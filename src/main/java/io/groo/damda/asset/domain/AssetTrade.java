package io.groo.damda.asset.domain;

import io.groo.core.type.YNType;
import io.groo.damda.asset.type.HoldingType;
import io.groo.damda.asset.type.TradeType;
import io.groo.damda.common.domain.AuditingFieldsWithIp;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 투자 자산의 매수·매도 거래.
 *
 * <p>증권계좌에서 예수금이 줄고 느는 진짜 사건은 매수·매도다. 그게 없으면 평가액 갱신을 보고
 * 예수금을 추측할 수밖에 없는데, 시세 변동·추가 매수·재등록이 전부 같은 갱신으로 들어와
 * 구분이 안 된다. 거래로 남기면 예수금이 실제 흐름대로 움직인다.
 *
 * <pre>
 *   매수  예수금 -(대금 + 수수료),  수량 +, 원가 +(대금 + 수수료)
 *   매도  예수금 +(대금 - 수수료),  수량 -, 원가 -(판 만큼의 원가)
 *         실현손익 = (대금 - 수수료) - 판 만큼의 원가
 * </pre>
 *
 * <p>원가는 이동평균이다. 매수 수수료는 취득원가에 넣고, 매도 수수료는 대금에서 뺀다.
 *
 * <p>{@code quantityDelta}·{@code costDelta} 를 따로 남기는 이유 — 이동평균은 순서에 의존해서
 * 거래를 취소할 때 "그때 원가가 얼마나 빠졌는지" 를 현재 기준으로 다시 계산하면 어긋난다.
 * 그 시점의 변동분을 박아 두면 역적용으로 정확히 되돌아간다.
 *
 * <p>보유({@link AssetHolding})와는 {@code holdingKey} 로 묶는다. 보유 목록은 편집할 때마다
 * 통째로 재생성돼서(soft delete 후 재삽입) row_id 로는 묶을 수 없다.
 */
