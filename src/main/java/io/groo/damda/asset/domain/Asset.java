package io.groo.damda.asset.domain;

import io.groo.core.type.YNType;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.card.domain.CardCatalog;
import io.groo.damda.common.domain.AuditingFieldsWithIp;
import io.groo.damda.user.domain.User;
import java.math.BigDecimal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "asset")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Asset extends AuditingFieldsWithIp {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_row_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_catalog_row_id")
    private CardCatalog cardCatalog;

    @Column(name = "asset_name", nullable = false, length = 100)
    private String assetName;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 30)
    private AssetType assetType;

    @Column(name = "initial_balance", nullable = false)
    private Long initialBalance;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    /**
     * 원화 환산율 (통화 1단위당 원화). KRW 는 1.
     *
     * <p>{@code currency} 만 있고 환산율이 없으면 USD 1,000 잔고가 순자산에 1,000원으로 더해진다.
     * 합계·순자산은 {@code round(예수금 × exchangeRate) + 보유 평가(원화)} 로 환산한다 —
     * 예수금(CASH)만 자산 통화이고 보유 평가(HOLDING)는 원화다. 보유 평가에는 환율을 곱하지 않는다.
     *
     * <p>수동 입력이다 — 토스 환율 API 는 구독(SECURITIES) 게이트 대상이라
     * 미구독자가 외화통장을 못 쓰게 된다. 구독자에게 자동으로 채워 주는 건 이 필드 위에 얹으면 된다.
     */
    @Column(name = "exchange_rate", nullable = false, precision = 18, scale = 6)
    private BigDecimal exchangeRate;

    @Column(name = "color", length = 20)
    private String color;

    @Column(name = "institution", length = 100)
    private String institution;

    @Column(name = "memo", length = 500)
    private String memo;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "is_included_in_total", nullable = false, length = 1)
    private YNType isIncludedInTotal;

    /**
     * 이 자산의 <b>금액만</b> 가린다.
     *
     * <p>화면 카드 가리기({@code users.hide_cards})와 <b>별개 축</b>이다. 그쪽은 "자산
     * 화면의 계좌 카드를 통째로 가린다" 는 사용자 설정이고, 이건 "이 자산은 늘 가린다" 는
     * 자산의 속성이다. 둘은 <b>합집합</b>으로 판정한다 — 하나라도 켜져 있으면 가려진다.
     *
     * <p><b>합계는 이 값을 보지 않는다.</b> 그룹 합계·구성·순자산·추이는 이 표식과 무관하고,
     * 역산이 걱정되면 화면 카드 가리기로 합계 카드를 함께 켠다(사용자 결정 2026-09-17).
     * 그래서 이 컬럼은 어떤 집계 쿼리에도 들어가지 않는다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "is_amount_hidden", nullable = false, length = 1)
    private YNType isAmountHidden;

    /**
     * 이 자산의 금액을 가릴지 정한다.
     *
     * <p>{@link #createAsset}·{@link #updateAsset} 의 인자로 받지 않는 이유 — 그 둘은
     * 이미 인자가 열다섯 개다. 하나를 더 끼우면 <b>기존 호출부 마흔 곳의 인자 순서가
     * 밀린다.</b> 순서로 맞추는 자리에 칸을 더하는 것은 그 자체가 위험이라, 뜻이 뚜렷한
     * 이 칸은 자기 이름으로 받는다.
     *
     * <p>{@code null} 은 "안 바꾼다" 다 — NOT NULL 칸이라 지운다는 뜻이 성립하지 않는다.
     */
    public void updateAmountHidden(YNType isAmountHidden) {
        this.isAmountHidden = isAmountHidden != null ? isAmountHidden : this.isAmountHidden;
    }

    @Column(name = "credit_limit")
    private Long creditLimit;

    @Column(name = "payment_day")
    private Integer paymentDay;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_asset_row_id")
    private Asset paymentAsset;

    /**
     * 쓸 수 있는 결제·연결 계좌 — 없거나 <b>지운 계좌</b>면 null.
     *
     * <p>계좌를 지워도 카드가 그 계좌를 계속 가리키던 때가 있다(QA 30 6 — 삭제가 참조를 안
     * 끊었다). 그 카드는 [지금 결제]·자동 결제·카드 수정이 전부 "자산을 찾을 수 없어요" 404 로
     * 막혔다. 지운 계좌는 연결이 없는 것과 같다 — 이체 없이 사용액만 정리한다.
     */
    public Asset getUsablePaymentAsset() {
        return paymentAsset != null && paymentAsset.getIsDeleted() != YNType.Y ? paymentAsset : null;
    }

    /** 결제·연결 계좌를 끊는다 — 그 계좌를 지울 때 부른다. */
    public void detachPaymentAsset() {
        this.paymentAsset = null;
    }

    // 증권사 시세 연동 (INVESTMENT 자산 전용) — 종목코드 + 보유수량을 등록하면
    // 현재가 × 수량으로 평가액을 실시간 계산한다. 어느 증권사 시세로 계산할지는 사용자가 고른
    // 기본 소스(user_securities_credential.is_primary)가 정한다.
    // 프로(SECURITIES) + 증권사 1곳 이상 연결 시에만 설정 가능. 미연결이면 NULL → 수동 입력 유지.
    //
    // symbol 은 증권사 표기가 아니라 stock_master 기준이다 — 증권사마다 같은 종목을 다르게
    // 부르므로, 증권사 표기를 저장하면 사용자가 기본 소스를 바꾸는 순간 연동이 조용히 풀린다.
    // market_code 와 짝으로 stock_master 를 찾고, 브로커별 호출 심볼은 조회 시점에 변환한다.
    @Column(name = "market_code", length = 10)
    private String marketCode;

    @Column(name = "symbol", length = 30)
    private String symbol;

    @Column(name = "quantity")
    private Long quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "is_deleted", nullable = false, length = 1)
    private YNType isDeleted;

    public static Asset createAsset(User user, String assetName, AssetType assetType, Long balance,
                                     String currency, BigDecimal exchangeRate, String color, String institution,
                                     String memo, Integer sortOrder, YNType isIncludedInTotal,
                                     CardCatalog cardCatalog, Long creditLimit, Integer paymentDay,
                                     Asset paymentAsset) {
        Asset asset = new Asset();
        asset.user = user;
        asset.cardCatalog = cardCatalog;
        asset.assetName = assetName;
        asset.assetType = assetType;
        // 잔액 캐시는 두지 않는다 — 금액은 asset_balance_history 가 진실이고,
        // 캐시를 두면 낡은 값으로 판단하게 된다. 초기 잔액은 INIT 앵커로 남는다.
        asset.initialBalance = balance;
        asset.currency = currency;
        asset.exchangeRate = normalizeRate(exchangeRate, currency);
        asset.color = color;
        asset.institution = institution;
        asset.memo = memo;
        asset.sortOrder = sortOrder;
        asset.isIncludedInTotal = isIncludedInTotal != null ? isIncludedInTotal : YNType.Y;
        // 새 자산은 가리지 않는다 — 만들자마자 금액이 안 보이면 잘못 만든 줄 안다.
        // 켠 채로 만들고 싶으면 서비스가 이어서 `updateAmountHidden` 을 부른다.
        asset.isAmountHidden = YNType.N;
        asset.creditLimit = creditLimit;
        asset.paymentDay = paymentDay;
        asset.paymentAsset = paymentAsset;
        asset.isDeleted = YNType.N;
        return asset;
    }

    // 잔액은 여기서 받지 않는다 — asset_balance_history 가 단독 관리하고 조회 때 집계한다.
    // 잔액 직접 수정은 AssetBalanceHistoryService.recordManual → recompute 경로로만 반영.
    public void updateAsset(String assetName, AssetType assetType, String currency,
                            BigDecimal exchangeRate, String color, String institution, String memo,
                            YNType isIncludedInTotal, CardCatalog cardCatalog,
                            Long creditLimit, Integer paymentDay, Asset paymentAsset) {
        this.assetName = assetName;
        this.assetType = assetType;
        this.currency = currency;
        this.exchangeRate = normalizeRate(exchangeRate, currency);
        this.color = color;
        this.institution = institution;
        this.memo = memo;
        // NOT NULL 칸의 마지막 가드 — 병합은 서비스가 끝냈고(QA #96) 명시적 null 은 DTO 가 끊는다.
        this.isIncludedInTotal = isIncludedInTotal != null ? isIncludedInTotal : this.isIncludedInTotal;
        this.cardCatalog = cardCatalog;
        // 널 허용 칸은 받은 값을 그대로 쓴다 — null 이면 "지운다" 는 뜻이다. 종전엔 여기서
        // null 을 무시해, 한도·결제일·결제 계좌를 <b>지울 방법이 아예 없었다</b>.
        this.creditLimit = creditLimit;
        this.paymentDay = paymentDay;
        this.paymentAsset = paymentAsset;
    }

    /**
     * 환산율 정규화 — 원화이거나 값이 없으면 1. 0 이하는 순자산을 0·음수로 만들어 버리므로 1 로 막는다.
     */
    private static BigDecimal normalizeRate(BigDecimal rate, String currency) {
        if (currency == null || "KRW".equalsIgnoreCase(currency)) {
            return BigDecimal.ONE;
        }
        return (rate == null || rate.signum() <= 0) ? BigDecimal.ONE : rate;
    }

    /** 외화 자산인가 — 통화가 원화가 아니다. */
    public boolean isForeignCurrency() {
        return currency != null && !"KRW".equalsIgnoreCase(currency);
    }

    /**
     * 원화 환산 합계 — 합계·순자산·추이는 모두 이 값을 쓴다.
     *
     * <p>{@code round(cash × 환산율) + holding}. 예수금(CASH)만 자산 통화이고, 보유 평가(HOLDING)는
     * 원화로 적혀 있어(연동 평가 = 시세 × 종목 통화 환율, 수동 평가 = 원 단위 입력) 환율을 곱하지 않는다.
     * 총잔액에 환율을 곱하면 보유 평가에 환율이 이중으로 곱해진다.
     *
     * <p>USD 예수금 100 · 환산율 1,300 · 보유 평가 10,000,000원 → 10,130,000원. 보유 없는 외화
     * 예수금 100 → 130,000원. 원화 자산은 환산율이 1이라 cash + holding 그대로다.
     */
    public long balanceInKrw(long cash, long holding) {
        return toKrw(cash) + holding;
    }

    /** 환산이 필요 없는가 — 환율이 없거나 0 이하(정규화 전 값 방어)거나 정확히 1. */
    private boolean isIdentityRate() {
        return exchangeRate == null || exchangeRate.signum() <= 0
            || BigDecimal.ONE.compareTo(exchangeRate) == 0;
    }

    /**
     * 자산 통화 금액 → 원화. {@code round(units × 환산율, HALF_UP)}, 원 단위 정수.
     * 원화 자산 · 환율 없음은 그대로.
     */
    public long toKrw(long units) {
        if (isIdentityRate()) {
            return units;
        }
        return exchangeRate.multiply(BigDecimal.valueOf(units))
            .setScale(0, java.math.RoundingMode.HALF_UP)
            .longValueExact();
    }

    /**
     * 원화 금액 → 이 자산 통화 금액(정수 1단위). {@code round(krw ÷ 환산율, HALF_UP)}.
     *
     * <p>매매 대금 · 수입지출 금액 · 이체 금액은 원화로 들어온다(웹 · 앱 칸이 "원"). 외화 자산의
     * 예수금(CASH)은 자산 통화로 쌓이므로, 잔액 이력에 적기 전에 이 함수 하나로 나눈다 — 매매 ·
     * 수입지출 · 이체가 같은 식을 쓴다. 원화 자산 · 환율 없음(1)은 그대로라 지금 결과와 같다.
     *
     * <p>예: USD · 1,300 에서 1,300,000원 → 1,000. 1,000,000원 → 769.23 → 769.
     * 부호는 그대로 따른다(−1,300,000원 → −1,000).
     */
    public long krwToAssetCurrency(long krw) {
        if (isIdentityRate()) {
            return krw;
        }
        return BigDecimal.valueOf(krw)
            .divide(exchangeRate, 0, java.math.RoundingMode.HALF_UP)
            .longValueExact();
    }

    /**
     * 원화로 적힌 거래 금액을 이 자산 통화로 — 거래가 이미 이 자산 통화의 원 통화 금액을 들고 있으면
     * 그 값을 쓴다(이중 환산 방지).
     *
     * <p>USD 계좌의 $5 지출은 {@code originalAmount = 5 · originalCurrency = USD · amount = 6,500원}
     * 으로 온다. 원화 6,500 을 계좌 환율로 다시 나누면 거래 환율과 계좌 환율이 다를 때 $5 가 아닌
     * 값이 될 수 있어, 통화가 같으면 원 통화 금액(정수로 HALF_UP)을 그대로 쓴다. 통화가 다르면
     * (원화 결제 · EUR 결제를 USD 계좌로) 원화 금액 ÷ 계좌 환율이다.
     */
    public long expenseAmountInAssetCurrency(long krw, BigDecimal originalAmount, String originalCurrency) {
        if (!isForeignCurrency()) {
            return krw;
        }
        if (originalAmount != null && originalCurrency != null
                && originalCurrency.equalsIgnoreCase(currency)) {
            return originalAmount.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        }
        return krwToAssetCurrency(krw);
    }

    /** 두 자산의 통화가 같은가 — 비어 있으면 원화로 본다. */
    public boolean sameCurrencyAs(Asset other) {
        String a = currency == null || currency.isBlank() ? "KRW" : currency;
        String b = other == null || other.currency == null || other.currency.isBlank() ? "KRW" : other.currency;
        return a.equalsIgnoreCase(b);
    }

    /**
     * 증권 종목 1:1 연결 — 종목코드 + 보유수량. 시세 × 수량으로 평가액 실시간 계산.
     *
     * @param marketCode stock_master 기준 시장코드. 확정하지 못했으면 null —
     *                   같은 티커가 여러 시장에 걸릴 때 추측한 값을 눌러 두지 않는다.
     */
    public void linkSecurities(String marketCode, String symbol, Long quantity) {
        this.marketCode = marketCode;
        this.symbol = symbol;
        this.quantity = quantity;
    }

    /** 토스 연결 해제 — 다시 수동 입력 잔액으로 복귀. */
    public void unlinkSecurities() {
        this.quantity = null;
        this.symbol = null;
        this.marketCode = null;
    }

    /** 토스 보유종목에 연결된 자산인지. */
    public boolean isSecuritiesLinked() {
        return symbol != null && !symbol.isBlank();
    }

    public void updateSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public void deleteAsset() {
        this.isDeleted = YNType.Y;
    }
}
