package io.groo.damda.card.service;

import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.asset.service.AssetBalanceHistoryService;
import io.groo.damda.asset.service.AssetService;
import io.groo.damda.asset.service.dto.AssetServiceDto;
import io.groo.damda.asset.type.AssetType;
import io.groo.core.type.YNType;
import io.groo.damda.asset.domain.AssetTransfer;
import io.groo.damda.card.domain.CardBilling;
import io.groo.damda.card.repository.CardBillingRepository;
import io.groo.damda.card.service.dto.CardPaymentServiceDto;
import io.groo.damda.card.type.BillingStatus;
import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.user.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doReturn;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import io.groo.core.time.ServiceClock;
import io.groo.core.time.UserClock;

/**
 * 카드 결제 서비스 회귀 방지 단위 테스트 — 소유권 / 신용카드 검증 / 결제 자산 필수 +
 * 결제 회차(청구 기간 = 결제일의 전월 1일~말일) 금액·귀속 계산.
 */
@ExtendWith(MockitoExtension.class)
class CardPaymentServiceImplTest {

    @Mock private CardBillingRepository cardBillingRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private AssetService assetService;
    @Mock private AssetBalanceHistoryService balanceHistoryService;
    @Mock private EntityManager entityManager;
    // 날짜 판정용 — mock 이면 null 이 흘러 NPE. 실물을 주입하되 사용자 조회는 비어
    // 서비스 기준(Asia/Seoul)으로 폴백한다.
    @Spy private UserClock userClock = new UserClock(rowId -> null, new ServiceClock("Asia/Seoul"));
    // 배치는 카드 한 장마다 새 트랜잭션을 연다 — 단위 테스트에서는 상태만 돌려주고 커밋은 no-op.
    @Mock private PlatformTransactionManager transactionManager;
    // 회차별 결제일(D5) — 이력이 비어 있으면 카드의 지금 결제일 하나로 센다. 닫힌 회차의 "오늘"은 서울 시계(D11).
    private final io.groo.damda.asset.repository.AssetPaymentDayHistoryRepository paymentDayHistory =
        org.mockito.Mockito.mock(io.groo.damda.asset.repository.AssetPaymentDayHistoryRepository.class);
    @org.mockito.Spy private io.groo.damda.card.service.PaymentScheduleService paymentScheduleService =
        new io.groo.damda.card.service.PaymentScheduleService(paymentDayHistory);
    @org.mockito.Spy private io.groo.core.time.ServiceClock serviceClock =
        new io.groo.core.time.ServiceClock("Asia/Seoul");

    @InjectMocks private CardPaymentServiceImpl sut;

    @BeforeEach
    void givenTransaction() {
        lenient().when(transactionManager.getTransaction(any()))
            .thenReturn(new SimpleTransactionStatus());
    }

    private static final long USER_ID = 1L;
    private static final long CARD_ID = 5L;

    private User user(long rowId) {
        User u = User.createUser(null, "tester", "테스터", "tester@porest.com");
        ReflectionTestUtils.setField(u, "rowId", rowId);
        return u;
    }

    /** 소유자 USER_ID 의 신용카드 mock — paymentDay·rowId 지정. */
    private Asset creditCard(Integer paymentDay) {
        Asset card = mock(Asset.class);
        lenient().when(card.getUser()).thenReturn(user(USER_ID));
        lenient().when(card.getAssetType()).thenReturn(AssetType.CREDIT_CARD);
        lenient().when(card.getRowId()).thenReturn(CARD_ID);
        lenient().when(card.getPaymentDay()).thenReturn(paymentDay);
        // 청구는 현재 빚을 넘지 않는다(캡). 회차 산수 자체를 보는 테스트가 캡에 걸리지 않도록
        // 빚을 넉넉히 잡아 둔다 — 캡·환급을 보는 테스트는 givenCardBalance 로 덮어쓴다.
        givenCardBalance(-100_000_000L);
        return card;
    }

    /** 카드 잔액 — 음수면 빚, 양수면 과납(환급 대상). */
    private void givenCardBalance(long balance) {
        lenient().when(balanceHistoryService.balanceAt(any(), any()))
            .thenReturn(new AssetBalanceHistoryService.Split(balance, 0L));
    }

    /**
     * cycleNetSpend JPQL mock — 일시불 순사용액(Long 쿼리) + 할부 회차 조회(Expense 쿼리).
     * 할부는 기본 없음으로 두고, 필요한 테스트만 {@link #givenInstallments} 로 덮어쓴다.
     */
    private void givenCycleSpend(long spend) {
        @SuppressWarnings("unchecked")
        TypedQuery<Long> query = mock(TypedQuery.class);
        lenient().when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(query);
        lenient().when(query.setParameter(anyString(), any())).thenReturn(query);
        lenient().when(query.getSingleResult()).thenReturn(spend);
        givenInstallments();
    }

    /** 할부 거래 목록 mock — 인자 없으면 할부 없음. */
    private void givenInstallments(Expense... installments) {
        @SuppressWarnings("unchecked")
        TypedQuery<Expense> query = mock(TypedQuery.class);
        lenient().when(entityManager.createQuery(anyString(), eq(Expense.class))).thenReturn(query);
        lenient().when(query.setParameter(anyString(), any())).thenReturn(query);
        lenient().when(query.getResultList()).thenReturn(List.of(installments));
    }

    /** 카드 할부 거래 — 결제일(구매일)과 금액·개월로 만든다. */
    private Expense installment(LocalDate purchasedOn, long amount, int months) {
        return Expense.createExpense(
            null, null, null, ExpenseType.EXPENSE, amount, "할부 결제",
            purchasedOn.atTime(14, 0), "테스트가맹점", "CARD", months,
            null,
            null,
            null);
    }

    // === 회차(청구 기간) 계산 ===

