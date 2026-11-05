package io.groo.damda.asset.service;

import io.groo.core.type.YNType;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.domain.AssetBalanceHistory;
import io.groo.damda.asset.domain.AssetTransfer;
import io.groo.damda.asset.repository.AssetBalanceHistoryRepository;
import io.groo.damda.asset.type.AssetSignPolicy;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.asset.type.BalanceChannel;
import io.groo.damda.asset.type.BalanceSourceType;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.core.time.UserClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.TreeMap;
import java.util.ArrayList;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import io.groo.damda.asset.repository.AssetRepository;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자산 잔액 이력(asset_balance_history) 적재 + 기준시각 잔액 조회 (단일 진실 공급원).
 *
 * <p>잔액이 바뀌는 모든 서비스 경로에서 이 컴포넌트로 이력을 남긴다. 개별 자산 잔액 ·
 * summary · trend · byType 이 전부 이력이라는 한 소스에서 나와 항상 일치한다.
 * 조회는 <b>SQL 집계</b>로 "기준시각 이하 최신 절대 앵커 + 그 이후 flow 합" 을 구한다 —
 * 이력을 앱으로 가져와 접지 않는다. 자산에 잔액 캐시 컬럼도 두지 않는다(금액을 낡은 값으로
 * 판단하는 사고가 반복돼서 없앴다).
 * 호출자(@Transactional)의 트랜잭션에 합류한다.
 */
@Service
@RequiredArgsConstructor
public class AssetBalanceHistoryService {

    private final AssetBalanceHistoryRepository repository;
    private final UserClock userClock;
    private final AssetRepository assetRepository;
    /** 체크카드 연결 계좌가 다른 통화로 바뀔 때 옛 지출의 원화 금액을 다시 읽는다(외화가 낄 때만). */
    private final io.groo.damda.expense.repository.ExpenseRepository expenseRepository;

    // === 쓰기 ===

    /**
     * 자산 생성 — 초기 잔액 절대 앵커.
     *
     * <p>앵커 시각은 <b>분 시작으로 내린다</b>. 거래의 벽시계(expenseDate·transferDate)는
     * 분 단위(초 00)인데 앵커가 초를 들고 있으면, 자산을 만들고 같은 분에 기록한 거래가
     * 앵커보다 과거(21:47:00 &lt; 21:47:37)로 떨어져 잔액에서 조용히 빠진다 — dev 에서
     * 계좌 생성 직후의 이체가 출금 쪽만 반영됐다. 생성 이전에 이 자산의 거래가 실존할 수
     * 없으므로 분 시작으로 내려도 실거래를 앵커 앞으로 넘길 위험이 없다. 같은 시각 tie 는
     * 집계 쿼리가 row_id 로 가른다 — INIT 가 먼저 저장되므로 그 뒤의 flow 가 포함된다.
     * MANUAL 앵커는 내리지 않는다 — 같은 분의 <b>앞선</b> 실거래가 있을 수 있고, 수동
     * 스냅샷은 이미 그 거래를 품은 값이라 내리면 이중 계상이 된다.
     */
    public void recordInit(Asset asset, LocalDateTime effectiveAt) {
        long initial = asset.getInitialBalance() != null ? asset.getInitialBalance() : 0L;
        if (isNoSnapshotCard(asset)) {
            // 신용카드 잔액은 **거래의 합**이다(D4). 앵커를 먼 과거에 0 으로 두면 그 뒤의
            // 모든 지출이 그대로 미결제로 쌓인다 — 사용액은 앵커가 아니라 "이전 미결제
            // 사용액" 거래 한 건이 들고 있다(AssetServiceImpl 이 만든다).
            repository.save(AssetBalanceHistory.of(
                asset.getUser(), asset, BalanceSourceType.INIT, asset.getRowId(),
                0L, NO_SNAPSHOT_ANCHOR));
            return;
        }
        repository.save(AssetBalanceHistory.of(
            asset.getUser(), asset, BalanceSourceType.INIT, asset.getRowId(),
            normalizeAnchor(asset, initial), effectiveAt.withSecond(0).withNano(0)));
    }

