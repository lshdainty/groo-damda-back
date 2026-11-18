package io.groo.damda.asset.service;

import io.groo.core.time.UserClock;
import io.groo.core.type.YNType;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.domain.AssetBalanceHistory;
import io.groo.damda.asset.domain.AssetTransfer;
import io.groo.damda.asset.repository.AssetBalanceHistoryRepository;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.asset.type.BalanceSourceType;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

/**
 * 가계부가 "현실 그대로" 흘러가는지 — 규칙을 시나리오로 끝까지 돌려 검증한다.
 *
 * <p>지향점은 <b>마이데이터 수동판</b> 이다. 거래를 넣으면 실제 돈의 흐름과 같은 자산이 같은
 * 방향으로 움직이고, 사용자가 일일이 못 맞춘 부분은 자산 금액 조정으로 덮어쓴다.
 *
 * <ol>
 *   <li>체크카드 — 연결 계좌에서 즉시 단건으로 빠진다</li>
 *   <li>신용카드 — 결제일에 계좌에서 빠지고 카드는 0 으로 돌아온다</li>
 *   <li>계좌이체 — 보내는 계좌에서 빠지고 받는 계좌로 들어온다(수수료는 보내는 쪽)</li>
 *   <li>취소 — 위 셋 모두 지우면 금액이 원복된다</li>
 *   <li>자산 금액 조정 — 총자산이 사용자가 적어넣은 금액이 된다</li>
 *   <li>자산 미등록 — 잔액은 건드리지 않고 거래만 남는다(단순 계산기)</li>
 * </ol>
 *
 * <p>mock 을 검증하는 대신 이력을 실제로 쌓아 잔액을 계산한다 — 산술이 맞는지가 요점이라
 * 저장소만 인메모리로 흉내 내고 서비스는 진짜를 쓴다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LedgerRulesScenarioTest {

    @Mock private AssetBalanceHistoryRepository repository;
    @Mock private UserClock userClock;
    @Mock private AssetRepository assetRepository;
    @InjectMocks private AssetBalanceHistoryService sut;

    /** 적재된 이력 (insert 순서 유지) — DB 대용. */
    private final List<AssetBalanceHistory> store = new ArrayList<>();
    /** expenseId → 그 거래의 결제 자산 rowId. 체크카드 재연결이 거래 기준으로 찾기 때문에 필요. */
    private final Map<Long, Long> paidWith = new HashMap<>();

    private User user;
    /** 이력이 미래로 밀리지 않도록 기준 시각은 시나리오 끝보다 뒤에 둔다. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 23, 59);

    @BeforeEach
    void setUp() {
        user = User.createUser(1L, "tester", "테스터", "tester@hangroo.io");
        ReflectionTestUtils.setField(user, "rowId", 1L);
        given(userClock.nowIn(any())).willReturn(NOW);

        willAnswer(inv -> {
            AssetBalanceHistory h = inv.getArgument(0);
            store.add(h);
            return h;
        }).given(repository).save(any(AssetBalanceHistory.class));

        given(repository.findActiveByAssetIds(any(), any())).willAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return store.stream()
                .filter(h -> h.getIsDeleted() == YNType.N)
                .filter(h -> h.getAsset() != null && ids.contains(h.getAsset().getRowId()))
                // 실제 쿼리와 같은 정렬 — 시각 오름차순, 동시각은 적재 순서.
                .sorted(Comparator.comparing(AssetBalanceHistory::getEffectiveAt)
                    .thenComparing(store::indexOf))
                .toList();
        });

        given(repository.findActiveBySource(any(), any(), any())).willAnswer(inv -> {
            BalanceSourceType type = inv.getArgument(0);
            Long sourceRowId = inv.getArgument(1);
            return store.stream()
                .filter(h -> h.getIsDeleted() == YNType.N)
                .filter(h -> h.getSourceType() == type && sourceRowId.equals(h.getSourceRowId()))
                .toList();
        });

        given(repository.findActiveExpenseHistoryPaidWith(any(), any())).willAnswer(inv -> {
            Long cardRowId = inv.getArgument(0);
            return store.stream()
                .filter(h -> h.getIsDeleted() == YNType.N)
                .filter(h -> h.getSourceType() == BalanceSourceType.EXPENSE)
                .filter(h -> cardRowId.equals(paidWith.get(h.getSourceRowId())))
                .toList();
        });
    }

    // === 시나리오 헬퍼 =========================================================

    private Asset asset(long rowId, AssetType type, long initial, Asset paymentAsset) {
        Asset a = Asset.createAsset(user, "자산" + rowId, type, initial, "KRW",
            null, null, null, null,
            0, YNType.Y, null, null, null, paymentAsset);
        ReflectionTestUtils.setField(a, "rowId", rowId);
        sut.recordInit(a, LocalDateTime.of(2026, 8, 1, 0, 0));
        return a;
    }

    private void spend(Asset paidWithAsset, long expenseId, long amount, int day) {
        if (paidWithAsset != null) {
            paidWith.put(expenseId, paidWithAsset.getRowId());
        }
        sut.recordExpense(paidWithAsset, expenseId, ExpenseType.EXPENSE, amount,
            LocalDateTime.of(2026, 8, day, 12, 0));
    }

    private AssetTransfer transfer(Asset from, Asset to, long rowId, long amount, long fee, int day) {
        AssetTransfer t = AssetTransfer.createTransfer(user, from, to, amount, fee, 0L, "이체",
            LocalDateTime.of(2026, 8, day, 12, 0));
        ReflectionTestUtils.setField(t, "rowId", rowId);
        sut.recordTransfer(t);
        return t;
    }

    /**
     * 이력에서 잔액을 다시 읽는다 — 규칙이 맞는지 보는 게 목적이라 산술만 흉내 낸다.
     * 실물은 SQL 집계이고, 그쪽이 이 규칙대로 도는지는 BalanceAggregateParityTest 가 본다.
     */
    private long balanceOf(Asset a) {
        long running = 0;
        List<AssetBalanceHistory> rows = store.stream()
            .sorted(java.util.Comparator.comparing(AssetBalanceHistory::getEffectiveAt)
                .thenComparing(store::indexOf))
            .toList();
        for (AssetBalanceHistory h : rows) {
            if (!h.getAsset().getRowId().equals(a.getRowId())
                || h.getIsDeleted() == YNType.Y
                || h.getEffectiveAt().isAfter(NOW)) {
                continue;
            }
            running = h.isAbsolute() ? h.getAmount() : running + h.getAmount();
        }
        return running;
    }

    // === 규칙 1 · 4 — 체크카드는 즉시, 취소하면 원복 ============================

    @Test
    @DisplayName("규칙1·4 — 체크카드로 쓰면 연결 계좌에서 즉시 빠지고, 취소하면 되돌아온다")
    void checkCardSpendsFromLinkedAccountAndRestoresOnCancel() {
        Asset account = asset(10L, AssetType.BANK_ACCOUNT, 1_000_000L, null);
        Asset card = asset(19L, AssetType.CHECK_CARD, 0L, account);

        spend(card, 100L, 12_000L, 3);
        assertThat(balanceOf(account)).isEqualTo(988_000L);
        assertThat(balanceOf(card)).isZero(); // 카드는 잔액을 들지 않는다

        spend(card, 101L, 3_000L, 4);
        assertThat(balanceOf(account)).isEqualTo(985_000L); // 단건으로 계속 빠진다

        sut.removeExpense(100L);
        assertThat(balanceOf(account)).isEqualTo(997_000L); // 12,000 만 원복
        assertThat(balanceOf(card)).isZero();
    }

    @Test
    @DisplayName("규칙1 — 체크카드 환불(수입)은 연결 계좌로 들어온다")
    void checkCardRefundGoesBackToLinkedAccount() {
        Asset account = asset(10L, AssetType.BANK_ACCOUNT, 1_000_000L, null);
        Asset card = asset(19L, AssetType.CHECK_CARD, 0L, account);

        spend(card, 100L, 12_000L, 3);
        paidWith.put(101L, card.getRowId());
        sut.recordExpense(card, 101L, ExpenseType.INCOME, 12_000L,
            LocalDateTime.of(2026, 8, 5, 12, 0));

        assertThat(balanceOf(account)).isEqualTo(1_000_000L);
    }

    // === 규칙 2 · 4 — 신용카드는 결제일에, 취소하면 원복 ========================

    @Test
    @DisplayName("규칙2·4 — 신용카드는 결제일까지 카드가 들고 있다가 계좌에서 빠지고, 결제를 지우면 원복된다")
    void creditCardSettlesOnPaymentDay() {
        Asset account = asset(10L, AssetType.BANK_ACCOUNT, 1_000_000L, null);
        Asset card = asset(20L, AssetType.CREDIT_CARD, 0L, account);

        spend(card, 200L, 50_000L, 3);
        // 결제일 전 — 통장은 그대로, 카드가 사용액을 들고 있다.
        assertThat(balanceOf(account)).isEqualTo(1_000_000L);
        assertThat(balanceOf(card)).isEqualTo(-50_000L);

        // 결제일: 계좌 → 카드 이체로 정산 (CardPaymentService.createPaymentTransfer 와 같은 모양)
        AssetTransfer payment = transfer(account, card, 900L, 50_000L, 0L, 14);
        assertThat(balanceOf(account)).isEqualTo(950_000L);
        assertThat(balanceOf(card)).isZero();

        // 결제를 취소하면 양쪽 다 결제 직전으로 돌아간다.
        sut.removeTransfer(payment.getRowId());
        assertThat(balanceOf(account)).isEqualTo(1_000_000L);
        assertThat(balanceOf(card)).isEqualTo(-50_000L);
    }

    // === 규칙 3 · 4 — 계좌이체, 취소하면 원복 ==================================

    @Test
    @DisplayName("규칙3·4 — 계좌이체는 보내는 쪽에서 수수료까지 빠지고, 취소하면 양쪽 다 원복된다")
    void transferMovesMoneyAndRestoresOnCancel() {
        Asset from = asset(10L, AssetType.BANK_ACCOUNT, 1_000_000L, null);
        Asset to = asset(11L, AssetType.BANK_ACCOUNT, 200_000L, null);

        AssetTransfer t = transfer(from, to, 900L, 300_000L, 500L, 5);
        assertThat(balanceOf(from)).isEqualTo(699_500L); // 300,000 + 수수료 500
        assertThat(balanceOf(to)).isEqualTo(500_000L);   // 수수료는 받는 쪽과 무관

        sut.removeTransfer(t.getRowId());
        assertThat(balanceOf(from)).isEqualTo(1_000_000L);
        assertThat(balanceOf(to)).isEqualTo(200_000L);
    }

    // === 규칙 5 — 자산 금액 조정이 이긴다 =====================================

    @Test
    @DisplayName("규칙5 — 자산 금액을 조정하면 그 값이 총자산이 되고, 이후 거래만 다시 반영된다")
    void manualAdjustmentWins() {
        Asset account = asset(10L, AssetType.BANK_ACCOUNT, 1_000_000L, null);
        spend(account, 100L, 12_000L, 3);
        assertThat(balanceOf(account)).isEqualTo(988_000L);

        // 실제 통장은 980,000 이었다 — 사용자가 직접 맞춘다.
        sut.recordManual(account, 980_000L, LocalDateTime.of(2026, 8, 10, 12, 0));
        assertThat(balanceOf(account)).isEqualTo(980_000L);

        // 조정 이후 거래는 다시 정상 반영.
        spend(account, 101L, 30_000L, 11);
        assertThat(balanceOf(account)).isEqualTo(950_000L);
    }

    @Test
    @DisplayName("규칙5 — 조정 시점보다 앞선 거래를 뒤늦게 넣어도 총자산은 흔들리지 않는다")
    void backdatedExpenseDoesNotDisturbAdjustedBalance() {
        Asset account = asset(10L, AssetType.BANK_ACCOUNT, 1_000_000L, null);
        sut.recordManual(account, 980_000L, LocalDateTime.of(2026, 8, 10, 12, 0));

        // 조정한 잔액엔 이미 반영돼 있는 과거 지출 — 또 빼면 이중 차감이 된다.
        spend(account, 100L, 12_000L, 3);

        assertThat(balanceOf(account)).isEqualTo(980_000L);
    }

    // === 규칙 6 — 자산을 안 붙이면 단순 계산기 ================================

    @Test
    @DisplayName("규칙6 — 자산을 안 고른 거래는 잔액을 건드리지 않는다")
    void expenseWithoutAssetTouchesNothing() {
        Asset account = asset(10L, AssetType.BANK_ACCOUNT, 1_000_000L, null);

        spend(null, 100L, 12_000L, 3);

        assertThat(balanceOf(account)).isEqualTo(1_000_000L);
        assertThat(store).noneMatch(h -> h.getSourceType() == BalanceSourceType.EXPENSE);
    }

    @Test
    @DisplayName("규칙6 — 초기 잔액 0 으로 시작하면 쓴 만큼 마이너스로 누적된다")
    void zeroStartAccumulatesNegative() {
        Asset account = asset(10L, AssetType.BANK_ACCOUNT, 0L, null);

        spend(account, 100L, 12_000L, 3);
        spend(account, 101L, 8_000L, 4);

        assertThat(balanceOf(account)).isEqualTo(-20_000L);
    }

    // === 연결 계좌를 나중에 지정하는 경로 =====================================

    @Test
    @DisplayName("연결 계좌를 뒤늦게 지정하면 그 카드로 쓴 과거 거래까지 계좌에서 빠진다")
    void relinkPullsPastSpendIntoAccount() {
        Asset account = asset(10L, AssetType.BANK_ACCOUNT, 1_000_000L, null);
        // 연결 전 — 카드 앞으로 쌓인다(지정 전 데이터 보존).
        Asset card = asset(19L, AssetType.CHECK_CARD, 0L, null);
        spend(card, 100L, 12_000L, 3);
        assertThat(balanceOf(account)).isEqualTo(1_000_000L);
        assertThat(balanceOf(card)).isEqualTo(-12_000L);

        ReflectionTestUtils.setField(card, "paymentAsset", account);
        sut.relinkCheckCardHistory(card, account);

        assertThat(balanceOf(account)).isEqualTo(988_000L);
        assertThat(balanceOf(card)).isZero();
    }
}