    @Test
    @DisplayName("결제 회차 — 결제일(12) 경과 후에는 다음 결제일 회차(당월 1일~말일)로 넘어간다")
    void cycleRollsToCurrentMonthAfterPaymentDay() {
        // 7.24 기준 다음 결제일 = 8.12, 그 회차의 청구 기간 = 7.1 ~ 7.31
        LocalDate next = CardPaymentServiceImpl.nextPaymentDate(12, LocalDate.of(2026, 7, 24));
        assertThat(next).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(CardPaymentServiceImpl.periodStartFor(next)).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(CardPaymentServiceImpl.periodEndFor(next)).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    @DisplayName("결제 회차 — 결제일 당일 회차의 청구 기간은 전월 1일~말일")
    void cycleOnPaymentDayCoversPreviousMonth() {
        LocalDate next = CardPaymentServiceImpl.nextPaymentDate(12, LocalDate.of(2026, 7, 12));
        assertThat(next).isEqualTo(LocalDate.of(2026, 7, 12));
        assertThat(CardPaymentServiceImpl.periodStartFor(next)).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(CardPaymentServiceImpl.periodEndFor(next)).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    // === 할부 중도 전액 상환 ===

    /**
     * 1,000,000원 3개월 할부(333,334 / 333,333 / 333,333)를 2회차 시점에 상환하면
     * 2회차에 1,000,000 − 333,334 = 666,666원이 몰리고 3회차는 0원이어야 한다.
     * 여기가 어긋나면 회차 합이 원금과 달라져 돈이 조용히 새거나 이중 청구된다.
     */
    @Test
    @DisplayName("중도상환 — 상환 회차에 남은 원금이 몰리고 이후 회차는 0원이다")
    void payoffLumpsRemainingIntoAnchorCycle() {
        Expense e = installment(LocalDate.of(2026, 7, 10), 1_000_000L, 3);

        e.payoffInstallment(LocalDate.of(2026, 8, 1)); // 2회차에 상환

        assertThat(e.installmentAmountAt(1)).isEqualTo(333_334L); // 이미 지난 회차 그대로
        assertThat(e.installmentAmountAt(2)).isEqualTo(666_666L); // 남은 원금 전부
        assertThat(e.installmentAmountAt(3)).isZero();
        // 합 = 원금 — 이게 깨지면 마지막 회차에 몇 원이 남거나 초과 청구된다.
        assertThat(e.installmentAmountAt(1) + e.installmentAmountAt(2) + e.installmentAmountAt(3))
            .isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("중도상환 취소 — 정상 분할로 되돌아간다")
    void payoffCancelRestoresNormalSchedule() {
        Expense e = installment(LocalDate.of(2026, 7, 10), 1_000_000L, 3);
        e.payoffInstallment(LocalDate.of(2026, 8, 1));

        e.cancelInstallmentPayoff();

        assertThat(e.installmentAmountAt(2)).isEqualTo(333_333L);
        assertThat(e.installmentAmountAt(3)).isEqualTo(333_333L);
    }

    @Test
    @DisplayName("중도상환 — 구매월보다 이른 상환일은 1회차 전액이 된다(다가오는 회차가 구매월보다 앞선 경우)")
    void payoffBeforePurchaseMonthLumpsIntoFirstCycle() {
        // 9/1 에 산 할부를 그날 바로 정리 — 다가오는 회차(8월분)가 구매월보다 앞선다.
        Expense e = installment(LocalDate.of(2026, 9, 1), 1_200_000L, 12);

        e.payoffInstallment(LocalDate.of(2026, 8, 1));

        assertThat(e.installmentAmountAt(1)).isEqualTo(1_200_000L);
        assertThat(e.installmentAmountAt(2)).isZero();
    }

    @Test
    @DisplayName("중도상환 — 상환된 할부는 다가오는 청구에 남은 원금으로 잡히고 paidOff 로 표시된다")
    void payoffReflectsInUpcomingBilling() {
        Asset card = creditCard(12);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        // 오늘 8/24 → 다음 결제일 9/12 → 청구 기간 8/1~8/31 (7월 구매 기준 2회차)
        doReturn(LocalDate.of(2026, 8, 24)).when(serviceClock).today();
        givenCycleSpend(0L);
        Expense e = installment(LocalDate.of(2026, 7, 10), 1_000_000L, 3);
        e.payoffInstallment(LocalDate.of(2026, 8, 1));
        givenInstallments(e);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);

        CardPaymentServiceDto.CardBillingInfo info = sut.getCardBilling(CARD_ID, USER_ID);

        assertThat(info.upcomingAmount()).isEqualTo(666_666L);
        assertThat(info.upcomingInstallments()).singleElement()
            .satisfies(d -> {
                assertThat(d.amount()).isEqualTo(666_666L);
                assertThat(d.paidOff()).isTrue();
            });
    }

    @Test
    @DisplayName("중도상환 — 할부가 아니면 거부한다")
    void payoffRejectsNonInstallment() {
        Asset card = creditCard(12);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        lenient().doReturn(LocalDate.of(2026, 8, 24)).when(serviceClock).today();
        Expense lump = Expense.createExpense(
            null, null, null, ExpenseType.EXPENSE, 50_000L, null,
            LocalDate.of(2026, 8, 10).atTime(12, 0), "커피", "CARD", null,
            null, null, null);
        ReflectionTestUtils.setField(lump, "asset", cardAssetStub());
        given(entityManager.find(Expense.class, 77L)).willReturn(lump);

        assertThatThrownBy(() -> sut.payoffInstallment(CARD_ID, 77L, USER_ID))
            .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("중도상환 — 이미 끝난 할부는 거부한다(정리할 남은 원금이 없다)")
    void payoffRejectsFinishedInstallment() {
        Asset card = creditCard(12);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        // 다가오는 회차 2027-01 — 2026-07 구매 3개월 할부는 7회차라 끝났다
        doReturn(LocalDate.of(2027, 1, 24)).when(serviceClock).today();
        Expense e = installment(LocalDate.of(2026, 7, 10), 300_000L, 3);
        ReflectionTestUtils.setField(e, "asset", cardAssetStub());
        given(entityManager.find(Expense.class, 77L)).willReturn(e);

        assertThatThrownBy(() -> sut.payoffInstallment(CARD_ID, 77L, USER_ID))
            .isInstanceOf(InvalidValueException.class);
        assertThat(e.getInstallmentPayoffDate()).isNull(); // 거부 시 상태를 남기지 않는다
    }

    @Test
    @DisplayName("중도상환 — 남의 카드 거래·없는 거래는 못 찾은 것으로 취급한다")
    void payoffRejectsForeignExpense() {
        Asset card = creditCard(12);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        lenient().doReturn(LocalDate.of(2026, 8, 24)).when(serviceClock).today();
        given(entityManager.find(Expense.class, 77L)).willReturn(null);

        assertThatThrownBy(() -> sut.payoffInstallment(CARD_ID, 77L, USER_ID))
            .isInstanceOf(io.groo.core.exception.EntityNotFoundException.class);
    }

    /** 이 카드(CARD_ID)에 붙은 자산 stub — 상환 대상 검증이 asset.rowId 를 대조한다. */
    private Asset cardAssetStub() {
        Asset a = mock(Asset.class);
        lenient().when(a.getRowId()).thenReturn(CARD_ID);
        return a;
    }

    // === 할부 구성(명세서 표시용) ===

    /**
     * 명세서가 "원금·N개월 중 k회차·이번 회차 금액" 을 그리려면 합계가 아니라 구성이 와야 한다.
     * 1억 24개월이면 base 4,166,666 에 나머지 16원이 1회차에 몰린다(카드사 관행) —
     * 이 구성이 틀리면 회차 합이 원금과 안 맞아 마지막 달 청구가 어긋난다.
     */
    @Test
    @DisplayName("할부 구성 — 원금·회차·회차금액이 내려가고, 나머지는 1회차에 몰린다")
    void billingExposesInstallmentBreakdown() {
        Asset card = creditCard(6);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        // 오늘 8/10 → 다음 결제일 9/6 → 청구 기간 8/1~8/31 (구매월 8월 기준 1회차)
        doReturn(LocalDate.of(2026, 8, 10)).when(serviceClock).today();
        givenCycleSpend(30_000L);
        Expense phone = installment(LocalDate.of(2026, 8, 4), 100_000_000L, 24);
        ReflectionTestUtils.setField(phone, "rowId", 66_814L);
        givenInstallments(phone);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);

        CardPaymentServiceDto.CardBillingInfo info = sut.getCardBilling(CARD_ID, USER_ID);

        assertThat(info.upcomingLumpSumAmount()).isEqualTo(30_000L);
        assertThat(info.upcomingInstallments()).hasSize(1);
        CardPaymentServiceDto.InstallmentDue due = info.upcomingInstallments().get(0);
        assertThat(due.expenseRowId()).isEqualTo(66_814L);
        assertThat(due.principalAmount()).isEqualTo(100_000_000L);
        assertThat(due.installmentMonths()).isEqualTo(24);
        assertThat(due.sequence()).isEqualTo(1);
        // 1회차 = base 4,166,666 + 나머지 16
        assertThat(due.amount()).isEqualTo(4_166_682L);
        assertThat(info.upcomingAmount()).isEqualTo(30_000L + 4_166_682L);
    }

    @Test
    @DisplayName("할부 구성 — 회차가 끝난 할부는 목록에서 빠진다(0원 행을 내리지 않는다)")
    void finishedInstallmentExcludedFromBreakdown() {
        Asset card = creditCard(6);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        // 청구 기간 2027-02 — 2026-08 구매 3개월 할부(회차 1..3)는 7회차라 이미 끝났다
        doReturn(LocalDate.of(2027, 2, 10)).when(serviceClock).today();
        givenCycleSpend(0L);
        givenInstallments(installment(LocalDate.of(2026, 8, 4), 300_000L, 3));
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);

        CardPaymentServiceDto.CardBillingInfo info = sut.getCardBilling(CARD_ID, USER_ID);

        // 0원 행을 내리면 화면이 "0/3회차 0원" 같은 유령 행을 그린다.
        assertThat(info.upcomingInstallments()).isEmpty();
        assertThat(info.upcomingAmount()).isZero();
    }

    @Test
    @DisplayName("할부 구성 — 선결제한 만큼 기결제액으로 내려가 화면이 차감을 설명할 수 있다")
    void alreadyPaidExposedForBreakdown() {
        Asset card = creditCard(12);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        doReturn(LocalDate.of(2026, 7, 24)).when(serviceClock).today();
        givenCycleSpend(280_000L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(100_000L);

        CardPaymentServiceDto.CardBillingInfo info = sut.getCardBilling(CARD_ID, USER_ID);

        assertThat(info.upcomingAlreadyPaidAmount()).isEqualTo(100_000L);
        // 예정액 = 280,000 − 100,000. 구성(일시불 280,000 − 기결제 100,000)과 합이 맞아야
        // 화면이 "왜 이 숫자인지" 를 설명할 수 있다.
        assertThat(info.upcomingAmount()).isEqualTo(180_000L);
    }

    // === 할부 — 실제 카드 사용 시나리오 ===

    /**
     * 7/10 에 아이폰 150만원을 6개월 할부로 샀다. 카드 결제일은 12일.
     *
     * <p>일시불이면 8/12 에 150만원이 통째로 빠지지만, 할부라 25만원씩 6번에 나뉜다.
     * 8월 청구(7/1~7/31 사용분)는 1회차 25만원 + 그 달 일시불 사용분이어야 한다.
     */
    @Test
    @DisplayName("할부 — 7월에 산 150만원 6개월 할부는 8월 청구에 25만원만 잡힌다(일시불 3만원과 합산)")
    void installmentFirstCycle() {
        Asset card = creditCard(12);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        // 오늘 7/24 → 다음 결제일 8/12 → 청구 기간 7/1~7/31
        doReturn(LocalDate.of(2026, 7, 24)).when(serviceClock).today();
        givenCycleSpend(30_000L); // 7월 일시불 사용분(커피·편의점 등)
        givenInstallments(installment(LocalDate.of(2026, 7, 10), 1_500_000L, 6));
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);

        CardPaymentServiceDto.CardBillingInfo info = sut.getCardBilling(CARD_ID, USER_ID);

        assertThat(info.upcomingAmount()).isEqualTo(280_000L); // 30,000 + 250,000
    }

    @Test
    @DisplayName("할부 — 같은 거래가 다음 달 청구에도 25만원으로 이어진다(2회차)")
    void installmentSecondCycle() {
        Asset card = creditCard(12);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        // 오늘 8/24 → 다음 결제일 9/12 → 청구 기간 8/1~8/31 (구매월 7월 기준 2회차)
        doReturn(LocalDate.of(2026, 8, 24)).when(serviceClock).today();
        givenCycleSpend(0L); // 8월엔 일시불 사용 없음
        givenInstallments(installment(LocalDate.of(2026, 7, 10), 1_500_000L, 6));
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);

        assertThat(sut.getCardBilling(CARD_ID, USER_ID).upcomingAmount()).isEqualTo(250_000L);
    }