    /**
     * "앵커 없음" 을 뜻하는 INIT 시각 — 모든 날짜의 flow 가 이 뒤에 온다.
     *
     * <p>신용카드를 <b>현재 사용액 0</b> 으로 만들면 그 0 은 "이 시점의 미결제 스냅샷" 이라기보다
     * 대개 아직 안 적은 상태다. 카드는 지난 며칠 쓴 걸 나중에 입력하는 게 자연스러운 물건인데,
     * 생성 시각을 앵커로 두면 그 전 날짜의 지출이 청구서에는 들어가고 빚에는 안 잡혀
     * "결제 완료인데 이체 없음"(payoff 의 빚 캡)이 구조적으로 생긴다 — 운영에서 8/3 에 만든
     * 카드에 8/1·8/2 지출을 넣은 사용자가 정확히 이걸 겪었다(청구 225,733 / 빚 0 / 이체 NULL).
     * 그래서 사용액 0 카드는 앵커를 먼 과거에 두어 입력하는 모든 내역이 미결제로 잡히게 한다.
     * 사용액을 0 보다 크게 적은 카드는 사용자가 "이 시점 미결제액" 을 선언한 것이라 종전대로
     * 생성 시각 앵커를 쓰고, 통장·대출은 잔액이 사용자가 정확히 아는 값이라 그대로다.
     * 잔액 수동 수정(MANUAL)은 의도된 스냅샷이므로 이 규칙과 무관하다.
     */
    static final LocalDateTime NO_SNAPSHOT_ANCHOR = LocalDateTime.of(1970, 1, 1, 0, 0);

    /**
     * 앵커를 두지 않는 자산인가 — <b>신용카드 전부</b>다(D4, 2026-09-18 결정).
     *
     * <p>종전엔 사용액 0 인 카드만 그랬다. 그런데 사용액을 적어 넣은 카드도 같은 사고를
     * 겪는다: 한도 사용(잔액)은 "마지막 앵커 + 그 뒤 흐름" 이고 청구는 "회차 거래 합" 이라
     * 재료가 다르다. 달 중간에 카드를 만들며 사용액을 넣고 지난 날짜 지출을 나중에 적으면
     * 앵커 이전 지출이 잔액에서만 빠져 두 숫자가 어긋난다(운영 ZERO 카드 사고와 같은 기전).
     *
     * <p>신용카드는 원래 "거래의 기록" 이므로 앵커 없이 거래 합으로만 센다. 그러면 두 숫자가
     * 같은 재료에서 나온다. 통장·대출은 잔액이 사용자가 정확히 아는 값이라 그대로 앵커를 쓴다.
     */
    private static boolean isNoSnapshotCard(Asset asset) {
        return asset != null && asset.getAssetType() == AssetType.CREDIT_CARD;
    }

    /** 사용자의 수동 잔액 수정 — 절대 앵커(점프). 가계부 통계엔 영향 없음. */
    public void recordManual(Asset asset, long newBalance, LocalDateTime effectiveAt) {
        repository.save(AssetBalanceHistory.of(
            asset.getUser(), asset, BalanceSourceType.MANUAL, asset.getRowId(),
            normalizeAnchor(asset, newBalance), effectiveAt));
    }