@Entity
@Table(name = "asset_trade")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssetTrade extends AuditingFieldsWithIp {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_row_id", nullable = false)
    private io.groo.damda.user.domain.User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_row_id", nullable = false)
    private Asset asset;

    /**
     * 결제 계좌 — 지정하면 증권계좌 예수금 대신 이 계좌에서 돈이 오간다.
     * 예수금을 따로 관리하지 않는 사용자를 위한 길이다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_asset_row_id")
    private Asset settlementAsset;

    @Enumerated(EnumType.STRING)
    @Column(name = "trade_type", nullable = false, length = 10)
    private TradeType tradeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "holding_type", nullable = false, length = 10)
    private HoldingType holdingType;

    /** 종목 식별자 — 연동은 토스 종목코드, 미연동은 항목명. */
    @Column(name = "holding_key", nullable = false, length = 100)
    private String holdingKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "linked", nullable = false, length = 1)
    private YNType linked;

    /** 거래 수량 — 항상 양수. 방향은 tradeType 이 정한다. */
    @Column(name = "quantity", nullable = false, precision = 28, scale = 8)
    private BigDecimal quantity;

    /** 거래대금 (원화, 수수료 제외). */
    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "fee", nullable = false)
    private Long fee;

    @Column(name = "quantity_delta", nullable = false, precision = 28, scale = 8)
    private BigDecimal quantityDelta;

    @Column(name = "cost_delta", nullable = false)
    private Long costDelta;

    /** 실현손익 (매도 전용). 이익 양수 / 손실 음수. */
    @Column(name = "realized_pl")
    private Long realizedPl;

    /** 실현손익을 기록한 거래(expense) 행 아이디 — 매도 취소 시 함께 지운다. */
    @Column(name = "realized_expense_row_id")
    private Long realizedExpenseRowId;

    @Column(name = "trade_date", nullable = false)
    private LocalDateTime tradeDate;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "is_deleted", nullable = false, length = 1)
    private YNType isDeleted;

    public static AssetTrade create(io.groo.damda.user.domain.User user, Asset asset,
                                    Asset settlementAsset,
                                    TradeType tradeType, HoldingType holdingType, String holdingKey,
                                    YNType linked, BigDecimal quantity, Long amount, Long fee,
                                    BigDecimal quantityDelta, Long costDelta,
                                    LocalDateTime tradeDate, String description) {
        AssetTrade t = new AssetTrade();
        t.user = user;
        t.asset = asset;
        t.settlementAsset = settlementAsset;
        t.tradeType = tradeType;
        t.holdingType = holdingType != null ? holdingType : HoldingType.STOCK;
        t.holdingKey = holdingKey;
        t.linked = linked != null ? linked : YNType.N;
        t.quantity = quantity;
        t.amount = amount != null ? amount : 0L;
        t.fee = fee != null ? fee : 0L;
        t.quantityDelta = quantityDelta;
        t.costDelta = costDelta;
        t.tradeDate = tradeDate;
        t.description = description;
        t.isDeleted = YNType.N;
        return t;
    }

    /**
     * 재계산 결과로 변동분과 실현손익을 갈아끼운다.
     *
     * <p>이동평균은 순서에 의존해서, 앞선 거래가 지워지거나 과거 날짜 거래가 끼어들면
     * 그 뒤 거래들의 원가·손익이 전부 달라진다. 그때 처음부터 다시 쌓아 이 메서드로 덮는다.
     */
    public void replaceDeltas(BigDecimal quantityDelta, long costDelta, Long realizedPl) {
        this.quantityDelta = quantityDelta;
        this.costDelta = costDelta;
        this.realizedPl = realizedPl;
    }

    public void recordRealized(long realizedPl, Long realizedExpenseRowId) {
        this.realizedPl = realizedPl;
        this.realizedExpenseRowId = realizedExpenseRowId;
    }

    public void deleteTrade() {
        this.isDeleted = YNType.Y;
    }

    /**
     * 예수금 변동액 — 매수는 대금과 수수료가 함께 빠지고, 매도는 수수료를 뗀 나머지가 들어온다.
     * 기초 보유(OPENING)는 앱을 쓰기 전 일이라 돈이 오가지 않는다.
     */
    /** 돈이 실제로 오가는 자산 — 결제 계좌를 골랐으면 거기, 아니면 증권계좌 예수금. */
    /**
     * 예수금 충당 이체 — 결제 계좌 매수에서 부족분만큼 자동 생성한다.
     * 앱이 만든 것이라 거래를 취소하면 함께 지운다.
     */
    @Column(name = "settlement_transfer_row_id")
    private Long settlementTransferRowId;

    /**
     * 보유 행 아이디 — 종목을 이름이 아니라 id 로 가리킨다.
     *
     * <p>{@code holding_key} 는 미연동 종목에서 항목명이라, 이름을 고치면 거래와 보유가
     * 끊겼다. 보유가 제자리 수정되도록 바뀌면서 row_id 가 안정돼 이제 id 로 묶을 수 있다.
     * 기존 거래는 null 이라 종전대로 이름으로 찾는다.
     */
    @Column(name = "holding_row_id")
    private Long holdingRowId;

    public void linkHolding(Long holdingRowId) {
        this.holdingRowId = holdingRowId;
    }

    public void linkSettlementTransfer(Long transferRowId) {
        this.settlementTransferRowId = transferRowId;
    }

    /**
     * 현금이 오가는 자산 — <b>언제나 증권계좌 예수금</b>이다.
     *
     * <p>증권계좌는 예수금이 있어야 주식을 산다. 결제 계좌를 골랐어도 통장에서 주식이
     * 바로 사지는 게 아니라, 통장 → 예수금 이체가 먼저 일어나고 매수는 예수금에서 나간다.
     */
    public Asset cashAsset() {
        return asset;
    }

    /** 이 거래가 예수금(자산 통화)에 준 증감 — {@link #cashDeltaOf} 와 같은 식. */
    public long cashDelta() {
        return cashDeltaOf(asset, tradeType,
            amount != null ? amount : 0L, fee != null ? fee : 0L);
    }

    /**
     * 매매가 예수금(CASH, <b>자산 통화</b>)에 주는 증감.
     *
     * <p>거래대금 · 수수료는 원화다(웹 · 앱 칸이 "원", SQL 정의 원화). 외화 계좌의 예수금은 자산
     * 통화로 쌓이므로 <b>합친 뒤 한 번만</b> 환율로 나눈다 — 매수 {@code −round((amount + fee) ÷ 환율)},
     * 매도 {@code +round((amount − fee) ÷ 환율)}, HALF_UP 정수 1단위. 원화 자산(환율 1)은 지금 식 그대로다.
     * 원가(totalCost) · 실현손익은 원화 그대로 — 여기서 나누는 건 예수금 한 자리뿐이다.
     */
    public static long cashDeltaOf(Asset asset, TradeType type, long amount, long fee) {
        long krw = switch (type) {
            case BUY -> -(amount + fee);
            case SELL -> amount - fee;
            case OPENING -> 0L;
        };
        if (krw == 0L || asset == null) {
            return krw;
        }
        return asset.krwToAssetCurrency(krw);
    }
}
