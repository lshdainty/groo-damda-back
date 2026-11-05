package io.groo.damda.asset.domain;

import io.groo.core.type.YNType;
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

@Entity
@Table(name = "asset_transfer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssetTransfer extends AuditingFieldsWithIp {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_row_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_asset_row_id")
    private Asset fromAsset;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_asset_row_id")
    private Asset toAsset;

    @Column(name = "amount", nullable = false)
    private Long amount;

    /**
     * 받는 쪽 자산 통화 금액. <b>NULL 이면 보낸 금액과 같다</b>(같은 통화 · 칸이 생기기 전 이체 — 지금 해석).
     *
     * <p>이체 금액(amount) · 수수료 · 이자는 <b>보내는 계좌 통화</b>다. 두 자산 통화가 다르면 amount 를
     * 보내는 쪽 환율로 원화로 바꾼 뒤 받는 쪽 환율로 나눈 값(HALF_UP, 한 번)을 만들 때 박아 둔다 —
     * 나중에 계좌 환율을 고쳐도 지난 이체의 받은 금액이 흔들리지 않는다.
     * 원화 1,300,000 → USD(1,300) = 1,000 · USD 1,000 → 원화 = 1,300,000 · USD 1,000 → EUR(1,450) = 897.
     */
    @Column(name = "to_amount")
    private Long toAmount;

    @Column(name = "fee", nullable = false)
    private Long fee;

    /**
     * 이자 (대출 상환 시). {@code amount} 중 이 금액은 <b>부채를 줄이지 않고</b> 은행으로 나가는 비용이다.
     *
     * <p>원금은 부채가 줄어드는 자산 이동이지만 이자는 순수 지출이라 성격이 다르다.
     * 그래서 입금 자산(대출)에는 {@code amount − interestAmount} 만 반영하고,
     * 이자만큼은 별도 지출 거래로 잡아 카테고리·예산에 들어가게 한다.
     *
     * <p>{@code fee}(송금 수수료)와 컬럼을 나눈 이유 — 섞으면 나중에 이 돈이 수수료였는지
     * 이자였는지 구분할 수 없어 통계를 되돌릴 수 없다.
     */
    @Column(name = "interest_amount", nullable = false)
    private Long interestAmount;

    /** 이자로 자동 생성한 지출 거래 행 아이디 (이체 수정·삭제 시 함께 따라간다). */
    @Column(name = "interest_expense_row_id")
    private Long interestExpenseRowId;

    /**
     * 시스템이 만든 이체의 출처 (TRADE_SETTLEMENT). null 이면 사용자가 직접 만든 이체.
     *
     * <p>매수 시 예수금이 모자라 자동으로 생긴 이체는 매수의 부속물이라 금액이 매수와
     * 묶여 있다. 손으로 고치면 매수는 그대로인데 예수금만 달라져 앞뒤가 안 맞는다.
     */
    @Column(name = "auto_source", length = 30)
    private String autoSource;

    @Column(name = "description", length = 500)
    private String description;

    /** [userClock] 사용자·업무가 정한 벽시계 — 타임존 변환 금지(자정 근처 날짜가 밀린다) */
    @Column(name = "transfer_date", nullable = false)
    private LocalDateTime transferDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "is_deleted", nullable = false, length = 1)
    private YNType isDeleted;

    public static AssetTransfer createTransfer(User user, Asset fromAsset, Asset toAsset,
                                                Long amount, Long fee, Long interestAmount,
                                                String description, LocalDateTime transferDate) {
        AssetTransfer transfer = new AssetTransfer();
        transfer.user = user;
        transfer.fromAsset = fromAsset;
        transfer.toAsset = toAsset;
        transfer.amount = amount;
        transfer.fee = fee != null ? fee : 0L;
        transfer.interestAmount = interestAmount != null ? interestAmount : 0L;
        transfer.description = description;
        transfer.transferDate = transferDate;
        transfer.isDeleted = YNType.N;
        return transfer;
    }

    /** 이자가 있는가 — 대출 상환에서만 0 보다 크다. */
    public boolean hasInterest() {
        return interestAmount != null && interestAmount > 0L;
    }

    /**
     * 입금 자산에 반영할 금액 — 이자는 부채를 줄이지 않으므로 뺀다.
     *
     * <p>50만원 상환(이자 15만)이면 대출 잔액은 35만원만 줄어든다.
     */
    public long principalAmount() {
        return amount - (interestAmount != null ? interestAmount : 0L);
    }

    /**
     * 받는 금액을 정한다 — 두 자산 통화가 다르면 {@code round(amount × 보내는 쪽 환율 ÷ 받는 쪽 환율)},
     * 같으면 NULL.
     *
     * @param override 서버 안에서 받는 금액을 못 박을 때(매수 충당 · 카드 결제). null 이면 위 규칙
     */
    public void resolveToAmount(Long override) {
        if (fromAsset == null || toAsset == null || fromAsset.sameCurrencyAs(toAsset)) {
            this.toAmount = null;
            return;
        }
        this.toAmount = override != null ? override : fromAsset.convertTo(amount, toAsset);
    }

    /** 출금 자산 잔액 증감 — {@code −(amount + fee)}. 보내는 계좌 통화 그대로라 환산하지 않는다. */
    public long fromBalanceDelta() {
        return -(amount + (fee != null ? fee : 0L));
    }

    /**
     * 입금 자산 잔액 증감(입금 자산 통화) — 이자를 뺀 원금만.
     *
     * <p>같은 통화(to_amount NULL)는 {@code amount − 이자}(지금 해석). 통화가 다르면 이자가 없을 때
     * to_amount 그대로, 이자가 있으면 {@code round((amount − 이자) × 보내는 쪽 환율 ÷ 받는 쪽 환율)} —
     * 원금을 한 번에 환산해 반올림이 두 번 끼지 않는다.
     */
    public long toBalanceDelta() {
        long interest = interestAmount != null ? interestAmount : 0L;
        if (toAmount == null) {
            return amount - interest;
        }
        if (interest == 0L) {
            return toAmount;
        }
        return fromAsset.convertTo(amount - interest, toAsset);
    }

    /**
     * 돈 칸이 그대로인가 — 두 계좌 · 금액 · 수수료 · 이자가 모두 같으면 수정해도 받은 금액과 잔액
     * 기록을 다시 계산하지 않는다(메모 · 날짜만 고친 수정이 그 사이 바뀐 환율로 잔액을 흔들지 않게).
     */
    public boolean sameMoneyAs(Asset newFrom, Asset newTo, Long newAmount, Long newFee, Long newInterest) {
        return sameAsset(fromAsset, newFrom) && sameAsset(toAsset, newTo)
            && java.util.Objects.equals(amount, newAmount)
            && java.util.Objects.equals(fee != null ? fee : 0L, newFee != null ? newFee : 0L)
            && java.util.Objects.equals(interestAmount != null ? interestAmount : 0L,
                newInterest != null ? newInterest : 0L);
    }

    private static boolean sameAsset(Asset a, Asset b) {
        if (a == b) {
            return true;
        }
        return a != null && b != null && a.getRowId() != null && a.getRowId().equals(b.getRowId());
    }

    /** 자동 생성한 이자 지출 거래를 연결한다. */
    public void linkInterestExpense(Long expenseRowId) {
        this.interestExpenseRowId = expenseRowId;
    }

    /** 시스템이 만든 이체로 표시한다 — 이후 수정·삭제가 막힌다. */
    public void markAutoGenerated(String source) {
        this.autoSource = source;
    }

    /** 사용자가 고칠 수 없는 이체인가. */
    public boolean isAutoGenerated() {
        return autoSource != null;
    }

    /**
     * 사용자가 고칠 수 있는 값들을 갈아 끼운다.
     *
     * <p>부수효과(이자 지출·잔액 이력)는 서비스가 되돌렸다가 다시 만든다 — 여기서는
     * 필드만 바꾼다. rowId 는 그대로라 이 이체를 가리키던 참조가 끊기지 않는다.
     */
    public void updateTransfer(Asset fromAsset, Asset toAsset, Long amount, Long fee,
                               Long interestAmount, String description, LocalDateTime transferDate) {
        this.fromAsset = fromAsset;
        this.toAsset = toAsset;
        this.amount = amount;
        this.fee = fee != null ? fee : 0L;
        this.interestAmount = interestAmount != null ? interestAmount : 0L;
        this.description = description;
        this.transferDate = transferDate;
        this.interestExpenseRowId = null;  // 서비스가 다시 만들어 연결한다
    }

    public void deleteTransfer() {
        this.isDeleted = YNType.Y;
    }
}