    /**
     * 사용자가 넣은 절대 잔액을 규약에 맞춰 정규화한다.
     *
     * <p>부채 자산은 <b>잔액이 음수</b>다. 잔액을 움직이는 모든 경로가 이미 그 규약을
     * 따른다 — 신용카드는 결제하면 {@code -amount}, 환불하면 {@code +amount}, 대금을 갚으면
     * {@code +원금} 이라 0 으로 수렴하고, 대출도 상환 이체가 {@code +원금}, 추가 인출이
     * {@code -amount} 라 같은 방향이다. 화면은 "현재 사용액"/"잔액" 을 묻고 사용자는 당연히
     * 양수를 치므로, 막지 않고 여기서 뒤집는다.
     *
     * <p>여기서 안 잡으면 부호가 섞인다 — 잔액 자체는 부호에 무관한 소비자(순자산 {@code abs},
     * 청구액, 한도 게이지)를 통과해 버리고, 부호를 그대로 더하는 화면 합계에서만 어긋난다.
     * 실제로 대출이 여기 빠져 있어 양수 5,000,000 이 그대로 앵커가 됐고, 순자산이 부채만큼
     * <b>커지고</b>(총 부채 0), 상환 이체(+499,500)가 잔액을 5,499,500 으로 "늘리는" 화면이
     * 됐다 — dev 신규 가입 E2E 에서 발견.
     *
     * <p>규칙 자체는 {@link AssetSignPolicy} 에 있다 — 요약 집계도 같은 분류를 봐야 해서
     * 여기 숨겨 두지 않는다. 자산군 값은 <b>안 건드린다</b>: 마이너스 통장은 API 경계에서
     * 이미 음수로 확정된 채 내려오므로 여기서 다시 뒤집으면 안 된다.
     */
    private long normalizeAnchor(Asset asset, long amount) {
        return asset == null ? amount : AssetSignPolicy.normalizeAnchor(asset.getAssetType(), amount);
    }

    /**
     * 투자 평가액 갱신 — HOLDING 채널의 절대 앵커(점프). 토스 시세×수량 등 외부 평가액 반영 지점.
     *
     * <p>예수금 채널을 건드리지 않는다 — 증권계좌로 들어온 이체는 평가액과 무관하게 남아야 한다.
     */
    public void recordValuation(Asset asset, long valuation, LocalDateTime effectiveAt) {
        repository.save(AssetBalanceHistory.of(
            asset.getUser(), asset, BalanceSourceType.VALUATION, BalanceChannel.HOLDING,
            asset.getRowId(), valuation, effectiveAt));
    }

    /** 수입/지출 거래 — flow(INCOME=+, EXPENSE=-). asset 미연결/널이면 no-op. */
    public void recordExpense(Asset asset, Long expenseId, ExpenseType type, Long amount,
                              LocalDateTime effectiveAt) {
        if (asset == null || type == null || amount == null) {
            return;
        }
        Asset target = balanceTargetOf(asset);
        long signed = (type == ExpenseType.INCOME) ? amount : -amount;
        repository.save(AssetBalanceHistory.of(
            asset.getUser(), target, BalanceSourceType.EXPENSE, expenseId, signed, effectiveAt));
    }

    /**
     * 수입/지출 거래 — 금액이 <b>원화</b>(expense.amount)로 들어오는 경로. 잔액이 움직이는 자산의 통화로
     * 바꿔 적는다.
     *
     * <p>외화 계좌(USD 통장 · 외화 투자 계좌 예수금)는 자산 통화로 쌓이므로 원화를 그대로 적으면
     * $5 지출(6,500원)이 −$6,500 으로 남는다. 거래가 그 계좌 통화의 원 통화 금액을 들고 있으면 그
     * 값을, 아니면 원화 ÷ 계좌 환율을 쓴다({@link Asset#expenseAmountInAssetCurrency}). 원화 계좌는
     * {@link #recordExpense(Asset, Long, ExpenseType, Long, LocalDateTime)} 와 결과가 같다.
     *
     * <p>체크카드면 연결 계좌의 통화 · 환율로 바꾼다 — 실제로 빠지는 곳이 그 계좌다.
     */
    public void recordExpenseKrw(Asset asset, Long expenseId, ExpenseType type, Long krwAmount,
                                 BigDecimal originalAmount, String originalCurrency,
                                 LocalDateTime effectiveAt) {
        if (asset == null || type == null || krwAmount == null) {
            return;
        }
        Asset target = balanceTargetOf(asset);
        long units = target.expenseAmountInAssetCurrency(krwAmount, originalAmount, originalCurrency);
        long signed = (type == ExpenseType.INCOME) ? units : -units;
        repository.save(AssetBalanceHistory.of(
            asset.getUser(), target, BalanceSourceType.EXPENSE, expenseId, signed, effectiveAt));
    }

