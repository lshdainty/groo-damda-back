package io.groo.damda.asset.service.dto;

import java.math.BigDecimal;
import io.groo.damda.asset.type.HoldingType;
import io.groo.core.type.YNType;
import io.groo.damda.common.patch.Patch;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.service.AssetBalanceHistoryService;
import io.groo.damda.asset.domain.AssetHolding;
import io.groo.damda.asset.domain.AssetTransfer;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.card.domain.CardCatalog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class AssetServiceDto {

    // === Asset ===
    public record CreateAssetCommand(
        Long userRowId,
        String assetName,
        AssetType assetType,
        Long balance,
        /**
         * 마이너스 통장 여부 — true 면 잔액을 음수로 저장한다({@code BANK_ACCOUNT} + 음수).
         *
         * <p>null 은 "이 필드를 모르는 클라이언트" 다. 그때는 보낸 부호를 그대로 존중한다 —
         * 무조건 abs() 를 걸면 옛 앱이 마이너스 통장을 저장만 해도 부호가 뒤집힌다.
         */
        Boolean isOverdraft,
        String currency,
        /** 원화 환산율 (통화 1단위당 원화). KRW 는 1. null 이면 기존 값 유지·신규는 1. */
        java.math.BigDecimal exchangeRate,
        String color,
        String institution,
        String memo,
        Integer sortOrder,
        YNType isIncludedInTotal,
        YNType isAmountHidden,
        Long cardCatalogRowId,
        Long creditLimit,
        Integer paymentDay,
        Long paymentAssetRowId,
        // 투자 보유 목록 (INVESTMENT 전용). null=없음.
        List<HoldingCommand> holdings,
        /**
         * 신용카드: 사용액 중 <b>결제를 기다리던 지난달 청구분</b>(양수, 2026-09-22). 다가오는 결제일에
         * 청구된다 — {@code balance} 는 그 뒤 쓴 금액이다. 결제 대기 청구분이 없을 때(이번 달 결제일이
         * 지났거나 오늘) 0 보다 크면 거절한다. null·0 이면 없음.
         */
        Long dueCarryoverAmount
    ) {
        /** 결제 대기 청구분을 모르는 호출자 — 없음으로 본다. */
        public CreateAssetCommand(Long userRowId, String assetName, AssetType assetType, Long balance,
                                  Boolean isOverdraft, String currency, java.math.BigDecimal exchangeRate,
                                  String color, String institution, String memo, Integer sortOrder,
                                  YNType isIncludedInTotal, YNType isAmountHidden, Long cardCatalogRowId,
                                  Long creditLimit, Integer paymentDay, Long paymentAssetRowId,
                                  List<HoldingCommand> holdings) {
            this(userRowId, assetName, assetType, balance, isOverdraft, currency, exchangeRate, color,
                institution, memo, sortOrder, isIncludedInTotal, isAmountHidden, cardCatalogRowId, creditLimit,
                paymentDay, paymentAssetRowId, holdings, null);
        }
    }

    /**
     * 수정 명령 — 각 칸은 "안 왔다 / 지워라 / 이 값으로" 셋 중 하나다({@link Patch}).
     * {@code holdings} 만 종전 그대로다({@code null}=무변경, 리스트=전체 교체).
     */
    public record UpdateAssetCommand(
        Patch<String> assetName,
        Patch<AssetType> assetType,
        Patch<Long> balance,
        /**
         * 마이너스 통장 여부 — true 면 잔액을 음수로 저장한다({@code BANK_ACCOUNT} + 음수).
         *
         * <p>null 은 "이 필드를 모르는 클라이언트" 다. 그때는 보낸 부호를 그대로 존중한다 —
         * 무조건 abs() 를 걸면 옛 앱이 마이너스 통장을 저장만 해도 부호가 뒤집힌다.
         */
        Patch<Boolean> isOverdraft,
        Patch<String> currency,
        /** 원화 환산율 (통화 1단위당 원화). KRW 는 1. 안 오면 기존 값 유지·신규는 1. */
        Patch<java.math.BigDecimal> exchangeRate,
        Patch<String> color,
        Patch<String> institution,
        Patch<String> memo,
        Patch<YNType> isIncludedInTotal,
        Patch<YNType> isAmountHidden,
        Patch<Long> cardCatalogRowId,
        Patch<Long> creditLimit,
        Patch<Integer> paymentDay,
        Patch<Long> paymentAssetRowId,
        // 투자 보유 목록 — null=무변경, 리스트=전체 교체(빈 리스트=전부 삭제).
        List<HoldingCommand> holdings,
        /**
         * 신용카드의 "이전 미결제 사용액"(이월 금액, D7) — 신용카드는 {@code balance} 를 무시하고 이 칸으로만
         * 이월 거래를 고친다. 키가 없거나 null 이면 그대로.
         */
        Patch<Long> carryoverAmount,
        /**
         * 신용카드: 결제를 기다리던 지난달 청구분({@link CreateAssetCommand#dueCarryoverAmount()}) — 키가
         * 없거나 null 이면 그대로. 등록한 달의 그 회차 결제일 전까지만 넣고 고칠 수 있다.
         */
        Patch<Long> dueCarryoverAmount
    ) {
        /** 결제 대기 청구분 칸을 모르는 호출자 — 그대로 둔다. */
        public UpdateAssetCommand(Patch<String> assetName, Patch<AssetType> assetType, Patch<Long> balance,
                                  Patch<Boolean> isOverdraft, Patch<String> currency,
                                  Patch<java.math.BigDecimal> exchangeRate, Patch<String> color,
                                  Patch<String> institution, Patch<String> memo, Patch<YNType> isIncludedInTotal,
                                  Patch<YNType> isAmountHidden, Patch<Long> cardCatalogRowId,
                                  Patch<Long> creditLimit, Patch<Integer> paymentDay,
                                  Patch<Long> paymentAssetRowId, List<HoldingCommand> holdings,
                                  Patch<Long> carryoverAmount) {
            this(assetName, assetType, balance, isOverdraft, currency, exchangeRate, color, institution, memo,
                isIncludedInTotal, isAmountHidden, cardCatalogRowId, creditLimit, paymentDay, paymentAssetRowId,
                holdings, carryoverAmount, Patch.absent());
        }

        /** 이월 금액 칸을 모르는 호출자 — 이월은 그대로 둔다. */
        public UpdateAssetCommand(Patch<String> assetName, Patch<AssetType> assetType, Patch<Long> balance,
                                  Patch<Boolean> isOverdraft, Patch<String> currency,
                                  Patch<java.math.BigDecimal> exchangeRate, Patch<String> color,
                                  Patch<String> institution, Patch<String> memo, Patch<YNType> isIncludedInTotal,
                                  Patch<YNType> isAmountHidden, Patch<Long> cardCatalogRowId,
                                  Patch<Long> creditLimit, Patch<Integer> paymentDay,
                                  Patch<Long> paymentAssetRowId, List<HoldingCommand> holdings) {
            this(assetName, assetType, balance, isOverdraft, currency, exchangeRate, color, institution, memo,
                isIncludedInTotal, isAmountHidden, cardCatalogRowId, creditLimit, paymentDay, paymentAssetRowId,
                holdings, Patch.absent(), Patch.absent());
        }
    }

    /**
     * 신용카드의 결제 대기 청구분 칸(2026-09-22) — 폼이 이 칸을 그릴지·고칠 수 있는지·언제 결제되는지.
     *
     * @param amount      지금 청구분(없으면 0)
     * @param locked      그 회차 결제일이 됐다 — 읽기 전용(D15 와 같은 규칙)
     * @param paymentDate 그 회차의 결제일 — 칸 이름에 쓴다("9월 25일에 결제될 금액")
     */
    public record DueCarryover(long amount, boolean locked, java.time.LocalDate paymentDate) {}

    /**
     * 투자 보유 입력 — linked=true: symbol+quantity 필수 / false: holdingName+holdingValue 필수(quantity 선택).
     * holdingType 미지정은 STOCK 으로 본다(구버전 클라이언트 하위호환).
     */
    public record HoldingCommand(
        /**
         * 기존 보유 행 아이디 — 있으면 제자리 수정, 없으면 신규.
         *
         * <p>이게 없으면 편집할 때마다 보유를 통째로 지우고 새로 만들게 되고, row_id 가
         * 매번 바뀌어 거래(asset_trade)가 이름으로 묶일 수밖에 없다. 그러면 종목명을
         * 바꾸는 순간 원가와 매매 이력이 끊긴다.
         */
        Long rowId,
        HoldingType holdingType,
        Boolean linked,
        /**
         * stock_master 기준 시장코드 — 선택. 안 보내오면 서버가 심볼로 1회 해석하고,
         * 여러 시장에 걸리면 비워 둔다(구버전 클라이언트는 안 보낸다).
         */
        String marketCode,
        String symbol,
        BigDecimal quantity,
        String holdingName,
        Long holdingValue,
        /** 총 매수원가 (원화). null 이면 기존 값 유지 — 실현손익 계산의 기준이다. */
        Long totalCost,
        /** 목록에서의 자리. null 이면 보낸 배열의 인덱스가 자리가 된다(QA 2026-09-07 #91). */
        Integer sortOrder
    ) {}

    public record HoldingInfo(
        Long rowId,
        HoldingType holdingType,
        boolean linked,
        /** 저장된 시장코드. 확정하지 못한 행은 null 이다. */
        String marketCode,
        String symbol,
        BigDecimal quantity,
        String holdingName,
        Long holdingValue,
        /** 총 매수원가 (원화, 수수료 포함). 평가액과의 차이가 평가손익이다. */
        Long totalCost,
        /** 평단가 — 총원가 / 수량. 수량이 없으면 null. */
        BigDecimal avgPrice,
        Integer sortOrder
    ) {
        public static HoldingInfo from(AssetHolding h) {
            return new HoldingInfo(
                h.getRowId(), h.getHoldingType(), h.isLinked(), h.getMarketCode(), h.getSymbol(),
                h.getQuantity(),
                h.getHoldingName(), h.getHoldingValue(), h.getTotalCost(), h.avgPrice(),
                h.getSortOrder());
        }
    }

    public record CardCatalogBrief(
        Long rowId,
        String cardName,
        String imgUrl,
        String companyName,
        String companyLogoUrl
    ) {
        public static CardCatalogBrief from(CardCatalog c) {
            if (c == null) return null;
            String companyName = null;
            String companyLogoUrl = null;
            if (c.getCompany() != null) {
                companyName = c.getCompany().getName();
                companyLogoUrl = c.getCompany().getLogoUrl();
            }
            return new CardCatalogBrief(c.getRowId(), c.getCardName(), c.getImgUrl(), companyName, companyLogoUrl);
        }
    }

    public record AssetInfo(
        Long rowId,
        Long userRowId,
        String assetName,
        AssetType assetType,
        Long balance,
        /** 예수금·현금 잔액. 투자 자산의 매수 대기 자금이 여기 잡힌다. balance = cashBalance + holdingBalance */
        Long cashBalance,
        /** 보유 종목 평가금액. 보유가 없으면 0. */
        Long holdingBalance,
        
        String currency,
        /** 원화 환산율 (통화 1단위당 원화). KRW 는 1. null 이면 기존 값 유지·신규는 1. */
        java.math.BigDecimal exchangeRate,
        String color,
        String institution,
        String memo,
        Integer sortOrder,
        YNType isIncludedInTotal,
        YNType isAmountHidden,
        CardCatalogBrief cardCatalog,
        Long creditLimit,
        Integer paymentDay,
        Long paymentAssetRowId,
        /** 레거시 단일 연동의 시장코드. 확정하지 못했으면 null. */
        String marketCode,
        String symbol,
        Long quantity,
        // 투자 보유 목록 (INVESTMENT 외/보유 없음 = 빈 리스트)
        List<HoldingInfo> holdings,
        LocalDateTime createAt,
        LocalDateTime modifyAt,
        /**
         * 체크카드의 이번 달(1일~) 사용 합계 — 체크카드만 값, 그 외 null.
         * 결제가 연결 계좌에서 즉시 빠져 balance 가 항상 0 이라, 이 값이 없으면
         * 화면에서 "이 카드로 얼마 썼는지" 를 알 길이 없다. 순자산 계산과는 무관하다
         * (돈은 이미 계좌에서 빠졌다 — balance 에 실으면 이중 차감이 된다).
         */
        Long monthlyUsedAmount,
        /** 신용카드만: 지금 이월 금액("이전 미결제 사용액"), 없으면 0. 그 외 null(D7). */
        Long carryoverAmount,
        /** 신용카드: 이월 거래가 든 회차의 결제일이 됐으면 true — 이월 금액 칸 읽기 전용(D15). */
        boolean carryoverLocked,
        /** 결제일 있는 신용카드: 이 날짜 이하 거래는 닫힌 회차(D5·D12). 그 외 null. */
        java.time.LocalDate cardClosedThrough,
        /**
         * 결제일 있는 신용카드: 결제일이 오늘보다 뒤인 첫 회차의 <b>실제 결제일</b>(결제일 이력 반영, D5).
         * 그 회차는 이 날짜의 전달 1일~말일이고, 그 뒤 회차는 지금 결제일로 결제된다 — 결제일을 바꿔
         * 옛 결제일로 나갈 회차가 남아 있을 때 화면이 지금 결제일로 세면 틀린다(QA 26 4). 그 외 null.
         */
        java.time.LocalDate nextPaymentDate,
        /**
         * 신용카드: 결제 대기 청구분 칸. 청구분이 있거나, 카드를 등록한 달의 그 회차 결제일이 아직 안
         * 왔으면 값 — 폼이 칸을 그린다. 그 외 null(칸 없음).
         */
        DueCarryover dueCarryover,
        /**
         * 원화 환산 합계 = round(cashBalance × 환율) + holdingBalance. 예수금만 자산 통화이고 보유 평가는 원화라
         * 환율을 곱하는 건 예수금뿐이다. 순자산·요약·추이와 같은 식이라 줄·묶음 합계가 서버와 어긋나지 않는다.
         * 원화 자산은 balance 와 같다. 환산을 모르는 호출자(테스트 등)는 null.
         */
        Long balanceInKrw
    ) {
        /** 카드 상태를 모르는 호출자(테스트 등) — 카드 칸은 비운다. */
        public AssetInfo(Long rowId, Long userRowId, String assetName, AssetType assetType, Long balance,
                         Long cashBalance, Long holdingBalance, String currency,
                         java.math.BigDecimal exchangeRate, String color, String institution, String memo,
                         Integer sortOrder, YNType isIncludedInTotal, YNType isAmountHidden,
                         CardCatalogBrief cardCatalog, Long creditLimit, Integer paymentDay,
                         Long paymentAssetRowId, String marketCode, String symbol, Long quantity,
                         List<HoldingInfo> holdings, LocalDateTime createAt, LocalDateTime modifyAt,
                         Long monthlyUsedAmount) {
            this(rowId, userRowId, assetName, assetType, balance, cashBalance, holdingBalance, currency,
                exchangeRate, color, institution, memo, sortOrder, isIncludedInTotal, isAmountHidden, cardCatalog,
                creditLimit, paymentDay, paymentAssetRowId, marketCode, symbol, quantity, holdings, createAt,
                modifyAt, monthlyUsedAmount, null, false, null, null, null, null);
        }

        /** 잔액 없이(0) 만든다 — 잔액이 화면에 안 쓰이는 응답 전용. */
        public static AssetInfo from(Asset asset) {
            return from(asset, List.of(), null);
        }

        /** 잔액 없이(0) 만든다 — 호출부가 집계 결과를 따로 붙일 때. */
        public static AssetInfo from(Asset asset, List<HoldingInfo> holdings) {
            return from(asset, holdings, null);
        }

        /**
         * @param split 이력에서 집계한 채널별 잔액. 자산에 잔액 캐시 컬럼은 없다 —
         *             금액은 언제나 이력이 진실이고, 캐시를 두면 어긋난 값으로 판단하게 된다.
         */
        public static AssetInfo from(Asset asset, List<HoldingInfo> holdings,
                                     AssetBalanceHistoryService.Split split) {
            AssetBalanceHistoryService.Split s =
                split != null ? split : AssetBalanceHistoryService.Split.ZERO;
            long balance = s.total();
            long cash = s.cash();
            long holdingValue = s.holding();
            return new AssetInfo(
                asset.getRowId(),
                asset.getUser().getRowId(),
                asset.getAssetName(),
                asset.getAssetType(),
                balance,
                cash,
                holdingValue,
                asset.getCurrency(),
                asset.getExchangeRate(),
                asset.getColor(),
                asset.getInstitution(),
                asset.getMemo(),
                asset.getSortOrder(),
                asset.getIsIncludedInTotal(),
                asset.getIsAmountHidden(),
                CardCatalogBrief.from(asset.getCardCatalog()),
                asset.getCreditLimit(),
                asset.getPaymentDay(),
                asset.getUsablePaymentAsset() != null ? asset.getUsablePaymentAsset().getRowId() : null,
                asset.getMarketCode(),
                asset.getSymbol(),
                asset.getQuantity(),
                holdings != null ? holdings : List.of(),
                asset.getCreateAt(),
                asset.getModifyAt(),
                null
            ).withBalanceInKrw(asset.balanceInKrw(cash, holdingValue));
        }

        /** 원화 환산 합계를 붙인다. */
        public AssetInfo withBalanceInKrw(Long krw) {
            return new AssetInfo(rowId, userRowId, assetName, assetType, balance, cashBalance,
                holdingBalance, currency, exchangeRate, color, institution, memo, sortOrder,
                isIncludedInTotal, isAmountHidden, cardCatalog, creditLimit, paymentDay, paymentAssetRowId,
                marketCode, symbol, quantity, holdings, createAt, modifyAt, monthlyUsedAmount,
                carryoverAmount, carryoverLocked, cardClosedThrough, nextPaymentDate, dueCarryover, krw);
        }

        public AssetInfo withMonthlyUsedAmount(Long monthlyUsed) {
            return new AssetInfo(rowId, userRowId, assetName, assetType, balance, cashBalance,
                holdingBalance, currency, exchangeRate, color, institution, memo, sortOrder,
                isIncludedInTotal, isAmountHidden, cardCatalog, creditLimit, paymentDay, paymentAssetRowId,
                marketCode, symbol, quantity, holdings, createAt, modifyAt, monthlyUsed,
                carryoverAmount, carryoverLocked, cardClosedThrough, nextPaymentDate, dueCarryover, balanceInKrw);
        }

        /** 신용카드 상태(이월 금액·잠금·닫힌 회차 끝·다음 실제 결제일)를 붙인다. */
        public AssetInfo withCardState(Long carryover, boolean locked, java.time.LocalDate closedThrough,
                                       java.time.LocalDate nextPayment) {
            return withCardState(carryover, locked, closedThrough, nextPayment, null);
        }

        /** 신용카드 상태 + 결제 대기 청구분 칸. */
        public AssetInfo withCardState(Long carryover, boolean locked, java.time.LocalDate closedThrough,
                                       java.time.LocalDate nextPayment, DueCarryover due) {
            return new AssetInfo(rowId, userRowId, assetName, assetType, balance, cashBalance,
                holdingBalance, currency, exchangeRate, color, institution, memo, sortOrder,
                isIncludedInTotal, isAmountHidden, cardCatalog, creditLimit, paymentDay, paymentAssetRowId,
                marketCode, symbol, quantity, holdings, createAt, modifyAt, monthlyUsedAmount,
                carryover, locked, closedThrough, nextPayment, due, balanceInKrw);
        }
    }

    public record ReorderItem(
        Long assetId,
        Integer sortOrder
    ) {}

    public record AssetSummary(
        Long totalBalance,          // 기존 호환: 모든 자산 balance 합 (부채도 양수로 포함)
        Long totalAssets,           // 순수 자산 합 (BANK_ACCOUNT, CASH, SAVINGS, INVESTMENT, CHECK_CARD)
        Long totalDebt,             // 부채 합 (CREDIT_CARD, LOAN) — 양수
        Long netWorth,              // totalAssets - totalDebt
        Long lastMonthNetWorth,     // 이번 달 순수입을 역산해 추정한 지난달 말 순자산
        Long changeAmount,          // netWorth - lastMonthNetWorth (= 이번 달 수입 - 이번 달 지출)
        Double changePercent,       // changeAmount / |lastMonthNetWorth| * 100 (소수 1자리). lastMonth==0이면 0.0
        List<AssetTypeSummary> byType
    ) {}

    public record AssetTypeSummary(
        AssetType assetType,
        Long totalBalance,
        Integer count
    ) {}

    public record NetWorthTrendPoint(
        Integer year,
        Integer month,
        Long netWorth
    ) {}

    /** 자산 상세 차트용 — 각 주의 월요일(weekStart) 기준 잔액. */
    public record AssetBalancePoint(
        LocalDate weekStart,
        /** 예수금 + 보유 — 옛 클라이언트용으로 뜻 그대로(외화 투자 자산은 단위가 섞인다). */
        Long balance,
        /** 원화 합계 — round(예수금 × 환율) + 보유 평가. 요약 · 순자산과 같은 식 */
        Long balanceInKrw
    ) {}

    // === Asset Transfer ===
    public record CreateTransferCommand(
        Long userRowId,
        Long fromAssetRowId,
        Long toAssetRowId,
        Long amount,
        Long fee,
        /** 이자 (대출 상환 시). amount 중 이 금액은 부채를 줄이지 않고 지출로 잡힌다. */
        Long interestAmount,
        String description,
        LocalDateTime transferDate,
        /**
         * 시스템이 만드는 이체면 그 출처 (TRADE_SETTLEMENT). 사용자가 만드는 이체는 null.
         * 값이 있으면 이후 수정·삭제가 잠긴다.
         */
        String autoSource,
        /**
         * 받는 쪽 자산 통화 금액을 <b>서버 안에서</b> 못 박을 때만 (매수 충당 이체). null 이면 서버가 정한다 —
         * 두 자산 통화가 다르면 amount(원화) ÷ 받는 쪽 환율, 같으면 저장하지 않는다(NULL).
         * 사용자 요청(컨트롤러)은 이 값을 싣지 않는다.
         */
        Long toAmount
    ) {
        /** 받는 금액을 서버가 정하는 보통 이체. */
        public CreateTransferCommand(Long userRowId, Long fromAssetRowId, Long toAssetRowId,
                                     Long amount, Long fee, Long interestAmount, String description,
                                     LocalDateTime transferDate, String autoSource) {
            this(userRowId, fromAssetRowId, toAssetRowId, amount, fee, interestAmount, description,
                transferDate, autoSource, null);
        }
    }

    public record TransferInfo(
        Long rowId,
        Long userRowId,
        Long fromAssetRowId,
        String fromAssetName,
        Long toAssetRowId,
        String toAssetName,
        Long amount,
        Long fee,
        /** 이자 (대출 상환 시). */
        Long interestAmount,
        /** 원금 = amount − interestAmount. 입금 자산(대출)에 실제로 반영된 금액. */
        Long principalAmount,
        String description,
        /**
         * 시스템이 만든 이체의 출처 (TRADE_SETTLEMENT). null 이면 사용자가 직접 만든 이체.
         * 값이 있으면 수정·삭제가 잠긴다 — 화면이 버튼을 감출 수 있게 내려 준다.
         */
        String autoSource,
        LocalDateTime transferDate,
        LocalDateTime createAt,
        /**
         * 받는 쪽 자산 통화로 받은 금액. null 이면 보낸 금액과 같다(같은 통화 · 기존 이체).
         * 두 자산 통화가 다를 때만 값이 있다 — 예: 원화 1,300,000 → USD 계좌(1,300) 는 1,000.
         */
        Long toAmount,
        /** 사용자가 지금 이 이체를 직접 지울 수 있는가 — {@code AssetTransfer#isDeletableByUser} (삭제 API 거부 조건과 같은 함수). */
        boolean deletable
    ) {
        public static TransferInfo from(AssetTransfer transfer) {
            return new TransferInfo(
                transfer.getRowId(),
                transfer.getUser().getRowId(),
                transfer.getFromAsset().getRowId(),
                transfer.getFromAsset().getAssetName(),
                transfer.getToAsset().getRowId(),
                transfer.getToAsset().getAssetName(),
                transfer.getAmount(),
                transfer.getFee(),
                transfer.getInterestAmount(),
                transfer.principalAmount(),
                transfer.getDescription(),
                transfer.getAutoSource(),
                transfer.getTransferDate(),
                transfer.getCreateAt(),
                transfer.getToAmount(),
                transfer.isDeletableByUser()
            );
        }
    }
}
