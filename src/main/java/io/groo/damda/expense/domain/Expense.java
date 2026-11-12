package io.groo.damda.expense.domain;

import io.groo.damda.common.validation.InstallmentLimits;

import io.groo.damda.common.exception.DeskErrorCode;

import io.groo.core.exception.InvalidValueException;

import io.groo.core.type.YNType;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.calendar.domain.CalendarEvent;
import io.groo.damda.common.domain.AuditingFieldsWithIp;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.todo.domain.Todo;
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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "expense")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Expense extends AuditingFieldsWithIp {
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
    @JoinColumn(name = "category_row_id")
    private ExpenseCategory category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_row_id")
    private Asset asset;

    @Enumerated(EnumType.STRING)
    @Column(name = "expense_type", nullable = false, length = 20)
    private ExpenseType expenseType;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "description", length = 500)
    private String description;

    /** [userClock] 사용자·업무가 정한 벽시계 — 타임존 변환 금지(자정 근처 날짜가 밀린다) */
    @Column(name = "expense_date", nullable = false)
    private LocalDateTime expenseDate;

    @Column(name = "merchant", length = 100)
    private String merchant;

    @Column(name = "payment_method", length = 30)
    private String paymentMethod;

    /**
     * 할부 개월 (null·1 = 일시불). 신용카드 결제에만 의미가 있다.
     *
     * <p>카드 청구는 결제일 기준 전월 사용분을 한 번에 잡는데, 할부는 그 금액이 N개월에 나뉘어
     * 청구된다. 이 값이 있으면 청구 회차 계산이 거래 금액을 N등분해 회차별로 잡는다.
     * 통계·예산은 거래 시점에 전액을 인식한다(가계부 관점의 지출 시점은 결제한 날이다).
     */
    @Column(name = "installment_months")
    private Integer installmentMonths;

    /**
     * 할부 중도 전액 상환일 (null = 정상 분할).
     *
     * <p>이 날짜가 속한 청구 회차(월)에 <b>남은 원금</b>이 한 번에 청구되고 이후 회차는
     * 0원이 된다. 회차는 순수 날짜 계산이라 이 값 없이는 "상환됨" 을 표현할 방법이 없었다 —
     * 할부 개월을 고치는 우회는 과거 회차 금액까지 재계산해 이미 낸 청구와 어긋난다.
     */
    @Column(name = "installment_payoff_date")
    private LocalDate installmentPayoffDate;

    /**
     * 환불 처리 시각 — 있으면 <b>환불된 거래</b>다. 없으면 보통 거래.
     *
     * <p><b>삭제 대신 환불 마크</b>다(사용자 결정 2026-09-18). 돈과 집계는 삭제와 똑같이
     * 다룬다 — 원거래 잔액 흐름을 지워 금액이 그 자산으로 돌아가고, 모든 합계·청구·실적에서
     * 빠진다. 삭제와 다른 점은 내역에 남고 되돌릴 수 있다는 것뿐이다.
     *
     * <p>종전 모델(수입 행 + 원거래 연결)에서는 환불이 카드에 {@code +금액} 흐름을 남기고
     * 동시에 <b>환불 날짜</b> 회차의 청구에서 또 빠져, 두 날짜가 다른 회차면 한 번 산 것을
     * 두 번 깎아 유령 빚이 남았다. 마크 모델에는 "환불 날짜 회차" 라는 개념이 아예 없다.
     */
    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    /**
     * 환불 마크가 만든 카드→결제계좌 환급 이체 — 환불 취소가 이걸 되돌린다.
     *
     * <p>원거래가 속한 회차에 <b>결제 완료 기록</b>이 있을 때만 생긴다(이미 낸 돈이라
     * 돌려받아야 한다). 결제 전이면 그 회차 청구가 저절로 줄어드니 이체가 없고,
     * 결제계좌를 안 걸어 둔 카드는 기록용이라 역시 없다.
     */
    @Column(name = "refund_transfer_row_id")
    private Long refundTransferRowId;

    /**
     * 이 날짜(회차 말일)까지의 카드 회차분은 <b>앱 밖에서 이미 결제된 기록용</b>이다.
     * null 이면 정상(앱이 청구·결제한다).
     *
     * <p>결제일이 지난 회차에 소급 입력한 카드 지출은 현실에서 이미 결제가 끝났다. 가계부에는
     * 정상 집계하되, 계좌에서 돈을 빼거나 뒤 회차 청구에 얹지 않는다(닫힌 회차 규칙 R2).
     * 일시불은 거래 전체, 할부는 이 날짜 이전 회차분만 기록용이다(R5).
     */
    @Column(name = "card_settled_through")
    private LocalDate cardSettledThrough;

    /**
     * 자동 생성 출처 — 시스템이 계산해 만든 거래다. null 이면 사용자가 직접 만든 것.
     *
     * <p>매도 실현손익(TRADE_REALIZED)·대출 이자(TRANSFER_INTEREST)는 원 거래에서 파생된
     * 금액이라 잔액 이력을 따로 남기지 않는다. 그런데 가계부에 그대로 보이고 수정도 됐다 —
     * 사용자가 카테고리를 달고 저장하는 순간 recordExpense 가 돌아 잔액에 flow 가 새로
     * 생겼다(카테고리 하나 달았는데 예수금이 실현손익만큼 늘어난다).
     *
     * <p>그래서 금액·자산·유형·일자는 잠그고 카테고리·메모·거래처는 연다. 전부 막으면
     * 미분류로 남을 수밖에 없어 오히려 불편하다.
     *
     * <p>paymentMethod 문자열로 판정하지 않는 이유 — 그건 사용자가 바꿀 수 있다.
     */
    @Column(name = "auto_source", length = 30)
    private String autoSource;

    /**
     * 원 통화 금액 (해외 결제 시). null 이면 원화 결제 — {@code amount} 가 곧 결제액이다.
     *
     * <p>{@code amount}(원화)만 남기면 "얼마짜리를 어떤 환율로 샀는지" 가 사라져
     * 카드사 청구 환율과 대사할 수 없다. 잔액·통계는 종전대로 {@code amount} 를 쓴다.
     */
    @Column(name = "original_amount", precision = 18, scale = 4)
    private BigDecimal originalAmount;

    /** 원 통화 (ISO 4217, 예: USD). null 이면 원화 결제. */
    @Column(name = "original_currency", length = 10)
    private String originalCurrency;

    /** 적용 환율 (원 통화 1단위당 원화). {@code amount ≈ originalAmount × exchangeRate}. */
    @Column(name = "exchange_rate", precision = 18, scale = 6)
    private BigDecimal exchangeRate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "calendar_event_row_id")
    private CalendarEvent calendarEvent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "todo_row_id")
    private Todo todo;

    /**
     * 고정 수입·지출 표식 (Y=고정) — 월급·월세처럼 매달 정해진 거래.
     *
     * <p>통계가 "전체 / 고정 제외 / 고정만" 으로 나눠 보는 재료다. 돈·잔액·카드 청구에는 아무 영향이
     * 없고 <b>집계의 분류</b>일 뿐이라, 금액을 잠그는 거래(카드 회차 결제·자동 생성)도 이 칸은 고칠 수
     * 있다. 이체는 {@code Expense} 가 아니라 대상이 아니다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "is_fixed", nullable = false, length = 1)
    private YNType isFixed = YNType.N;

    @Enumerated(EnumType.STRING)
    @Column(name = "is_deleted", nullable = false, length = 1)
    private YNType isDeleted;

    public static Expense createExpense(User user, ExpenseCategory category, Asset asset,
                                        ExpenseType expenseType, Long amount, String description,
                                        LocalDateTime expenseDate, String merchant, String paymentMethod,
                                        Integer installmentMonths,
                                        BigDecimal originalAmount, String originalCurrency,
                                        BigDecimal exchangeRate) {
        Expense expense = new Expense();
        expense.user = user;
        expense.category = category;
        expense.asset = asset;
        expense.expenseType = expenseType;
        expense.amount = amount;
        expense.description = description;
        expense.expenseDate = expenseDate;
        expense.merchant = merchant;
        expense.paymentMethod = paymentMethod;
        expense.installmentMonths = normalizeInstallment(installmentMonths);
        expense.applyForeignCurrency(originalAmount, originalCurrency, exchangeRate);
        expense.isDeleted = YNType.N;
        return expense;
    }

    /**
     * 할부 개월 정규화 — 1 이하·null 은 일시불(null)로 통일하고, {@link InstallmentLimits#MAX_MONTHS} 를 넘으면 거절한다.
     * "1개월 할부"는 일시불과 같아서 두 표기가 섞이면 청구 계산이 갈린다.
     */
    private static Integer normalizeInstallment(Integer months) {
        if (months == null || months <= 1) {
            return null;
        }
        // 상한 — 요청 DTO(@Max)를 거치지 않는 경로(미리보기 쿼리·문자 저장·교체)도 여기를 지난다(보안 점검 DAM-9).
        if (months > InstallmentLimits.MAX_MONTHS) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_INSTALLMENT_OUT_OF_RANGE);
        }
        return months;
    }

    /**
     * 환불인가 — INCOME 이면서 원거래가 지정된 건.
     *
     * <p>원거래 없이 INCOME 이면 그냥 수입이다(급여·이자 등).
     */
    /** 시스템이 만든 거래인가 — 금액·자산·유형·일자를 사용자가 못 고친다. */
    public boolean isAutoGenerated() {
        return autoSource != null;
    }

    /**
     * 사용자가 지금 이 거래를 직접 지울 수 있는가 — 거부 조건(삭제 API)과 응답의 {@code deletable} 이
     * 같은 함수를 부른다. 손으로 쓴 거래는 true, 자동 생성은 매도 실현손익(TRADE_REALIZED)이면서 그
     * 증권 자산이 이미 지워졌을 때만 true(자산 삭제는 기록을 남기므로 치울 길을 사용자에게 연다).
     * 수정·환불 잠금과는 무관하다.
     */
    public boolean isDeletableByUser() {
        if (!isAutoGenerated()) {
            return true;
        }
        return "TRADE_REALIZED".equals(autoSource) && asset != null && asset.getIsDeleted() == YNType.Y;
    }

    /** 자동 생성 표시 — 매도 실현손익·대출 이자처럼 원 거래에서 파생된 금액에만 붙인다. */
    public void markAutoGenerated(String source) {
        this.autoSource = source;
    }

    /** 사용자가 고칠 수 있는 부분만 반영 — 금액·자산·유형·일자는 원 거래가 정한다. */
    public void updateEditableFields(ExpenseCategory category, String description, String merchant) {
        this.category = category;
        this.description = description;
        this.merchant = merchant;
    }

    public boolean isRefunded() {
        return refundedAt != null;
    }

    /**
     * 집계에 셀 거래인가 — 지워지지도, 환불되지도 않은 것.
     *
     * <p>환불된 거래는 <b>삭제와 똑같이</b> 빠진다. 목록·검색·상세에는 남지만 합계·예산·
     * 통계·청구·실적·내보내기 합계에서는 없는 것으로 본다. 한 자리라도 빠뜨리면 그 화면만
     * 환불을 안 뺀 숫자를 보여 주므로, 집계 조건은 이 판정 하나를 쓴다.
     */
    /** 카드를 만들 때 적은 "이전 미결제 사용액"(D4) — 가계부 집계에서만 빠진다. */
    public static final String AUTO_SOURCE_CARD_CARRYOVER = "CARD_CARRYOVER";

    /**
     * 카드를 만들 때 적은 사용액 중 <b>결제를 기다리던 지난달 청구분</b>(2026-09-22 사용자 결정).
     *
     * <p>결제일 전에 카드를 등록하면 실제 카드사는 지난달 청구분을 다가오는 결제일에, 이번 달
     * 쓴 금액을 그다음 결제일에 뺀다. 한 건으로 두면 등록한 날의 회차에 한꺼번에 청구돼 한 달
     * 동안 통장 잔액이 실제보다 많았다. 그래서 이 몫은 <b>지난달 회차(말일)</b>에 따로 둔다 —
     * 다가오는 결제일에 그 회차로 청구된다. 나머지는 {@link #AUTO_SOURCE_CARD_CARRYOVER} 그대로다.
     *
     * <p>이월과 같은 대접을 받는다 — 가계부 집계에서 빠지고, 가계부에서 못 고친다({@link #isCardCarryover()}).
     */
    public static final String AUTO_SOURCE_CARD_CARRYOVER_DUE = "CARD_CARRYOVER_DUE";

    /** 카드 이월 거래 출처 둘 — 집계 조건이 이 목록 하나를 본다. */
    public static final List<String> CARD_CARRYOVER_SOURCES =
        List.of(AUTO_SOURCE_CARD_CARRYOVER, AUTO_SOURCE_CARD_CARRYOVER_DUE);

    /** 카드 이월 거래인가(결제 대기 청구분 포함) — 등록 전에 이미 쓴 돈이라 그 달의 지출이 아니다. */
    public boolean isCardCarryover() {
        return autoSource != null && CARD_CARRYOVER_SOURCES.contains(autoSource);
    }

    /**
     * <b>가계부</b> 집계에 넣을 거래인가 — {@link #isCountable()} 에서 카드 이월을 뺀다.
     *
     * <p>카드 쪽(청구·할부·실적·잔액)은 {@link #isCountable()} 을 쓴다. 이월 거래가 곧
     * 카드의 미결제 잔액이라 거기서 빼면 D4(잔액 = 거래 합)가 풀린다.
     */
    public boolean isLedgerCountable() {
        return isCountable() && !isCardCarryover();
    }

    public boolean isCountable() {
        return isDeleted != YNType.Y && refundedAt == null;
    }

    /** 환불로 표시한다 — 돈을 되돌리는 것은 서비스(잔액 흐름 제거)가 한다. */
    public void markRefunded(LocalDateTime at) {
        this.refundedAt = at;
    }

    /** 환불 취소 — 표식과 환급 이체 연결을 함께 지운다. */
    public void clearRefund() {
        this.refundedAt = null;
        this.refundTransferRowId = null;
    }

    /** 결제 완료 회차라 만들어진 카드→계좌 환급 이체를 걸어 둔다. */
    public void linkRefundTransfer(Long transferRowId) {
        this.refundTransferRowId = transferRowId;
    }

    /** 기록용 회차분 표식 — 서버가 저장·수정 때 회차 규칙으로 정한다. */
    public void markCardSettledThrough(LocalDate through) {
        this.cardSettledThrough = through;
    }

    /**
     * 수입 집계에 더할 금액.
     *
     * <p>마크 모델에서는 <b>수입은 수입, 지출은 지출</b>이다. 환불이 수입 행을 만들지
     * 않으므로 상계 분기가 필요 없다 — 환불된 거래는 애초에 집계에 들어오지 않는다
     * ({@link #isCountable()}).
     */
    public long incomeContribution() {
        return expenseType == ExpenseType.INCOME ? amount : 0L;
    }

    /** 지출 집계에 더할 금액. */
    public long expenseContribution() {
        return expenseType == ExpenseType.EXPENSE ? amount : 0L;
    }

    /** 고정 거래인가. */
    public boolean isFixedTx() {
        return isFixed == YNType.Y;
    }

    /** 고정 표식을 바꾼다 — null 은 "변동" 으로 본다. */
    public void markFixed(Boolean fixed) {
        this.isFixed = Boolean.TRUE.equals(fixed) ? YNType.Y : YNType.N;
    }

    /** 수입 집계 중 <b>고정</b>인 몫 — {@link #incomeContribution()} 의 부분집합. */
    public long fixedIncomeContribution() {
        return isFixedTx() ? incomeContribution() : 0L;
    }

    /** 지출 집계 중 <b>고정</b>인 몫 — {@link #expenseContribution()} 의 부분집합. */
    public long fixedExpenseContribution() {
        return isFixedTx() ? expenseContribution() : 0L;
    }

    /**
     * 외화 결제 정보 — 셋이 함께 있어야 의미가 있다. 통화가 없거나 원화면 전부 비운다
     * (반쪽만 남으면 "$? 를 환율 1,400 에" 같은 해석 불가한 기록이 생긴다).
     */
    private void applyForeignCurrency(BigDecimal originalAmount, String originalCurrency,
                                      BigDecimal exchangeRate) {
        boolean foreign = originalCurrency != null
            && !originalCurrency.isBlank()
            && !"KRW".equalsIgnoreCase(originalCurrency)
            && originalAmount != null
            && originalAmount.signum() > 0;
        this.originalAmount = foreign ? originalAmount : null;
        this.originalCurrency = foreign ? originalCurrency.toUpperCase() : null;
        this.exchangeRate = foreign ? exchangeRate : null;
    }

    /** 해외 결제인가. */
    public boolean isForeignCurrency() {
        return originalCurrency != null;
    }

    /** 일시불이 아닌가. */
    public boolean isInstallment() {
        return installmentMonths != null && installmentMonths > 1;
    }

    /**
     * 할부 n회차(1-base)에 청구될 금액.
     *
     * <p>나누어떨어지지 않는 금액은 <b>첫 회차에 나머지를 몰아</b> 합이 원금과 정확히 맞게 한다
     * (국내 카드사 관행). 예: 1,000,000원 3개월 → 333,334 / 333,333 / 333,333.
     *
     * @param seq 1..installmentMonths. 범위를 벗어나면 0.
     */
    public long installmentAmountAt(int seq) {
        return installmentAmountAt(seq, amount);
    }

    /**
     * 원금을 <b>가정해서</b> 센 회차 금액 — 환급 미리보기가 "감액하면 이 회차가 얼마가
     * 되나" 를 같은 산식으로 묻는 자리다(설계 13-1).
     *
     * <p>산식을 복사해 가면 미리보기와 실제 환급액이 갈린다. 그래서 원금만 인자로 열고
     * 나머지(중도 상환 처리·나머지 몰기)는 한 곳에 둔다.
     */
    public long installmentAmountAt(int seq, long principal) {
        if (!isInstallment()) {
            return seq == 1 ? principal : 0L;
        }
        if (seq < 1 || seq > installmentMonths) {
            return 0L;
        }
        // 중도 전액 상환 — 상환 회차에 남은 원금을 몰고, 이후 회차는 0.
        // 정상 분할 산식과 같은 자리에 둔다: 산식이 두 곳으로 갈라지면 합이 원금과 어긋난다.
        Integer payoffSeq = installmentPayoffSequence();
        if (payoffSeq != null && payoffSeq <= installmentMonths) {
            if (seq > payoffSeq) {
                return 0L;
            }
            if (seq == payoffSeq) {
                long paidBefore = 0L;
                for (int i = 1; i < payoffSeq; i++) {
                    paidBefore += normalInstallmentAmountAt(i, principal);
                }
                return principal - paidBefore;
            }
        }
        return normalInstallmentAmountAt(seq, principal);
    }

    /** 정상 분할 회차 금액 — 나머지는 1회차에 몰아 합이 원금과 정확히 맞는다. */
    private long normalInstallmentAmountAt(int seq, long principal) {
        long base = principal / installmentMonths;
        long remainder = principal % installmentMonths;
        return seq == 1 ? base + remainder : base;
    }

    /**
     * 상환일이 몇 회차(월)에 속하는지. 상환 안 했으면 null.
     *
     * <p>구매월보다 이른 상환일은 1회차로 본다 — 결제일이 지나 다가오는 회차가 구매월보다
     * 앞서는 경우(이달에 산 할부를 바로 정리)인데, 그때는 첫 회차에 전액을 몰면 된다.
     */
    public Integer installmentPayoffSequence() {
        if (installmentPayoffDate == null || !isInstallment()) {
            return null;
        }
        return Math.max(1, installmentSequenceAt(installmentPayoffDate));
    }

    /**
     * 이 날짜가 속한 청구 회차 번호(1-base, 구매월 = 1회차). 범위 검사는 하지 않는다.
     *
     * <p>회차 산식은 여기 <b>한 곳</b>이다 — 청구 계산(installmentDuesIn)·상환 검증·표시가
     * 전부 이걸 쓴다. 산식이 갈라지면 회차 합이 원금과 어긋난다.
     */
    public int installmentSequenceAt(LocalDate dateInCycle) {
        return installmentSequenceAt(dateInCycle, expenseDate.toLocalDate());
    }

    /** 구매일을 <b>가정해서</b> 센 회차 번호 — 미리보기가 "날짜를 옮기면" 을 묻는다. */
    public static int installmentSequenceAt(LocalDate dateInCycle, LocalDate purchasedOn) {
        return (int) (java.time.temporal.ChronoUnit.MONTHS.between(
            java.time.YearMonth.from(purchasedOn),
            java.time.YearMonth.from(dateInCycle)) + 1);
    }

    /**
     * 할부 중도 전액 상환 — 남은 원금을 [payoffDate] 가 속한 회차에 몰아 청구되게 한다.
     *
     * <p>이미 끝난 할부(상환 회차 > N)는 거부한다 — 정리할 남은 원금이 없다.
     */
    public void payoffInstallment(LocalDate payoffDate) {
        if (!isInstallment()) {
            throw new IllegalStateException("할부 거래가 아니다");
        }
        this.installmentPayoffDate = payoffDate;
        if (installmentPayoffSequence() > installmentMonths) {
            this.installmentPayoffDate = null;
            throw new IllegalStateException("이미 회차가 끝난 할부다");
        }
    }

    /** 상환 취소 — 정상 분할로 되돌린다. 잘못 누른 상환을 무르는 경로. */
    public void cancelInstallmentPayoff() {
        this.installmentPayoffDate = null;
    }

    /** 카테고리만 교체 — 카테고리 재편 시 일괄 이동용(다른 값은 건드리지 않는다). */
    public void changeCategory(ExpenseCategory category) {
        this.category = category;
    }

    public void updateExpense(ExpenseCategory category, Asset asset, ExpenseType expenseType,
                              Long amount, String description, LocalDateTime expenseDate,
                              String merchant, String paymentMethod, Integer installmentMonths,
                              BigDecimal originalAmount,
                              String originalCurrency, BigDecimal exchangeRate) {
        this.installmentMonths = normalizeInstallment(installmentMonths);
        applyForeignCurrency(originalAmount, originalCurrency, exchangeRate);
        this.category = category;
        this.asset = asset;
        this.expenseType = expenseType;
        this.amount = amount;
        this.description = description;
        this.expenseDate = expenseDate;
        this.merchant = merchant;
        this.paymentMethod = paymentMethod;
    }

    public void setCalendarEvent(CalendarEvent calendarEvent) {
        this.calendarEvent = calendarEvent;
    }

    public void setTodo(Todo todo) {
        this.todo = todo;
    }

    /**
     * 금액만 바꾼다 — 신용카드의 "이전 미결제 사용액"(D4) 을 카드 편집 폼에서 고치는 자리.
     *
     * <p>일반 수정 경로({@link #updateExpense})는 시스템 거래에 열려 있지 않다. 여기만
     * 예외로 두되 <b>금액 하나</b>만 연다 — 날짜·자산·분류가 바뀌면 그건 이월 거래가 아니다.
     */
    public void updateAmountOnly(long newAmount) {
        this.amount = newAmount;
    }

    public void deleteExpense() {
        this.isDeleted = YNType.Y;
    }
}