    /**
     * 잔액이 실제로 움직이는 자산.
     *
     * <p>체크카드는 계좌에 1:1 로 물린 결제 수단이라 자체 잔액이 없다 — 긁는 즉시 연결 계좌에서
     * 빠져나가므로 flow 를 카드가 아니라 그 계좌 앞으로 쌓는다. 카드 앞으로 쌓으면 통장 잔액이
     * 실제보다 많게 남는다(순자산 총액만 우연히 맞고 계좌·카드 배분이 전부 틀린다).
     *
     * <p>신용카드는 다르다 — 결제일에 {@code CardPaymentService} 가 이체로 몰아서 정산하므로
     * 그때까지 카드가 사용액을 들고 있어야 한다.
     *
     * <p>연결 계좌를 아직 안 고른 체크카드는 종전대로 카드 앞으로 — 지정 전 데이터를 잃지 않는다.
     */
    public Asset balanceTargetOf(Asset asset) {
        if (asset != null && asset.getAssetType() == AssetType.CHECK_CARD
            && asset.getPaymentAsset() != null) {
            return asset.getPaymentAsset();
        }
        return asset;
    }




    /**
     * 체크카드 연결 계좌를 지정·변경했을 때, 그 카드로 쓴 <b>기존</b> 지출 이력도 새 계좌로 옮긴다.
     *
     * <p>안 옮기면 과거 지출은 카드에, 신규 지출은 계좌에 쌓여 어느 쪽 잔액도 맞지 않는다.
     * 옮긴 뒤 이전 소속 자산까지 전부 재산정해야 빠져나간 만큼이 되돌려진다.
     */
    @Transactional
    public void relinkCheckCardHistory(Asset card, Asset newAccount) {
        if (card == null || newAccount == null || card.getAssetType() != AssetType.CHECK_CARD) {
            return;
        }
        List<AssetBalanceHistory> rows =
            repository.findActiveExpenseHistoryPaidWith(card.getRowId(), YNType.N);
        Set<Asset> affected = new LinkedHashSet<>();
        affected.add(card);
        affected.add(newAccount);
        for (AssetBalanceHistory h : rows) {
            Asset before = h.getAsset();
            affected.add(before); // 옮기기 전 소속 — 여기서도 빠져야 한다
            // 원화끼리면 금액 그대로 옮긴다(지금 동작). 외화가 끼면 옛 계좌 통화로 적힌 금액을 그대로
            // 옮기면 단위가 섞이므로, 거래의 원화 금액(원 통화 금액)을 새 계좌 통화로 다시 바꾼다.
            boolean foreign = newAccount.isForeignCurrency()
                || (before != null && before.isForeignCurrency());
            io.groo.damda.expense.domain.Expense e = foreign && expenseRepository != null
                && h.getSourceRowId() != null
                ? expenseRepository.findById(h.getSourceRowId()).orElse(null) : null;
            if (e == null || e.getAmount() == null) {
                h.moveTo(newAccount);
                continue;
            }
            long units = newAccount.expenseAmountInAssetCurrency(
                e.getAmount(), e.getOriginalAmount(), e.getOriginalCurrency());
            h.moveTo(newAccount, h.getAmount() != null && h.getAmount() < 0 ? -units : units);
        }
    }