    @Test
    @DisplayName("할부 — 6개월이 끝난 뒤(7회차)에는 더 이상 청구되지 않는다")
    void installmentAfterLastCycle() {
        Asset card = creditCard(12);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        // 오늘 2027-01-24 → 다음 결제일 2027-02-12 → 청구 기간 2027-01 (구매월 2026-07 기준 7회차)
        doReturn(LocalDate.of(2027, 1, 24)).when(serviceClock).today();
        givenCycleSpend(0L);
        givenInstallments(installment(LocalDate.of(2026, 7, 10), 1_500_000L, 6));
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);

        assertThat(sut.getCardBilling(CARD_ID, USER_ID).upcomingAmount()).isZero();
    }

    @Test
    @DisplayName("할부 — 노트북 100만원 3개월(나머지 1원)은 첫 청구가 333,334원")
    void installmentUnevenFirstCycle() {
        Asset card = creditCard(12);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        doReturn(LocalDate.of(2026, 7, 24)).when(serviceClock).today();
        givenCycleSpend(0L);
        givenInstallments(installment(LocalDate.of(2026, 7, 5), 1_000_000L, 3));
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);

        assertThat(sut.getCardBilling(CARD_ID, USER_ID).upcomingAmount()).isEqualTo(333_334L);
    }

    @Test
    @DisplayName("할부 — 여러 건이 겹치면 회차 금액이 합산된다(아이폰 2회차 + 냉장고 1회차)")
    void multipleInstallmentsOverlap() {
        Asset card = creditCard(12);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        // 청구 기간 8/1~8/31 — 7월 구매분은 2회차, 8월 구매분은 1회차
        doReturn(LocalDate.of(2026, 8, 24)).when(serviceClock).today();
        givenCycleSpend(15_000L);
        givenInstallments(
            installment(LocalDate.of(2026, 7, 10), 1_500_000L, 6),   // 2회차 250,000
            installment(LocalDate.of(2026, 8, 3), 2_400_000L, 12));  // 1회차 200,000
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);

        assertThat(sut.getCardBilling(CARD_ID, USER_ID).upcomingAmount()).isEqualTo(465_000L);
    }

    // === getCardBilling — 회차 금액 ===

    @Test
    @DisplayName("getCardBilling — 결제예정액 = 회차 순사용액 − 같은 회차 기결제액(선결제 차감)")
    void upcomingAmountIsCycleSpendMinusAlreadyPaid() {
        Asset card = creditCard(12);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        givenCycleSpend(100_000L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(30_000L);

        CardPaymentServiceDto.CardBillingInfo info = sut.getCardBilling(CARD_ID, USER_ID);

        assertThat(info.upcomingAmount()).isEqualTo(70_000L);
        // 다가오는 회차의 청구 기간(전월 1일~말일)이 응답에 포함된다
        assertThat(info.upcomingPeriodStart()).isNotNull();
        assertThat(info.upcomingPeriodStart().getDayOfMonth()).isEqualTo(1);
        assertThat(info.upcomingPeriodEnd())
            .isEqualTo(info.upcomingPeriodStart().withDayOfMonth(info.upcomingPeriodStart().lengthOfMonth()));
    }

    @Test
    @DisplayName("getCardBilling — 기결제가 사용액 이상이면 결제예정액은 0 (음수 금지)")
    void upcomingAmountNeverNegative() {
        Asset card = creditCard(12);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        givenCycleSpend(50_000L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(80_000L);

        assertThat(sut.getCardBilling(CARD_ID, USER_ID).upcomingAmount()).isZero();
    }

    @Test
    @DisplayName("getCardBilling — 결제일 미설정 카드는 당월 1일~말일 사용액으로 청구한다")
    void upcomingUsesCalendarMonthWithoutPaymentDay() {
        Asset card = creditCard(null);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        doReturn(LocalDate.of(2026, 7, 24)).when(serviceClock).today();
        givenCycleSpend(33_800L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);

        CardPaymentServiceDto.CardBillingInfo info = sut.getCardBilling(CARD_ID, USER_ID);

        assertThat(info.upcomingAmount()).isEqualTo(33_800L);
        // 회차 기간이 잡힌다 — 종전엔 기간 없이 잔액 전액을 청구했다
        assertThat(info.upcomingPeriodStart()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(info.upcomingPeriodEnd()).isEqualTo(LocalDate.of(2026, 7, 31));
        // 결제일이 없는 건 사실이므로 그대로 null
        assertThat(info.nextPaymentDate()).isNull();
    }

    @Test
    @DisplayName("결제일 미설정 카드 — 잔액이 양수여도 당월 사용액을 보여준다(돈 이동은 payoff 캡)")
    void positiveBalanceWithoutPaymentDayShowsSpend() {
        Asset card = creditCard(null);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        doReturn(LocalDate.of(2026, 7, 24)).when(serviceClock).today();
        givenCardBalance(228_600L); // 과납 상태
        givenCycleSpend(50_000L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);

        assertThat(sut.getCardBilling(CARD_ID, USER_ID).upcomingAmount()).isEqualTo(50_000L);
    }

    // === 결제예정액 — 빚으로 캡하지 않는다(양수 역전 방지는 payoff 의 돈 이동 캡) ===

    @Test
    @DisplayName("결제예정액 — 잔액을 수동 보정(앵커)해 빚이 줄어도 표시·기록은 회차 사용액 전액")
    void billingShowsCycleSpendDespiteReducedDebt() {
        Asset card = creditCard(12);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        doReturn(LocalDate.of(2026, 7, 24)).when(serviceClock).today();
        givenCycleSpend(500_000L);          // 기록상 사용액
        givenCardBalance(-120_000L);        // 실제 남은 빚(수동 보정으로 줄어듦)
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);

        // 종전엔 빚(120,000)으로 캡해서 표시했다 — 빚 0 카드는 버튼이 죽는 사고의 뿌리.
        assertThat(sut.getCardBilling(CARD_ID, USER_ID).upcomingAmount()).isEqualTo(500_000L);
    }

    @Test
    @DisplayName("결제예정액 — 잔액이 양수여도 회차 사용액을 그대로 보여준다(돈은 결제 시 안 움직임)")
    void positiveBalanceStillShowsCycleSpend() {
        Asset card = creditCard(12);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        doReturn(LocalDate.of(2026, 7, 24)).when(serviceClock).today();
        givenCycleSpend(500_000L);
        givenCardBalance(228_600L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);

        assertThat(sut.getCardBilling(CARD_ID, USER_ID).upcomingAmount()).isEqualTo(500_000L);
    }

    @Test
    @DisplayName("결제예정액 — 빚이 사용액보다 많은 정상 상태는 사용액 그대로(할부 전액 선반영 등)")
    void capDoesNotShrinkNormalBilling() {
        Asset card = creditCard(12);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        doReturn(LocalDate.of(2026, 7, 24)).when(serviceClock).today();
        givenCycleSpend(280_000L);
        // 할부는 구매 시 전액이 잔액에 잡히므로 빚이 회차 청구보다 크다 — 정상 상태
        givenCardBalance(-1_530_000L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);

        assertThat(sut.getCardBilling(CARD_ID, USER_ID).upcomingAmount()).isEqualTo(280_000L);
    }

    // === payCard — 선결제 귀속 ===

    @Test
    @DisplayName("payCard — 선결제는 다가오는 회차의 기간·금액으로 귀속된다(실행일의 전월 라벨 금지)")
    void manualPaymentBelongsToUpcomingCycle() {
        Asset paymentAsset = mock(Asset.class);
        lenient().when(paymentAsset.getRowId()).thenReturn(9L);
        Asset card = creditCard(12);
        given(card.getPaymentAsset()).willReturn(paymentAsset);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        // 오늘을 고정한다 — 서비스는 서울 시계(serviceClock)로 회차를 정하는데 기대값을 LocalDate.now()(JVM 시간대)로
        // 계산하면, 서울만 결제일(12일)로 넘어간 시각(UTC 15시~자정)에 하루 어긋나 실패했다(2026-11-12 00:03 KST CI).
        LocalDate today = LocalDate.of(2026, 8, 24);
        doReturn(today).when(serviceClock).today();
        givenCycleSpend(70_000L);
        givenCardBalance(-70_000L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);

        AssetServiceDto.TransferInfo transfer = mock(AssetServiceDto.TransferInfo.class);
        given(transfer.rowId()).willReturn(77L);
        given(assetService.createTransfer(any())).willReturn(transfer);
        given(cardBillingRepository.save(any(CardBilling.class)))
            .willAnswer(inv -> inv.getArgument(0));

        sut.payCard(CARD_ID, USER_ID, null);

        ArgumentCaptor<CardBilling> captor = ArgumentCaptor.forClass(CardBilling.class);
        verify(cardBillingRepository).save(captor.capture());
        CardBilling saved = captor.getValue();

        // 다가오는 결제일(nextPaymentDate)의 회차 = 그 결제일의 전월 1일~말일
        LocalDate next = CardPaymentServiceImpl.nextPaymentDate(12, today);
        assertThat(next).isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(saved.getPeriodStart()).isEqualTo(CardPaymentServiceImpl.periodStartFor(next));
        assertThat(saved.getPeriodEnd()).isEqualTo(CardPaymentServiceImpl.periodEndFor(next));
        assertThat(saved.getBillingAmount()).isEqualTo(70_000L);
        assertThat(saved.getStatus()).isEqualTo(BillingStatus.COMPLETED);
    }

    @Test
    @DisplayName("payCard — 회차 잔여액 0이면 이체 없이 SKIPPED 기록")
    void manualPaymentSkipsWhenCycleSettled() {
        Asset paymentAsset = mock(Asset.class);
        Asset card = creditCard(12);
        given(card.getPaymentAsset()).willReturn(paymentAsset);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        givenCycleSpend(50_000L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(50_000L);
        given(cardBillingRepository.save(any(CardBilling.class)))
            .willAnswer(inv -> inv.getArgument(0));

        CardPaymentServiceDto.BillingInfo result = sut.payCard(CARD_ID, USER_ID, null);

        assertThat(result.status()).isEqualTo(BillingStatus.SKIPPED);
        assertThat(result.billingAmount()).isZero();
    }

    // === 부분 선결제 · 기록용 앱 특례 ===

    @Test
    @DisplayName("부분 선결제 — 청구액 448,600 중 200,000만 결제하면 그 금액만 기록된다")
    void partialPrepayment() {
        Asset paymentAsset = mock(Asset.class);
        Asset card = creditCard(25);
        given(card.getPaymentAsset()).willReturn(paymentAsset);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        givenCycleSpend(448_600L);
        givenCardBalance(-448_600L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);
        AssetServiceDto.TransferInfo transfer = mock(AssetServiceDto.TransferInfo.class);
        given(transfer.rowId()).willReturn(77L);
        given(assetService.createTransfer(any())).willReturn(transfer);
        given(cardBillingRepository.save(any(CardBilling.class)))
            .willAnswer(inv -> inv.getArgument(0));

        CardPaymentServiceDto.BillingInfo result = sut.payCard(CARD_ID, USER_ID, 200_000L);

        assertThat(result.billingAmount()).isEqualTo(200_000L);
        assertThat(result.status()).isEqualTo(BillingStatus.COMPLETED);
    }

    @Test
    @DisplayName("부분 선결제 후 남은 청구액 — 448,600 중 200,000 냈으면 다음엔 248,600")
    void remainingAfterPartial() {
        Asset paymentAsset = mock(Asset.class);
        Asset card = creditCard(25);
        given(card.getPaymentAsset()).willReturn(paymentAsset);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        givenCycleSpend(448_600L);
        givenCardBalance(-248_600L);
        // 청구액은 '사용액 − 이미 결제한 금액' 이라 선결제분이 자동으로 빠진다.
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(200_000L);
        AssetServiceDto.TransferInfo transfer = mock(AssetServiceDto.TransferInfo.class);
        given(transfer.rowId()).willReturn(78L);
        given(assetService.createTransfer(any())).willReturn(transfer);
        given(cardBillingRepository.save(any(CardBilling.class)))
            .willAnswer(inv -> inv.getArgument(0));

        CardPaymentServiceDto.BillingInfo result = sut.payCard(CARD_ID, USER_ID, null);

        assertThat(result.billingAmount()).isEqualTo(248_600L);
    }

    // === 돈 이동 캡 — 청구(기록)는 사용액, 이체는 남은 빚까지만 ===

    @Test
    @DisplayName("빚 0(잔액 앵커) 카드 — 사용액대로 결제 완료되고 이체·상계는 만들지 않는다")
    void paysAnchoredCardWithoutMovingMoney() {
        Asset paymentAsset = mock(Asset.class);
        Asset card = creditCard(12);
        given(card.getPaymentAsset()).willReturn(paymentAsset);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        givenCycleSpend(225_733L);
        givenCardBalance(0L); // 자산 수정으로 잔액 0 앵커 — 지출은 이미 잔액에 정리된 상태
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);
        given(cardBillingRepository.save(any(CardBilling.class)))
            .willAnswer(inv -> inv.getArgument(0));

        CardPaymentServiceDto.BillingInfo result = sut.payCard(CARD_ID, USER_ID, null);

        assertThat(result.status()).isEqualTo(BillingStatus.COMPLETED);
        assertThat(result.billingAmount()).isEqualTo(225_733L);
        then(assetService).should(never()).createTransfer(any());
        then(balanceHistoryService).should(never())
            .recordExpense(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("돈 이동 캡 — 빚이 청구보다 작으면 이체는 남은 빚까지만 나간다")
    void transferIsCappedByRemainingDebt() {
        Asset paymentAsset = mock(Asset.class);
        lenient().when(paymentAsset.getRowId()).thenReturn(9L);
        Asset card = creditCard(12);
        given(card.getPaymentAsset()).willReturn(paymentAsset);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        givenCycleSpend(500_000L);
        givenCardBalance(-120_000L); // 수동 보정으로 빚이 줄어든 상태
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);
        AssetServiceDto.TransferInfo transfer = mock(AssetServiceDto.TransferInfo.class);
        given(transfer.rowId()).willReturn(77L);
        given(assetService.createTransfer(any())).willReturn(transfer);
        given(cardBillingRepository.save(any(CardBilling.class)))
            .willAnswer(inv -> inv.getArgument(0));

        CardPaymentServiceDto.BillingInfo result = sut.payCard(CARD_ID, USER_ID, null);

        // 기록은 회차 사용액 전액 — 500,000 을 그대로 이체하면 잔액이 +380,000 으로 뒤집힌다.
        assertThat(result.billingAmount()).isEqualTo(500_000L);
        ArgumentCaptor<AssetServiceDto.CreateTransferCommand> cmdCaptor =
            ArgumentCaptor.forClass(AssetServiceDto.CreateTransferCommand.class);
        verify(assetService).createTransfer(cmdCaptor.capture());
        assertThat(cmdCaptor.getValue().amount()).isEqualTo(120_000L);
    }

    @Test
    @DisplayName("수동 결제 이체는 결제일 자정이 아니라 누른 시각으로 찍힌다")
    void manualPaymentTransferIsStampedAtPressTime() {
        Asset paymentAsset = mock(Asset.class);
        lenient().when(paymentAsset.getRowId()).thenReturn(9L);
        Asset card = creditCard(12);
        given(card.getPaymentAsset()).willReturn(paymentAsset);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        givenCycleSpend(300_000L);
        givenCardBalance(-300_000L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);
        AssetServiceDto.TransferInfo transfer = mock(AssetServiceDto.TransferInfo.class);
        given(transfer.rowId()).willReturn(78L);
        given(assetService.createTransfer(any())).willReturn(transfer);
        given(cardBillingRepository.save(any(CardBilling.class)))
            .willAnswer(inv -> inv.getArgument(0));
        // 11:40 에 눌렀다 — 같은 날 11:30 에 만든 통장 INIT 앵커보다 뒤여야 잔액에 잡힌다.
        LocalDateTime pressedAt = LocalDateTime.of(2026, 9, 2, 11, 40);
        doReturn(pressedAt.toLocalDate()).when(serviceClock).today();
        doReturn(pressedAt).when(userClock).now(USER_ID);

        sut.payCard(CARD_ID, USER_ID, null);

        ArgumentCaptor<AssetServiceDto.CreateTransferCommand> cmdCaptor =
            ArgumentCaptor.forClass(AssetServiceDto.CreateTransferCommand.class);
        verify(assetService).createTransfer(cmdCaptor.capture());
        assertThat(cmdCaptor.getValue().transferDate()).isEqualTo(pressedAt);
    }

    // === 다음 회차(nextCycle) + 회차 선택 결제 ===

    @Test
    @DisplayName("getCardBilling — 다가오는 회차 뒤에 지금 쌓이는 회차(다음 달 결제일, 당월 1일~말일)를 하나 더 내린다")
    void billingIncludesTheCycleAfterUpcoming() {
        Asset card = creditCard(12);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        givenCycleSpend(100_000L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(30_000L);
        doReturn(LocalDate.of(2026, 9, 2)).when(serviceClock).today();

        CardPaymentServiceDto.CardBillingInfo info = sut.getCardBilling(CARD_ID, USER_ID);

        assertThat(info.nextPaymentDate()).isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(info.upcomingPeriodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(info.nextCycle()).isNotNull();
        assertThat(info.nextCycle().paymentDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(info.nextCycle().periodStart()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(info.nextCycle().periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(info.nextCycle().amount()).isEqualTo(70_000L);
    }

    @Test
    @DisplayName("getCardBilling — 결제일이 없는 카드는 다음 회차도 없다")
    void noNextCycleWithoutPaymentDay() {
        Asset card = creditCard(null);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
        givenCycleSpend(0L);

        assertThat(sut.getCardBilling(CARD_ID, USER_ID).nextCycle()).isNull();
    }

    @Test
    @DisplayName("payCard — 다음 회차 결제일을 주면 그 회차(당월 1일~말일)로 귀속된다")
    void payCardWithNextCycleDateBillsThatCycle() {
        Asset card = creditCard(12);
        given(card.getPaymentAsset()).willReturn(null);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        givenCycleSpend(50_000L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);
        given(cardBillingRepository.save(any(CardBilling.class))).willAnswer(inv -> inv.getArgument(0));
        LocalDateTime pressedAt = LocalDateTime.of(2026, 9, 2, 11, 40);
        doReturn(pressedAt.toLocalDate()).when(serviceClock).today();
        doReturn(pressedAt).when(userClock).now(USER_ID);

        CardPaymentServiceDto.BillingInfo billed =
            sut.payCard(CARD_ID, USER_ID, null, LocalDate.of(2026, 10, 12));

        assertThat(billed.periodStart()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(billed.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(billed.billingAmount()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("payCard — 다가오는 회차 결제일을 주면 미전달과 같다(전월 1일~말일)")
    void payCardWithUpcomingDateIsSameAsDefault() {
        Asset card = creditCard(12);
        given(card.getPaymentAsset()).willReturn(null);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        givenCycleSpend(50_000L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);
        given(cardBillingRepository.save(any(CardBilling.class))).willAnswer(inv -> inv.getArgument(0));
        LocalDateTime pressedAt = LocalDateTime.of(2026, 9, 2, 11, 40);
        doReturn(pressedAt.toLocalDate()).when(serviceClock).today();
        doReturn(pressedAt).when(userClock).now(USER_ID);

        CardPaymentServiceDto.BillingInfo billed =
            sut.payCard(CARD_ID, USER_ID, null, LocalDate.of(2026, 9, 12));

        assertThat(billed.periodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(billed.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    @DisplayName("payCard — 두 회차 밖의 날짜는 거절한다(CARD_016)")
    void payCardRejectsOtherCycleDates() {
        Asset card = creditCard(12);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        doReturn(LocalDate.of(2026, 9, 2)).when(serviceClock).today();

        assertThatThrownBy(() -> sut.payCard(CARD_ID, USER_ID, null, LocalDate.of(2026, 11, 12)))
            .isInstanceOf(InvalidValueException.class)
            .hasMessage("error.card.billing.invalid.cycle");
    }

    @Test
    @DisplayName("무계좌 카드 — 상계 flow 도 남은 빚까지만 쌓는다")
    void offsetFlowIsCappedByRemainingDebt() {
        Asset card = creditCard(25);
        given(card.getPaymentAsset()).willReturn(null);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        givenCycleSpend(100_000L);
        givenCardBalance(-80_000L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);
        given(cardBillingRepository.save(any(CardBilling.class)))
            .willAnswer(inv -> inv.getArgument(0));

        CardPaymentServiceDto.BillingInfo result = sut.payCard(CARD_ID, USER_ID, null);

        assertThat(result.billingAmount()).isEqualTo(100_000L);
        then(balanceHistoryService).should()
            .recordExpense(eq(card), isNull(), eq(ExpenseType.INCOME), eq(80_000L), any());
    }

    @Test
    @DisplayName("남은 청구액보다 많이 결제하려 하면 막는다")
    void rejectsOverpayment() {
        Asset paymentAsset = mock(Asset.class);
        Asset card = creditCard(25);
        given(card.getPaymentAsset()).willReturn(paymentAsset);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        givenCycleSpend(448_600L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);

        assertThatThrownBy(() -> sut.payCard(CARD_ID, USER_ID, 500_000L))
            .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("0원 이하 결제는 막는다")
    void rejectsNonPositiveAmount() {
        Asset paymentAsset = mock(Asset.class);
        Asset card = creditCard(25);
        given(card.getPaymentAsset()).willReturn(paymentAsset);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        givenCycleSpend(448_600L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);

        assertThatThrownBy(() -> sut.payCard(CARD_ID, USER_ID, 0L))
            .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("결제계좌를 안 만들었어도 결제된다 — 이체 없이 카드 사용액만 정리")
    void paysWithoutPaymentAsset() {
        Asset card = creditCard(25);
        given(card.getPaymentAsset()).willReturn(null);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        givenCycleSpend(448_600L);
        givenCardBalance(-448_600L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);
        given(cardBillingRepository.save(any(CardBilling.class)))
            .willAnswer(inv -> inv.getArgument(0));

        CardPaymentServiceDto.BillingInfo result = sut.payCard(CARD_ID, USER_ID, null);

        // 이체는 만들지 않고 카드에 상계 flow 만 쌓는다 — 등록 안 한 통장은 순자산에 없다.
        assertThat(result.status()).isEqualTo(BillingStatus.COMPLETED);
        assertThat(result.billingAmount()).isEqualTo(448_600L);
        then(assetService).should(never()).createTransfer(any());
        then(balanceHistoryService).should()
            .recordExpense(eq(card), isNull(), eq(ExpenseType.INCOME), eq(448_600L), any());
    }

    @Test
    @DisplayName("지운 결제계좌는 없는 것과 같다 — 404 대신 이체 없이 사용액만 정리(QA 30 6)")
    void deletedPaymentAssetActsAsNone() {
        Asset deleted = mock(Asset.class);
        given(deleted.getIsDeleted()).willReturn(io.groo.core.type.YNType.Y);
        Asset card = creditCard(25);
        given(card.getPaymentAsset()).willReturn(deleted);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        givenCycleSpend(448_600L);
        givenCardBalance(-448_600L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);
        given(cardBillingRepository.save(any(CardBilling.class)))
            .willAnswer(inv -> inv.getArgument(0));

        CardPaymentServiceDto.BillingInfo result = sut.payCard(CARD_ID, USER_ID, null);

        assertThat(result.status()).isEqualTo(BillingStatus.COMPLETED);
        then(assetService).should(never()).createTransfer(any());
        then(balanceHistoryService).should()
            .recordExpense(eq(card), isNull(), eq(ExpenseType.INCOME), eq(448_600L), any());
    }

    @Test
    @DisplayName("출금계좌 잔액이 모자라도 결제된다 — 기록용 앱이라 막지 않는다")
    void paysDespiteInsufficientBalance() {
        Asset paymentAsset = mock(Asset.class); // 통장 잔액을 안 맞춰 둔 상태
        Asset card = creditCard(25);
        given(card.getPaymentAsset()).willReturn(paymentAsset);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        givenCycleSpend(448_600L);
        givenCardBalance(-448_600L);
        given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
            .willReturn(0L);
        AssetServiceDto.TransferInfo transfer = mock(AssetServiceDto.TransferInfo.class);
        given(transfer.rowId()).willReturn(79L);
        given(assetService.createTransfer(any())).willReturn(transfer);
        given(cardBillingRepository.save(any(CardBilling.class)))
            .willAnswer(inv -> inv.getArgument(0));

        CardPaymentServiceDto.BillingInfo result = sut.payCard(CARD_ID, USER_ID, null);

        assertThat(result.status()).isEqualTo(BillingStatus.COMPLETED);
    }

    // === 검증 회귀 ===

    @Test
    @DisplayName("getCardBilling — 남의 카드는 조회 불가")
    void getBillingRejectsOthers() {
        Asset card = mock(Asset.class);
        given(card.getUser()).willReturn(user(999L));
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));

        assertThatThrownBy(() -> sut.getCardBilling(CARD_ID, USER_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("payCard — 신용카드가 아니면 결제 불가")
    void payRejectsNonCreditCard() {
        Asset card = mock(Asset.class);
        given(card.getUser()).willReturn(user(USER_ID));
        given(card.getAssetType()).willReturn(AssetType.BANK_ACCOUNT);
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));

        assertThatThrownBy(() -> sut.payCard(CARD_ID, USER_ID, null))
                .isInstanceOf(InvalidValueException.class);
    }


    // ── 결제 취소 ──────────────────────────────────────────────────

    private CardBilling completedBilling(AssetTransfer transfer) {
        // 카드 mock 을 먼저 만들어 둔다 — given(...) 인자 안에서 만들면 스터빙이 중첩된다.
        Asset card = creditCard(25);
        CardBilling b = mock(CardBilling.class);
        lenient().when(b.getIsDeleted()).thenReturn(YNType.N);
        lenient().when(b.getStatus()).thenReturn(BillingStatus.COMPLETED);
        lenient().when(b.getCardAsset()).thenReturn(card);
        lenient().when(b.getTransfer()).thenReturn(transfer);
        return b;
    }

    @Test
    @DisplayName("결제 취소 — 결제로 만든 이체를 무른다(잔액·청구가 그 연쇄로 되돌아간다)")
    void cancelPaymentRevertsTransfer() {
        AssetTransfer transfer = mock(AssetTransfer.class);
        given(transfer.getRowId()).willReturn(77L);
        // billing 도 먼저 만들어 둔다 — given(...) 인자 안에서 만들면 스터빙이 중첩된다.
        CardBilling billing = completedBilling(transfer);
        given(cardBillingRepository.findById(5L)).willReturn(Optional.of(billing));

        sut.cancelPayment(5L, USER_ID);

        verify(assetService).deleteTransfer(77L, USER_ID);
    }

    @Test
    @DisplayName("결제 취소 — 남의 카드는 못 되돌린다")
    void cancelPaymentRejectsOthers() {
        CardBilling b = mock(CardBilling.class);
        Asset othersCard = mock(Asset.class);
        given(othersCard.getUser()).willReturn(user(999L));
        given(b.getIsDeleted()).willReturn(YNType.N);
        given(b.getCardAsset()).willReturn(othersCard);
        given(cardBillingRepository.findById(5L)).willReturn(Optional.of(b));

        assertThatThrownBy(() -> sut.cancelPayment(5L, USER_ID))
            .isInstanceOf(InvalidValueException.class);
        verify(assetService, never()).deleteTransfer(anyLong(), anyLong());
    }

    @Test
    @DisplayName("결제 취소 — 이미 무른 회차는 다시 못 되돌린다")
    void cancelPaymentRejectsNonCompleted() {
        Asset card = creditCard(25);
        CardBilling b = mock(CardBilling.class);
        given(b.getIsDeleted()).willReturn(YNType.N);
        given(b.getStatus()).willReturn(BillingStatus.SKIPPED);
        given(b.getCardAsset()).willReturn(card);
        given(cardBillingRepository.findById(5L)).willReturn(Optional.of(b));

        assertThatThrownBy(() -> sut.cancelPayment(5L, USER_ID))
            .isInstanceOf(InvalidValueException.class);
        verify(assetService, never()).deleteTransfer(anyLong(), anyLong());
    }

    // === 과납 환급 스윕 — 결제일과 무관하게 매일 검사 ===

    /** 환급 스윕 대상 카드 1장으로 스케줄을 돌린다. */
    private Asset scheduleWithOneCard(Integer paymentDay, Asset paymentAsset, long balance) {
        Asset card = creditCard(paymentDay);
        lenient().when(card.getPaymentAsset()).thenReturn(paymentAsset);
        givenCardBalance(balance);
        given(assetRepository.findAllByType(AssetType.CREDIT_CARD)).willReturn(List.of(card));
        // 배치는 목록에서 rowId 만 들고 나와 건마다 다시 읽는다(건별 트랜잭션).
        given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
        return card;
    }

    @Test
    @DisplayName("환급 — 잔액이 양수면 결제계좌로 돌려주는 이체를 만든다(카드→계좌, CARD_REFUND)")
    void refundsOverpaymentToPaymentAsset() {
        Asset paymentAsset = mock(Asset.class);
        lenient().when(paymentAsset.getRowId()).thenReturn(9L);
        // 결제일이 아닌 날 — 환급은 결제일과 무관하게 돌아야 한다
        scheduleWithOneCard(12, paymentAsset, 228_600L);

        sut.processDueCardPayments(LocalDate.of(2026, 7, 24));

        ArgumentCaptor<AssetServiceDto.CreateTransferCommand> captor =
            ArgumentCaptor.forClass(AssetServiceDto.CreateTransferCommand.class);
        verify(assetService).createTransfer(captor.capture());
        AssetServiceDto.CreateTransferCommand cmd = captor.getValue();
        // 방향이 결제와 반대다 — 카드에서 나가 결제계좌로 들어온다
        assertThat(cmd.fromAssetRowId()).isEqualTo(CARD_ID);
        assertThat(cmd.toAssetRowId()).isEqualTo(9L);
        assertThat(cmd.amount()).isEqualTo(228_600L);
        assertThat(cmd.autoSource()).isEqualTo("CARD_REFUND");
    }

    @Test
    @DisplayName("환급 — 결제계좌를 안 만들었으면 이체 없이 카드 잔액만 0으로 상계한다")
    void offsetsOverpaymentWithoutPaymentAsset() {
        scheduleWithOneCard(12, null, 228_600L);

        sut.processDueCardPayments(LocalDate.of(2026, 7, 24));

        verify(assetService, never()).createTransfer(any());
        // EXPENSE = 마이너스 flow — 양수 잔액을 0 으로 끌어내린다
        then(balanceHistoryService).should()
            .recordExpense(any(), isNull(), eq(ExpenseType.EXPENSE), eq(228_600L), any());
    }

    @Test
    @DisplayName("환급 — 잔액이 0이거나 빚이면 아무것도 만들지 않는다(멱등)")
    void doesNotRefundWhenNoSurplus() {
        Asset paymentAsset = mock(Asset.class);
        scheduleWithOneCard(12, paymentAsset, 0L);

        sut.processDueCardPayments(LocalDate.of(2026, 7, 24));

        verify(assetService, never()).createTransfer(any());
        then(balanceHistoryService).should(never())
            .recordExpense(any(), any(), eq(ExpenseType.EXPENSE), anyLong(), any());
    }

    @Test
    @DisplayName("환급 — 빚이 남은 정상 카드는 건드리지 않는다")
    void doesNotRefundWhenInDebt() {
        Asset paymentAsset = mock(Asset.class);
        scheduleWithOneCard(12, paymentAsset, -500_000L);

        sut.processDueCardPayments(LocalDate.of(2026, 7, 24));

        verify(assetService, never()).createTransfer(any());
    }

    @Test
    @DisplayName("환급은 CardBilling 을 만들지 않는다 — 청구 회차와 무관한 정산이다")
    void refundDoesNotCreateBilling() {
        Asset paymentAsset = mock(Asset.class);
        lenient().when(paymentAsset.getRowId()).thenReturn(9L);
        scheduleWithOneCard(12, paymentAsset, 100_000L);

        sut.processDueCardPayments(LocalDate.of(2026, 7, 24));

        verify(cardBillingRepository, never()).save(any(CardBilling.class));
    }

    /**
     * 청구는 <b>아직 오지 않은 거래를 세지 않는다</b>(D1, 2026-09-18 결정).
     *
     * <p>종전엔 회차 기간 전체를 세서, 같은 카드인데 한도 사용(잔액)과 청구 예정이 다르게
     * 보였다 — 사용자 제보 466,800 vs 527,100. 잔액은 {@code effective_at <= 지금} 인
     * 이력만 세므로, 청구도 같은 규칙으로 맞춘다. 가계부 합계도 같은 규칙이다.
     *
     * <p>합계는 mock 이라 값으로는 못 본다 — 대신 질의에 넘기는 <b>상한</b>을 본다.
     * 회차 끝이 아니라 지금으로 조여 있어야 한다.
     */
    @Nested
    @DisplayName("청구는 지금까지만 센다(D1)")
    class CountUntilNow {

        private final LocalDateTime NOW = LocalDate.of(2026, 9, 18).atTime(10, 30);

        private org.mockito.ArgumentCaptor<Object> givenCycleCapturingBounds() {
            @SuppressWarnings("unchecked")
            TypedQuery<Long> query = mock(TypedQuery.class);
            org.mockito.ArgumentCaptor<Object> values =
                org.mockito.ArgumentCaptor.forClass(Object.class);
            lenient().when(entityManager.createQuery(anyString(), eq(Long.class)))
                .thenReturn(query);
            lenient().when(query.setParameter(anyString(), values.capture()))
                .thenReturn(query);
            lenient().when(query.getSingleResult()).thenReturn(0L);
            givenInstallments();
            return values;
        }

        private void givenCard() {
            Asset card = creditCard(12);
            given(assetRepository.findById(CARD_ID)).willReturn(Optional.of(card));
            given(cardBillingRepository.findByCardAssetRowId(CARD_ID)).willReturn(List.of());
            given(cardBillingRepository.sumCompletedAmountByCardAndPeriod(eq(CARD_ID), any(), any()))
                .willReturn(0L);
            doReturn(NOW.toLocalDate()).when(serviceClock).today();
            doReturn(NOW).when(userClock).now(USER_ID);
        }

        @Test
        @DisplayName("진행 중인 회차의 상한은 회차 끝이 아니라 지금이다")
        void clampsUpperBoundToNow() {
            givenCard();
            org.mockito.ArgumentCaptor<Object> values = givenCycleCapturingBounds();

            sut.getCardBilling(CARD_ID, USER_ID);

            // 이번 회차 9/1~9/30 — 상한이 9/30 23:59 로 나가면 미래 거래까지 센다.
            assertThat(values.getAllValues())
                .as("상한이 지금(%s)으로 조여 있어야 한다", NOW)
                .contains(NOW)
                .doesNotContain(LocalDate.of(2026, 9, 30).atTime(LocalTime.MAX));
        }

        /** 이미 다 지난 회차는 조일 것이 없다 — 기간 끝이 그대로 상한이다. */
        @Test
        @DisplayName("지난 회차는 기간 끝이 그대로 상한")
        void pastCycleKeepsPeriodEnd() {
            givenCard();
            org.mockito.ArgumentCaptor<Object> values = givenCycleCapturingBounds();

            sut.getCardBilling(CARD_ID, USER_ID);

            // 다음 회차(10/12 결제 → 9/1~9/30)의 앞 회차는 8/1~8/31 이 아니라, 여기서는
            // nextCycle(11/12 결제 → 10/1~10/31)이 통째로 미래다. 그 회차의 상한도 지금이다.
            assertThat(values.getAllValues()).contains(NOW);
        }

        /** 회차가 통째로 미래면 셀 것이 없다 — 질의 자체를 건너뛴다. */
        @Test
        @DisplayName("통째로 미래인 회차는 0 이다")
        void wholeFutureCycleIsZero() {
            givenCard();
            givenCycleCapturingBounds();

            CardPaymentServiceDto.CardBillingInfo info = sut.getCardBilling(CARD_ID, USER_ID);

            // nextCycle = 11/12 결제 → 10/1~10/31, 지금은 9/18 이라 전부 미래다.
            assertThat(info.nextCycle().lumpSumAmount()).isZero();
            assertThat(info.nextCycle().amount()).isZero();
        }
    }

}
