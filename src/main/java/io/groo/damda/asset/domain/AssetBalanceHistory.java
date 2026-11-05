package io.groo.damda.asset.domain;

import io.groo.core.type.YNType;
import io.groo.damda.asset.type.BalanceChannel;
import io.groo.damda.asset.type.BalanceSourceType;
import io.groo.damda.common.domain.AuditingFieldsWithIp;
import io.groo.damda.user.domain.User;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 자산 잔액 변동 이력 (단일 진실 공급원).
 *
 * <p>잔액이 바뀌는 모든 사건을 datetime 스냅샷 row 로 적재한다. "기준시각의 자산 잔액" 은
 * {@code (기준시각 이하 최신 absolute 앵커).amount + 그 이후 flow row 들의 amount 합} 으로 계산하며,
 * 현재 총자산·과거 시점·추이 그래프가 모두 이 동일 메커니즘을 쓰므로 항상 일치한다.
 *
 * @see io.groo.damda.asset.type.BalanceSourceType
 */
@Entity
@Table(name = "asset_balance_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssetBalanceHistory extends AuditingFieldsWithIp {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_row_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_row_id")
    private Asset asset;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private BalanceSourceType sourceType;

    /**
     * 이 행이 속한 채널 — 예수금(CASH) / 보유 평가금액(HOLDING).
     * 채널이 다르면 서로의 앵커에 잡아먹히지 않는다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    private BalanceChannel channel;

    /** 출처 엔티티의 row_id (EXPENSE=expense.row_id, TRANSFER=asset_transfer.row_id, INIT/MANUAL/VALUATION=asset.row_id). */
    @Column(name = "source_row_id")
    private Long sourceRowId;

    /** flow = 부호 있는 변동액 / absolute(INIT·MANUAL·VALUATION) = 그 시점 절대 잔액. */
    @Column(name = "amount", nullable = false)
    private Long amount;

    /** [userClock] 사용자·업무가 정한 벽시계 — 타임존 변환 금지(자정 근처 날짜가 밀린다) */
    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "is_deleted", nullable = false, length = 1)
    private YNType isDeleted;

    /** 예수금 채널 — 이체·거래·수동 조정처럼 현금이 오간 기록. */
    public static AssetBalanceHistory of(User user, Asset asset, BalanceSourceType sourceType,
                                         Long sourceRowId, Long amount, LocalDateTime effectiveAt) {
        return of(user, asset, sourceType, BalanceChannel.CASH, sourceRowId, amount, effectiveAt);
    }

    public static AssetBalanceHistory of(User user, Asset asset, BalanceSourceType sourceType,
                                         BalanceChannel channel, Long sourceRowId, Long amount,
                                         LocalDateTime effectiveAt) {
        AssetBalanceHistory h = new AssetBalanceHistory();
        h.user = user;
        h.asset = asset;
        h.sourceType = sourceType;
        h.channel = channel != null ? channel : BalanceChannel.CASH;
        h.sourceRowId = sourceRowId;
        h.amount = amount;
        h.effectiveAt = effectiveAt;
        h.isDeleted = YNType.N;
        return h;
    }

    /**
     * 이 flow 가 걸린 자산을 옮긴다 — 체크카드 연결 계좌를 지정·변경했을 때만 쓴다.
     * 과거 지출을 새 계좌 앞으로 다시 걸어야 어느 쪽 잔액도 어긋나지 않는다.
     */
    public void moveTo(Asset asset) {
        this.asset = asset;
    }

    /**
     * 옮기면서 금액도 새 자산 통화로 다시 적는다 — 체크카드 연결 계좌가 다른 통화로 바뀔 때.
     * 원화 13,000 지출을 USD(1,300) 통장으로 옮기면 −13,000 이 아니라 −10 이어야 한다.
     */
    public void moveTo(Asset asset, long amount) {
        this.asset = asset;
        this.amount = amount;
    }

    /**
     * 시각만 옮긴다 — 돈 칸(금액 · 통화 · 계좌)이 그대로인 수정. 금액은 만들 때 값 그대로 남아
     * 그 사이 계좌 환율을 고쳤어도 지난 기록이 흔들리지 않는다.
     */
    public void retime(LocalDateTime effectiveAt) {
        this.effectiveAt = effectiveAt;
    }

    public void softDelete() {
        this.isDeleted = YNType.Y;
    }

    /** 절대 앵커(INIT/MANUAL/VALUATION)면 true. */
    public boolean isAbsolute() {
        return sourceType.isAbsolute();
    }

    /** 보유 평가금액 채널인가. */
    public boolean isHolding() {
        return channel == BalanceChannel.HOLDING;
    }
}