    /**
     * 돈 칸(금액 · 통화 · 계좌 · 유형)이 그대로인 거래 수정 — 저장된 증감을 그대로 두고 시각만 옮긴다.
     *
     * <p>지웠다 다시 적으면 그 사이 계좌 환율을 고친 외화 계좌에서 메모만 고쳐도 잔액이 바뀐다
     * (USD 1,300 → 1,400: 13,000원 지출이 −$10 → −$9). 그래서 행을 남기고 시각만 고친다.
     *
     * @return 옮길 행이 있었고 모두 지금 잔액 대상 자산의 것이면 true. 아니면 아무것도 안 하고 false —
     *         호출자가 지웠다 다시 적는다
     */
    public boolean retimeExpense(Asset asset, Long expenseId, LocalDateTime effectiveAt) {
        if (asset == null || expenseId == null) {
            return false;
        }
        Asset target = balanceTargetOf(asset);
        List<AssetBalanceHistory> rows =
            repository.findActiveBySource(BalanceSourceType.EXPENSE, expenseId, YNType.N);
        if (rows.isEmpty() || !rows.stream().allMatch(h -> sameRow(h.getAsset(), target))) {
            return false;
        }
        rows.forEach(h -> h.retime(effectiveAt));
        return true;
    }

    /**
     * 환불 취소 — 환불 때 걷은 증감을 그대로 되살린다(돈 칸은 환불 동안 잠겨 있다).
     *
     * <p>걷은 행 중 가장 나중 것(row_id 최대)이 환불 직전의 기록이다. 그 행이 지금 잔액 대상 자산의
     * 것이면 같은 금액으로 다시 적고, 아니면(체크카드 연결 변경 등) 지금 환율로 새로 적는다.
     */
    public void restoreExpense(Asset asset, Long expenseId, ExpenseType type, Long krwAmount,
                               BigDecimal originalAmount, String originalCurrency,
                               LocalDateTime effectiveAt) {
        if (asset == null || type == null || krwAmount == null) {
            return;
        }
        Asset target = balanceTargetOf(asset);
        AssetBalanceHistory last = repository
            .findActiveBySource(BalanceSourceType.EXPENSE, expenseId, YNType.Y).stream()
            .filter(h -> h.getRowId() != null)
            .max(java.util.Comparator.comparing(AssetBalanceHistory::getRowId))
            .orElse(null);
        long expectedSign = type == ExpenseType.INCOME ? 1L : -1L;
        if (last != null && sameRow(last.getAsset(), target) && last.getAmount() != null
                && Long.signum(last.getAmount()) == expectedSign) {
            repository.save(AssetBalanceHistory.of(
                asset.getUser(), target, BalanceSourceType.EXPENSE, expenseId, last.getAmount(), effectiveAt));
            return;
        }
        recordExpenseKrw(asset, expenseId, type, krwAmount, originalAmount, originalCurrency, effectiveAt);
    }

    /**
     * 돈 칸이 그대로인 이체 수정 — 두 행의 금액을 그대로 두고 시각만 옮긴다(H1).
     *
     * @return 옮길 행이 있었으면 true
     */
    public boolean retimeTransfer(Long transferId, LocalDateTime effectiveAt) {
        List<AssetBalanceHistory> rows =
            repository.findActiveBySource(BalanceSourceType.TRANSFER, transferId, YNType.N);
        if (rows.isEmpty()) {
            return false;
        }
        rows.forEach(h -> h.retime(effectiveAt));
        return true;
    }

    private static boolean sameRow(Asset a, Asset b) {
        if (a == b) {
            return true;
        }
        return a != null && b != null && a.getRowId() != null && a.getRowId().equals(b.getRowId());
    }

    /**
     * 매수·매도 — 예수금(CASH) flow. 평가금액은 건드리지 않는다.
     *
     * <p>매수는 대금과 수수료가 함께 빠지고, 매도는 수수료를 뗀 나머지가 들어온다.
     * 보유 평가금액은 시세×수량으로 따로 산정되므로 여기서 손대면 이중 계상이 된다.
     */
    public void recordTrade(Asset asset, Long tradeId, Long cashDelta, LocalDateTime effectiveAt) {
        if (asset == null || cashDelta == null || cashDelta == 0L) {
            return;
        }
        repository.save(AssetBalanceHistory.of(
            asset.getUser(), asset, BalanceSourceType.TRADE, BalanceChannel.CASH,
            tradeId, cashDelta, effectiveAt));
    }

    /** 거래 취소 시 그 거래의 이력 row 를 soft-delete 후 자산 재산정. */
    public void removeTrade(Long tradeId) {
        softDeleteAndRecompute(repository.findActiveBySource(BalanceSourceType.TRADE, tradeId, YNType.N));
    }

    /**
     * 이 거래가 이 카드에 남긴 상계(CARD_SETTLED) 금액 — 없으면 0.
     *
     * <p>거래·카드별로 <b>활성 한 줄</b>만 둔다({@link #setCardSettled}). 자산을 옮기면 옛 카드에
     * 남은 줄(기한 안 환급의 짝)과 새 카드의 줄이 따로 있을 수 있어 카드까지 본다.
     */
    public long cardSettledAmount(Long expenseId, Long cardRowId) {
        return repository.findActiveBySource(BalanceSourceType.CARD_SETTLED, expenseId, YNType.N)
            .stream()
            .filter(h -> h.getAsset() != null && cardRowId.equals(h.getAsset().getRowId()))
            .mapToLong(AssetBalanceHistory::getAmount)
            .sum();
    }

    /**
     * 이 거래가 이 카드에 남기는 상계 금액을 {@code amount} 로 맞춘다(0 이면 지운다).
     *
     * <p>+금액은 "앱 밖에서 이미 결제된 기록용 빚" 을 지우고, −금액은 "기한 지나 돌려주지
     * 않는 결제분" 의 잔액을 붙잡는다 — 붙잡지 않으면 카드가 양수가 되어 자정 과납 스윕이
     * 돈을 돌려준다(닫힌 회차 규칙 R6).
     */
    public void setCardSettled(Asset card, Long expenseId, long amount, LocalDateTime effectiveAt) {
        repository.findActiveBySource(BalanceSourceType.CARD_SETTLED, expenseId, YNType.N).stream()
            .filter(h -> h.getAsset() != null && card.getRowId().equals(h.getAsset().getRowId()))
            .forEach(AssetBalanceHistory::softDelete);
        if (amount != 0L) {
            repository.save(AssetBalanceHistory.of(
                card.getUser(), card, BalanceSourceType.CARD_SETTLED, expenseId, amount, effectiveAt));
        }
    }

    /**
     * 환불 마크 때 붙잡은 몫 — 기한이 지나 돌려주지 않는 결제분·기록용을 카드에 묶어 둔다.
     *
     * <p>{@link BalanceSourceType#CARD_REFUND_HOLD} 로 따로 남겨 환불 취소가 이것만 지운다.
     */
    public void setCardRefundHold(Asset card, Long expenseId, long amount, LocalDateTime effectiveAt) {
        removeCardRefundHold(expenseId);
        if (amount != 0L) {
            repository.save(AssetBalanceHistory.of(
                card.getUser(), card, BalanceSourceType.CARD_REFUND_HOLD, expenseId, amount, effectiveAt));
        }
    }

    /** 환불 취소 — 환불 때 붙잡은 몫을 지운다. */
    public void removeCardRefundHold(Long expenseId) {
        repository.findActiveBySource(BalanceSourceType.CARD_REFUND_HOLD, expenseId, YNType.N)
            .forEach(AssetBalanceHistory::softDelete);
    }

    /** 거래 변경/삭제 시 해당 expense 의 이력 row 들을 soft-delete 후 영향 자산 재산정. */
    public void removeExpense(Long expenseId) {
        softDeleteAndRecompute(repository.findActiveBySource(BalanceSourceType.EXPENSE, expenseId, YNType.N));
    }

    /**
     * 자산 이체 — 출금자산 −(amount+fee), 입금자산 +원금 의 flow 두 row. 각 자산 통화로 적는다.
     *
     * <p>금액은 보내는 계좌 통화라 출금 쪽은 그대로({@link AssetTransfer#fromBalanceDelta}), 받는 쪽은
     * to_amount 가 있으면 그것(통화가 다름), NULL 이면 보낸 금액({@link AssetTransfer#toBalanceDelta}).
     * 원화끼리는 지금과 같다.
     */
    public void recordTransfer(AssetTransfer transfer) {
        // transfer_date 가 DATETIME 이라 실제 시각 그대로 — 지출(expense_date)과 같은 기준.
        LocalDateTime effectiveAt = transfer.getTransferDate();
        repository.save(AssetBalanceHistory.of(
            transfer.getUser(), transfer.getFromAsset(), BalanceSourceType.TRANSFER, transfer.getRowId(),
            transfer.fromBalanceDelta(), effectiveAt));
        // 입금 자산에는 원금만 — 이자는 부채를 줄이지 않고 은행으로 나가는 비용이라
        // 별도 지출 거래로 잡힌다(대출 상환에서만 0 보다 크다).
        repository.save(AssetBalanceHistory.of(
            transfer.getUser(), transfer.getToAsset(), BalanceSourceType.TRANSFER, transfer.getRowId(),
            transfer.toBalanceDelta(), effectiveAt));
    }

    /** 이체 삭제 시 양쪽 자산의 이력 row 를 soft-delete 후 영향 자산 재산정. */
    public void removeTransfer(Long transferId) {
        softDeleteAndRecompute(repository.findActiveBySource(BalanceSourceType.TRANSFER, transferId, YNType.N));
    }

    private void softDeleteAndRecompute(List<AssetBalanceHistory> rows) {
        Set<Asset> affected = new LinkedHashSet<>();
        for (AssetBalanceHistory h : rows) {
            h.softDelete();
            affected.add(h.getAsset());
        }
        for (Asset a : affected) {
        }
    }



    // === 읽기 (집계 쿼리) =======================================================

    /**
     * 기준시각의 자산별 잔액 — <b>DB 가 집계</b>한다. 이력을 앱으로 가져오지 않는다.
     *
     * @return 자산 rowId → 채널별 잔액. 이력이 없는 자산은 {@link Split#ZERO}.
     */
    public Map<Long, Split> balancesAt(Collection<Asset> assets, LocalDateTime at) {
        List<Long> ids = assets.stream().map(Asset::getRowId).filter(java.util.Objects::nonNull).toList();
        return balancesAtByIds(ids, at);
    }

    public Map<Long, Split> balancesAtByIds(Collection<Long> assetRowIds, LocalDateTime at) {
        Map<Long, Split> result = new LinkedHashMap<>();
        for (Long id : assetRowIds) {
            result.put(id, Split.ZERO);
        }
        if (assetRowIds.isEmpty()) {
            return result;
        }
        for (Object[] row : repository.aggregateBalances(assetRowIds, at)) {
            Long assetId = ((Number) row[0]).longValue();
            BalanceChannel channel = BalanceChannel.valueOf((String) row[1]);
            long amount = ((Number) row[2]).longValue();
            result.merge(assetId, channelSplit(channel, amount), Split::plus);
        }
        return result;
    }

    /** 한 자산의 기준시각 잔액. */
    public Split balanceAt(Asset asset, LocalDateTime at) {
        return balancesAt(List.of(asset), at).getOrDefault(asset.getRowId(), Split.ZERO);
    }

    /**
     * 여러 시점의 자산별 잔액 — 추이 그래프용. <b>쿼리 2회</b>로 끝난다.
     *
     * <p>첫 시점 잔액을 집계로 한 번 잡고, 그 뒤는 일자별 변동을 한 번에 받아 자바에서 누적한다.
     * 시점마다 쿼리를 날리면 12개월에 12번·26주에 26번이 된다.
     *
     * @param points 오름차순 시점들. 마지막 시점 이후 기록은 어디에도 안 들어간다.
     * @return 자산 rowId → 시점별 잔액(points 와 같은 순서·길이)
     */
    public Map<Long, List<Split>> balancesAtPoints(Collection<Asset> assets, List<LocalDateTime> points) {
        List<Long> ids = assets.stream().map(Asset::getRowId).filter(java.util.Objects::nonNull).toList();
        if (points.isEmpty()) {
            return Map.of();
        }
        LocalDateTime first = points.get(0);
        LocalDateTime last = points.get(points.size() - 1);

        Map<Long, Split> running = new LinkedHashMap<>(balancesAtByIds(ids, first));
        Map<Long, List<Split>> out = new LinkedHashMap<>();
        for (Long id : ids) {
            List<Split> seq = new ArrayList<>(points.size());
            seq.add(running.getOrDefault(id, Split.ZERO));
            out.put(id, seq);
        }
        if (ids.isEmpty() || points.size() == 1) {
            return out;
        }

        // 일자별 변동 — 자산·채널·날짜 오름차순으로 돌아온다.
        List<Object[]> deltas = repository.aggregateDailyDeltas(ids, first, last);
        // 날짜 → 그 날짜의 변동들. 시점 경계를 넘길 때마다 스냅샷을 찍는다.
        Map<LocalDate, List<Object[]>> byDate = new TreeMap<>();
        for (Object[] row : deltas) {
            byDate.computeIfAbsent(toLocalDate(row[2]), k -> new ArrayList<>()).add(row);
        }

        int idx = 1; // points.get(0) 은 이미 채웠다
        for (Map.Entry<LocalDate, List<Object[]>> e : byDate.entrySet()) {
            LocalDate date = e.getKey();
            // 이 날짜보다 앞선 시점들은 지금까지의 누적으로 확정한다.
            while (idx < points.size() && points.get(idx).toLocalDate().isBefore(date)) {
                snapshot(out, ids, running, idx);
                idx++;
            }
            for (Object[] row : e.getValue()) {
                apply(running, row);
            }
        }
        while (idx < points.size()) {
            snapshot(out, ids, running, idx);
            idx++;
        }
        return out;
    }

    private void snapshot(Map<Long, List<Split>> out, Collection<Long> ids,
                          Map<Long, Split> running, int idx) {
        for (Long id : ids) {
            out.get(id).add(running.getOrDefault(id, Split.ZERO));
        }
    }

    /** 앵커가 있는 날은 그 값으로 갈아엎고, 없는 날은 flow 를 더한다. */
    private void apply(Map<Long, Split> running, Object[] row) {
        Long assetId = ((Number) row[0]).longValue();
        BalanceChannel channel = BalanceChannel.valueOf((String) row[1]);
        Long anchor = row[3] != null ? ((Number) row[3]).longValue() : null;
        long flowAfter = row[4] != null ? ((Number) row[4]).longValue() : 0L;

        Split cur = running.getOrDefault(assetId, Split.ZERO);
        long cash = cur.cash();
        long holding = cur.holding();
        long next = anchor != null ? anchor + flowAfter
            : (channel == BalanceChannel.HOLDING ? holding : cash) + flowAfter;
        running.put(assetId, channel == BalanceChannel.HOLDING
            ? new Split(cash, next) : new Split(next, holding));
    }

    private static Split channelSplit(BalanceChannel channel, long amount) {
        return channel == BalanceChannel.HOLDING ? new Split(0L, amount) : new Split(amount, 0L);
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        return LocalDate.parse(value.toString());
    }

    /** 채널별 잔액 — 총잔액은 둘의 합. */
    public record Split(long cash, long holding) {
        public static final Split ZERO = new Split(0L, 0L);

        public long total() {
            return cash + holding;
        }

        /** 채널별로 더한다 — 집계 결과가 채널마다 한 행씩 오므로 합쳐 하나로 만든다. */
        public Split plus(Split other) {
            return new Split(cash + other.cash, holding + other.holding);
        }
    }
}
