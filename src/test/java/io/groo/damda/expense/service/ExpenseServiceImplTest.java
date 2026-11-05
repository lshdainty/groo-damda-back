package io.groo.damda.expense.service;

import io.groo.damda.common.patch.Patch;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.core.type.YNType;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.asset.service.AssetBalanceHistoryService;
import io.groo.damda.calendar.repository.CalendarEventRepository;
import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.domain.ExpenseBudget;
import io.groo.damda.expense.domain.ExpenseCategory;
import io.groo.damda.expense.domain.ExpenseSplit;
import io.groo.damda.expense.domain.ExpenseSplit;
import io.groo.damda.expense.repository.ExpenseBudgetRepository;
import io.groo.damda.expense.repository.ExpenseCategoryRepository;
import io.groo.damda.expense.repository.ExpenseRepository;
import io.groo.damda.expense.repository.ExpenseSplitRepository;
import io.groo.damda.expense.service.dto.ExpenseServiceDto;
import io.groo.damda.expense.service.dto.ExpenseSplitServiceDto;
import io.groo.damda.card.service.CardPaymentService;
import io.groo.damda.card.service.dto.CardPaymentServiceDto;
import io.groo.damda.asset.service.AssetService;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.notification.service.NotificationMessages;
import io.groo.damda.notification.service.NotificationService;
import io.groo.damda.notification.service.dto.NotificationServiceDto;
import io.groo.damda.support.message.TestMessages;
import io.groo.damda.todo.repository.TodoRepository;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import io.groo.damda.user.service.UserService;
import io.groo.damda.card.service.PaymentSchedule;
import io.groo.damda.card.service.PaymentScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import io.groo.core.time.ServiceClock;
import io.groo.core.time.UserClock;

/**
 * 거래(Expense) 정책 회귀 방지 단위 테스트.
 *
 * <p>핵심 정책: 거래는 leaf 카테고리(자식 없는)에만 — 자식 보유(상위) 카테고리는 거래 불가.
 * + 카테고리/거래 소유권 검증.
 */
@ExtendWith(MockitoExtension.class)
class ExpenseServiceImplTest {

    @Mock private ExpenseRepository expenseRepository;
    @Mock private ExpenseCategoryRepository expenseCategoryRepository;
    @Mock private ExpenseBudgetRepository expenseBudgetRepository;
    @Mock private ExpenseSplitRepository expenseSplitRepository;
    @Mock private ExpenseSplitService expenseSplitService;
    @Mock private NotificationService notificationService;
    @Mock private UserService userService;
    @Mock private AssetRepository assetRepository;
    @Mock private AssetBalanceHistoryService balanceHistoryService;
    @Mock private CalendarEventRepository calendarEventRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private UserRepository userRepository;
    @Mock private CardPaymentService cardPaymentService;
    @Mock private AssetService assetService;
    @Mock private PaymentScheduleService paymentScheduleService;
    // 닫힌 회차 판정의 "오늘"(D11) — 7월 회차가 아직 열려 있는 날(7월분 결제일 8/12 전)로 고정한다.
    @Mock private ServiceClock serviceClock;
    @Mock private jakarta.persistence.EntityManager entityManager;
    // 날짜 판정용 — mock 이면 null 이 흘러 NPE. 실물을 주입하되 사용자 조회는 비어
    // 서비스 기준(Asia/Seoul)으로 폴백한다.
    @Spy private UserClock userClock = new UserClock(rowId -> null, new ServiceClock("Asia/Seoul"));
    // 알림 문구는 DB 에 굳는다 — mock 을 주면 저장되는 문장을 아무것도 안 지킨다. 실물 번들을 읽는다.
    @Spy private NotificationMessages notificationMessages = TestMessages.notificationMessages();

    @InjectMocks private ExpenseServiceImpl sut;

    private static final long USER_ID = 1L;
    private static final LocalDate CYCLE_TODAY = LocalDate.of(2026, 7, 15);

    /** 신용카드는 지금 결제일 하나로 모든 회차를 센다 — 이력 조회는 카드 서비스 테스트가 본다. */
    @BeforeEach
    void cardSchedule() {
        lenient().when(serviceClock.today()).thenReturn(CYCLE_TODAY);
        lenient().when(paymentScheduleService.scheduleOf(any())).thenAnswer(inv -> {
            Asset a = inv.getArgument(0);
            return a != null && a.getAssetType() == AssetType.CREDIT_CARD
                ? PaymentSchedule.of(a.getPaymentDay()) : null;
        });
    }

    private User user(long rowId) {
        User u = User.createUser(null, "tester", "테스터", "tester@porest.com");
        ReflectionTestUtils.setField(u, "rowId", rowId);
        return u;
    }

    private ExpenseCategory category(long rowId, User owner) {
        ExpenseCategory c = ExpenseCategory.createCategory(owner, "식비", "tag", "#fff", ExpenseType.EXPENSE, null);
        ReflectionTestUtils.setField(c, "rowId", rowId);
        return c;
    }

    private ExpenseServiceDto.CreateCommand createCmd(long categoryRowId) {
        return new ExpenseServiceDto.CreateCommand(
                USER_ID, categoryRowId, null, ExpenseType.EXPENSE, 10_000L,
                "점심", LocalDateTime.of(2026, 6, 1, 12, 0), "식당", "CARD", null,
            null,
            null,
            null, null, null);
    }

    private ExpenseServiceDto.UpdateCommand updateCmd(long categoryRowId) {
        return new ExpenseServiceDto.UpdateCommand(
                Patch.set(categoryRowId), Patch.absent(), Patch.set(ExpenseType.EXPENSE), Patch.set(10_000L),
                Patch.set("점심"), Patch.set(LocalDateTime.of(2026, 6, 1, 12, 0)), Patch.set("식당"), Patch.set("CARD"), Patch.absent(),
            Patch.absent(),
            Patch.absent(),
            Patch.absent(), Patch.absent(), Patch.absent(), null);
    }

    @Test
    @DisplayName("createExpense — 자식 보유(상위) 카테고리에는 거래 불가")
    void createRejectsNonLeafCategory() {
        User u = user(USER_ID);
        ExpenseCategory parent = category(10L, u);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(parent));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(true);

        assertThatThrownBy(() -> sut.createExpense(createCmd(10L)))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("createExpense — 남의 카테고리에는 거래 불가")
    void createRejectsOthersCategory() {
        User u = user(USER_ID);
        ExpenseCategory othersCategory = category(20L, user(999L));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(20L)).willReturn(Optional.of(othersCategory));

        assertThatThrownBy(() -> sut.createExpense(createCmd(20L)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("createExpense — 거래 유형 ≠ 카테고리 유형이면 거부(타입 일치 강제)")
    void createRejectsTypeMismatch() {
        User u = user(USER_ID);
        ExpenseCategory expenseCat = category(10L, u); // EXPENSE 타입 카테고리
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(expenseCat));

        // INCOME 거래를 EXPENSE 카테고리에 등록 시도
        var cmd = new ExpenseServiceDto.CreateCommand(
                USER_ID, 10L, null, ExpenseType.INCOME, 10_000L,
                "x", LocalDateTime.of(2026, 6, 1, 12, 0), null, null, null,
            null,
            null,
            null, null, null);

        assertThatThrownBy(() -> sut.createExpense(cmd))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("updateExpense — 자식 보유(상위) 카테고리로 변경 불가")
    void updateRejectsNonLeafCategory() {
        User u = user(USER_ID);
        Expense expense = mock(Expense.class);
        given(expense.getUser()).willReturn(u);
        ExpenseCategory parent = category(30L, u);
        given(expenseRepository.findById(5L)).willReturn(Optional.of(expense));
        given(expenseCategoryRepository.findById(30L)).willReturn(Optional.of(parent));
        given(expenseCategoryRepository.hasChildren(30L)).willReturn(true);

        assertThatThrownBy(() -> sut.updateExpense(5L, USER_ID, updateCmd(30L)))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("updateExpense — 남의 거래는 수정 불가")
    void updateRejectsOthersExpense() {
        Expense expense = mock(Expense.class);
        given(expense.getUser()).willReturn(user(999L));
        given(expenseRepository.findById(5L)).willReturn(Optional.of(expense));

        assertThatThrownBy(() -> sut.updateExpense(5L, USER_ID, updateCmd(30L)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("updateExpense — 남의 자산으로 변경 불가(소유권 검증 누락 보강)")
    void updateRejectsOthersAsset() {
        User u = user(USER_ID);
        Expense expense = mock(Expense.class);
        given(expense.getUser()).willReturn(u);
        ExpenseCategory leaf = category(10L, u);
        given(expenseRepository.findById(5L)).willReturn(Optional.of(expense));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        Asset othersAsset = mock(Asset.class);
        given(othersAsset.getUser()).willReturn(user(999L));
        given(assetRepository.findById(20L)).willReturn(Optional.of(othersAsset));

        var cmd = new ExpenseServiceDto.UpdateCommand(
                Patch.set(10L), Patch.set(20L), Patch.set(ExpenseType.EXPENSE), Patch.set(10_000L),
                Patch.set("x"), Patch.set(LocalDateTime.of(2026, 6, 1, 12, 0)), Patch.set("식당"), Patch.set("CARD"), Patch.absent(),
            Patch.absent(),
            Patch.absent(),
            Patch.absent(), Patch.absent(), Patch.absent(), null);

        assertThatThrownBy(() -> sut.updateExpense(5L, USER_ID, cmd))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("getExpenses — 시작일이 종료일보다 늦으면 거부(역전 범위 조용한 빈결과 방지)")
    void getExpensesRejectsInvertedDateRange() {
        assertThatThrownBy(() -> sut.getExpenses(USER_ID, null, null, null,
                LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 1)))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("getRangeSummary — 시작일이 종료일보다 늦으면 거부")
    void getRangeSummaryRejectsInvertedDateRange() {
        assertThatThrownBy(() -> sut.getRangeSummary(USER_ID,
                LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 1)))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("getRangeSummary — 자식 카테고리가 다른 부모로 이동해도 합계는 leaf 기준으로 정확하다(부모 라벨만 변경)")
    void rangeSummaryAggregatesByLeafRegardlessOfParent() {
        User u = user(USER_ID);
        ExpenseCategory parent1 = ExpenseCategory.createCategory(u, "부모1", "t", "#fff", ExpenseType.EXPENSE, null);
        ReflectionTestUtils.setField(parent1, "rowId", 100L);
        ExpenseCategory parent2 = ExpenseCategory.createCategory(u, "부모2", "t", "#fff", ExpenseType.EXPENSE, null);
        ReflectionTestUtils.setField(parent2, "rowId", 200L);
        ExpenseCategory child = ExpenseCategory.createCategory(u, "식비", "t", "#fff", ExpenseType.EXPENSE, parent1);
        ReflectionTestUtils.setField(child, "rowId", 10L);

        Expense e1 = Expense.createExpense(u, child, null, ExpenseType.EXPENSE, 3_000L, "a",
                LocalDateTime.of(2026, 6, 1, 12, 0), null, null, null,
            null,
            null,
            null);
        ReflectionTestUtils.setField(e1, "rowId", 1L);
        Expense e2 = Expense.createExpense(u, child, null, ExpenseType.EXPENSE, 2_000L, "b",
                LocalDateTime.of(2026, 6, 2, 12, 0), null, null, null,
            null,
            null,
            null);
        ReflectionTestUtils.setField(e2, "rowId", 2L);

        LocalDate start = LocalDate.of(2026, 6, 1);
        LocalDate end = LocalDate.of(2026, 6, 30);
        given(expenseRepository.findByDateRange(USER_ID, start, end, null)).willReturn(List.of(e1, e2));
        given(expenseSplitRepository.findByExpenseIds(any())).willReturn(List.of());

        // 부모1 산하일 때
        var before = sut.getRangeSummary(USER_ID, start, end);
        assertThat(before.totalExpense()).isEqualTo(5_000L);
        assertThat(before.categoryBreakdown()).hasSize(1);
        var b0 = before.categoryBreakdown().get(0);
        assertThat(b0.categoryRowId()).isEqualTo(10L);
        assertThat(b0.totalAmount()).isEqualTo(5_000L);
        assertThat(b0.parentCategoryRowId()).isEqualTo(100L);

        // 부모2 산하로 이동 — 합계/leaf 집계는 그대로, 부모 라벨만 변경(이중계상·누락 없음)
        child.moveParent(parent2);
        var after = sut.getRangeSummary(USER_ID, start, end);
        assertThat(after.totalExpense()).isEqualTo(5_000L);
        assertThat(after.categoryBreakdown()).hasSize(1);
        var a0 = after.categoryBreakdown().get(0);
        assertThat(a0.categoryRowId()).isEqualTo(10L);
        assertThat(a0.totalAmount()).isEqualTo(5_000L);
        assertThat(a0.parentCategoryRowId()).isEqualTo(200L);
    }

    @Test
    @DisplayName("updateExpense — 금액 상향으로 예산 임계를 넘으면 알림이 발생한다(수정 경로 알림 누락 보강)")
    void updateCrossingBudgetThresholdNotifies() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        // 수정 전 1,000원 거래(예산 10,000 중 10%)
        Expense expense = Expense.createExpense(u, leaf, null, ExpenseType.EXPENSE, 1_000L,
                "x", LocalDateTime.of(2026, 6, 1, 12, 0), null, null, null,
            null,
            null,
            null);
        ReflectionTestUtils.setField(expense, "rowId", 5L);
        given(expenseRepository.findById(5L)).willReturn(Optional.of(expense));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);

        // 전체 예산(카테고리 null) 10,000원, warn 임계 85%
        ExpenseBudget budget = mock(ExpenseBudget.class);
        given(budget.getBudgetAmount()).willReturn(10_000L);
        given(budget.getCategory()).willReturn(null);
        given(budget.getRowId()).willReturn(1L);
        given(expenseBudgetRepository.findByUser(eq(USER_ID), eq(2026), eq(6))).willReturn(List.of(budget));
        given(userService.getBudgetAlertThreshold(USER_ID)).willReturn(85);
        // 월간 집계: 수정된 이 거래(9,900)만 존재 → 99% 사용
        given(expenseRepository.findByDateRange(eq(USER_ID), any(), any())).willReturn(List.of(expense));

        // 1,000 → 9,900 으로 상향 수정
        var cmd = new ExpenseServiceDto.UpdateCommand(
                Patch.set(10L), Patch.absent(), Patch.set(ExpenseType.EXPENSE), Patch.set(9_900L),
                Patch.set("x"), Patch.set(LocalDateTime.of(2026, 6, 1, 12, 0)), Patch.absent(), Patch.absent(), Patch.absent(),
            Patch.absent(),
            Patch.absent(),
            Patch.absent(), Patch.absent(), Patch.absent(), null);

        sut.updateExpense(5L, USER_ID, cmd);

        verify(notificationService).createNotification(any());
    }

    @Test
    @DisplayName("updateExpense — 고정 칸을 안 보내면 지금 표식을 유지한다(옛 앱의 수정이 표식을 지우지 않는다)")
    void updateWithoutFixedKeepsIt() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        Expense expense = Expense.createExpense(u, leaf, null, ExpenseType.EXPENSE, 700_000L,
                "월세", LocalDateTime.of(2026, 6, 1, 12, 0), null, null, null, null, null, null);
        expense.markFixed(true);
        ReflectionTestUtils.setField(expense, "rowId", 5L);
        given(expenseRepository.findById(5L)).willReturn(Optional.of(expense));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);

        sut.updateExpense(5L, USER_ID, updateCmd(10L));

        assertThat(expense.isFixedTx()).isTrue();
    }

    @Test
    @DisplayName("updateExpense — 고정 칸을 보내면 그 값으로 바뀌고, null 을 보내면 변동으로 돌아간다")
    void updateWithFixedChangesIt() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        Expense expense = Expense.createExpense(u, leaf, null, ExpenseType.EXPENSE, 700_000L,
                "월세", LocalDateTime.of(2026, 6, 1, 12, 0), null, null, null, null, null, null);
        ReflectionTestUtils.setField(expense, "rowId", 5L);
        given(expenseRepository.findById(5L)).willReturn(Optional.of(expense));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);

        sut.updateExpense(5L, USER_ID, updateCmdFixed(10L, Patch.set(true)));
        assertThat(expense.isFixedTx()).isTrue();

        sut.updateExpense(5L, USER_ID, updateCmdFixed(10L, Patch.set(null)));
        assertThat(expense.isFixedTx()).isFalse();
    }

    private ExpenseServiceDto.UpdateCommand updateCmdFixed(long categoryId, Patch<Boolean> fixed) {
        return new ExpenseServiceDto.UpdateCommand(
                Patch.set(categoryId), Patch.absent(), Patch.set(ExpenseType.EXPENSE), Patch.set(700_000L),
                Patch.set("월세"), Patch.set(LocalDateTime.of(2026, 6, 1, 12, 0)), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(),
                null, false, fixed);
    }

    // ── 자산 잔액 이력 연동(잔액 정확성 와이어링) ─────────────────────────────
    @Test
    @DisplayName("updateExpense — 잔액 이력은 removeExpense 먼저, 그 다음 recordExpense 순서로 재적재")
    void updateExpenseRemoveThenRecordInOrder() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        Expense expense = Expense.createExpense(u, leaf, null, ExpenseType.EXPENSE, 1_000L,
                "x", LocalDateTime.of(2026, 6, 1, 12, 0), null, null, null,
            null,
            null,
            null);
        ReflectionTestUtils.setField(expense, "rowId", 5L);
        given(expenseRepository.findById(5L)).willReturn(Optional.of(expense));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);

        sut.updateExpense(5L, USER_ID, updateCmd(10L));

        InOrder ord = inOrder(balanceHistoryService);
        ord.verify(balanceHistoryService).removeExpense(5L);                       // 먼저 기존 flow 제거
        ord.verify(balanceHistoryService).recordExpenseKrw(any(), eq(5L), any(), any(), any(), any(), any()); // 그 다음 재적재
    }

    @Test
    @DisplayName("deleteExpense — removeExpense 만 호출(recordExpense 없음)")
    void deleteExpenseRemovesOnly() {
        Expense expense = mock(Expense.class);
        given(expense.getUser()).willReturn(user(USER_ID));
        given(expenseRepository.findById(5L)).willReturn(Optional.of(expense));

        sut.deleteExpense(5L, USER_ID);

        verify(balanceHistoryService).removeExpense(5L);
        verify(balanceHistoryService, never()).recordExpenseKrw(any(), any(), any(), any(), any(), any(), any());
    }

    @Nested
    @DisplayName("일괄 삭제")
    class BulkDelete {

        private Expense mine(long rowId) {
            Expense e = mock(Expense.class);
            given(e.getUser()).willReturn(user(USER_ID));
            given(expenseRepository.findById(rowId)).willReturn(Optional.of(e));
            return e;
        }

        @Test
        @DisplayName("단건과 같은 본문 — 건마다 삭제 표식 · 잔액 이력 제거 · 분할 제거, 중복 아이디는 한 번만")
        void deletesEachOnce() {
            Expense a = mine(1L);
            Expense b = mine(2L);

            var result = sut.bulkDeleteExpenses(new ExpenseServiceDto.BulkDeleteCommand(USER_ID, List.of(1L, 2L, 1L)));

            assertThat(result.deletedCount()).isEqualTo(2);
            assertThat(result.refundedAmount()).isNull();
            then(a).should().deleteExpense();
            then(b).should().deleteExpense();
            then(balanceHistoryService).should().removeExpense(1L);
            then(balanceHistoryService).should().removeExpense(2L);
            then(expenseSplitRepository).should().findByExpense(1L);
            then(expenseSplitRepository).should().findByExpense(2L);
        }

        @Test
        @DisplayName("환급액은 건마다 난 값의 합 — 카드 거래 둘의 환급을 더한다")
        void sumsRefunds() {
            Asset cardAsset = creditCard(9L);
            for (long id : new long[] {1L, 2L}) {
                Expense e = Expense.createExpense(user(USER_ID), null, cardAsset, ExpenseType.EXPENSE, 50_000L,
                    "구매", LocalDateTime.of(2026, 7, 10, 12, 0), "무신사", "CARD", null, null, null, null);
                ReflectionTestUtils.setField(e, "rowId", id);
                given(expenseRepository.findById(id)).willReturn(Optional.of(e));
            }
            given(cardPaymentService.settleExpenseChange(any())).willReturn(
                new CardPaymentServiceDto.SettlementResult(88L, 50_000L, null, 0L),
                new CardPaymentServiceDto.SettlementResult(89L, 30_000L, null, 0L));

            var result = sut.bulkDeleteExpenses(new ExpenseServiceDto.BulkDeleteCommand(USER_ID, List.of(1L, 2L)));

            assertThat(result.refundedAmount()).isEqualTo(80_000L);
            then(cardPaymentService).should(times(2)).settleExpenseChange(any());
        }

        @Test
        @DisplayName("없음 · 남의 것 · 시스템 거래가 하나라도 있으면 아무것도 지우지 않고 단건과 같은 오류를 던진다")
        void rejectsBeforeDeletingAnything() {
            Expense ok = mine(1L);
            given(expenseRepository.findById(2L)).willReturn(Optional.empty());
            assertThatThrownBy(() -> sut.bulkDeleteExpenses(
                new ExpenseServiceDto.BulkDeleteCommand(USER_ID, List.of(1L, 2L))))
                .isInstanceOf(io.groo.core.exception.EntityNotFoundException.class);

            Expense theirs = mock(Expense.class);
            given(theirs.getUser()).willReturn(user(99L));
            given(expenseRepository.findById(3L)).willReturn(Optional.of(theirs));
            assertThatThrownBy(() -> sut.bulkDeleteExpenses(
                new ExpenseServiceDto.BulkDeleteCommand(USER_ID, List.of(1L, 3L))))
                .isInstanceOf(ForbiddenException.class);

            Expense auto = mine(4L);
            given(auto.isAutoGenerated()).willReturn(true);
            assertThatThrownBy(() -> sut.bulkDeleteExpenses(
                new ExpenseServiceDto.BulkDeleteCommand(USER_ID, List.of(1L, 4L))))
                .isInstanceOf(InvalidValueException.class);

            then(ok).should(never()).deleteExpense();
            then(balanceHistoryService).should(never()).removeExpense(any());
        }
    }

    // ── 예산 임계 경계 (정상 동작 정확성) ─────────────────────────────
    private ExpenseServiceDto.CreateCommand createCmdAmount(long categoryRowId, long amount) {
        return new ExpenseServiceDto.CreateCommand(
                USER_ID, categoryRowId, null, ExpenseType.EXPENSE, amount,
                "x", LocalDateTime.of(2026, 6, 1, 12, 0), null, null, null,
            null,
            null,
            null, null, null);
    }

    private void givenOverallBudget(long limit, int warnPct, User u, ExpenseCategory leaf, long monthlyTotal) {
        // 실제 ExpenseBudget(전체, category=null) 사용 — mock 불필요 스터빙 회피
        ExpenseBudget budget = ExpenseBudget.createBudget(u, null, limit, 2026, 6);
        given(expenseBudgetRepository.findByUser(eq(USER_ID), eq(2026), eq(6))).willReturn(List.of(budget));
        given(userService.getBudgetAlertThreshold(USER_ID)).willReturn(warnPct);
        given(expenseRepository.findByDateRange(eq(USER_ID), any(), any())).willReturn(List.of(
                Expense.createExpense(u, leaf, null, ExpenseType.EXPENSE, monthlyTotal,
                        "x", LocalDateTime.of(2026, 6, 1, 12, 0), null, null, null,
            null,
            null,
            null)));
    }

    @Test
    @DisplayName("createExpense — 예산 알림을 껐으면(notify_budget=N) 임계를 넘겨도 알림이 없다")
    void createDoesNotNotifyWhenBudgetNotificationIsOff() {
        User u = user(USER_ID);
        // 앱 알림 설정 화면이 부르는 경로 그대로 예산 알림만 끈다.
        u.updateNotificationPreferences(null, null, false, null, null, null, null, null,
                null, null, null, null, null, null, null);
        ExpenseCategory leaf = category(10L, u);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        // 아래 셋은 "검사가 없었다면 알림이 실제로 터지는" 상황(9,900/10,000, 임계 85%)을 세워 둔 것이다.
        // 검사가 있으면 쓰이지 않으므로 lenient — 빼면 이 테스트는 검사를 되돌려도 통과한다
        // (예산이 없어서 조용한 것과 구분이 안 된다).
        lenient().when(expenseBudgetRepository.findByUser(eq(USER_ID), eq(2026), eq(6)))
                .thenReturn(List.of(ExpenseBudget.createBudget(u, null, 10_000L, 2026, 6)));
        lenient().when(userService.getBudgetAlertThreshold(USER_ID)).thenReturn(85);
        lenient().when(expenseRepository.findByDateRange(eq(USER_ID), any(), any()))
                .thenReturn(List.of(Expense.createExpense(u, leaf, null, ExpenseType.EXPENSE, 9_900L,
                        "x", LocalDateTime.of(2026, 6, 1, 12, 0), null, null, null,
                        null, null, null)));

        var info = sut.createExpense(createCmdAmount(10L, 9_900L));

        verify(notificationService, never()).createNotification(any());
        // 막는 건 알림뿐이다 — 거래는 종전대로 저장된다.
        verify(expenseRepository).save(any(Expense.class));
        assertThat(info).isNotNull();
    }

    @Test
    @DisplayName("createExpense — 정확히 85%(8,500/10,000)에서 warn 알림 발생")
    void createWarnAtExactly85() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        givenOverallBudget(10_000L, 85, u, leaf, 8_500L);

        sut.createExpense(createCmdAmount(10L, 8_500L));

        verify(notificationService, times(1)).createNotification(any()); // 0→0.85 돌파
    }

    @Test
    @DisplayName("createExpense — 84.99%(8,499)에서는 알림 없음(경계 1원 미달)")
    void createNoAlertJustBelow85() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        givenOverallBudget(10_000L, 85, u, leaf, 8_499L);

        sut.createExpense(createCmdAmount(10L, 8_499L));

        verify(notificationService, never()).createNotification(any()); // 0.8499 < 0.85
    }

    @Test
    @DisplayName("createExpense — 99.5%(9,950) WARN 라벨은 '100%' 아닌 '99%' 로 표기(반올림 cap)")
    void createWarnLabelCappedAt99() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        givenOverallBudget(10_000L, 85, u, leaf, 9_950L); // 99.5% — OVER 아님(<100%)

        sut.createExpense(createCmdAmount(10L, 9_950L));

        ArgumentCaptor<NotificationServiceDto.CreateCommand> captor =
                ArgumentCaptor.forClass(NotificationServiceDto.CreateCommand.class);
        verify(notificationService).createNotification(captor.capture());
        assertThat(captor.getValue().title()).contains("99%").doesNotContain("100%");
    }

    @Test
    @DisplayName("createExpense — WARN 알림에 저장되는 문장(번들·`~어요`, 스케줄러와 같은 키)")
    void createWarnStoresTheSharedSentence() {
        // QA 2026-09-04 #76. 고치기 전 실측: "전체 예산 90% 사용" / "전체 예산의 90%를 사용했어요 (9,000 / 10,000원)."
        // 같은 상황을 스케줄러는 "전체 예산 초과 경고" / "…카테고리 예산의 90%를 사용했습니다." 로 알렸다.
        LocaleContextHolder.resetLocaleContext();
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        givenOverallBudget(10_000L, 85, u, leaf, 9_000L);

        sut.createExpense(createCmdAmount(10L, 9_000L));

        ArgumentCaptor<NotificationServiceDto.CreateCommand> captor =
                ArgumentCaptor.forClass(NotificationServiceDto.CreateCommand.class);
        verify(notificationService).createNotification(captor.capture());
        // 카테고리 없는(월 전체) 예산의 이름도 번들에서 온다 — 코드에 박혀 있던 "전체" 였다.
        assertThat(captor.getValue().title()).isEqualTo("전체 예산 90% 사용");
        assertThat(captor.getValue().message()).isEqualTo("전체 예산의 90%를 사용했어요 (9,000 / 10,000원)");
    }

    @Test
    @DisplayName("createExpense — OVER 알림에 저장되는 문장(번들, 스케줄러와 같은 키)")
    void createOverStoresTheSharedSentence() {
        // 고치기 전 실측: "전체 예산 초과" / "전체 예산 10,000원을 초과했어요 (현재 10,000원)."
        LocaleContextHolder.resetLocaleContext();
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        givenOverallBudget(10_000L, 85, u, leaf, 10_000L);

        sut.createExpense(createCmdAmount(10L, 10_000L));

        ArgumentCaptor<NotificationServiceDto.CreateCommand> captor =
                ArgumentCaptor.forClass(NotificationServiceDto.CreateCommand.class);
        verify(notificationService).createNotification(captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("전체 예산 초과");
        assertThat(captor.getValue().message()).isEqualTo("전체 예산 10,000원을 초과했어요 (현재 10,000원)");
    }

    @Test
    @DisplayName("createExpense — 요청 로케일이 en 이면 그 언어로 굳는다(알림은 저장 시점 언어다)")
    void createStoresTheNotificationInTheRequestLocale() {
        // 알림은 DB 에 문자열로 굳어 나중에 다시 렌더할 수 없다 — 그래서 "저장 시점의 언어" 가 전부다.
        // 요청 경로에서는 그 언어가 Accept-Language 다(스케줄러엔 없다 — NotificationMessages 주석 참고).
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        try {
            User u = user(USER_ID);
            ExpenseCategory leaf = category(10L, u);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
            given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
            given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
            givenOverallBudget(10_000L, 85, u, leaf, 10_000L);

            sut.createExpense(createCmdAmount(10L, 10_000L));

            ArgumentCaptor<NotificationServiceDto.CreateCommand> captor =
                    ArgumentCaptor.forClass(NotificationServiceDto.CreateCommand.class);
            verify(notificationService).createNotification(captor.capture());
            assertThat(captor.getValue().title()).isEqualTo("All budget exceeded");
            assertThat(captor.getValue().message())
                    .isEqualTo("You went over the All budget of 10,000 won (now 10,000 won)");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    @DisplayName("createExpense — 0→100% 점프 시 OVER 우선(알림 정확히 1건, WARN 중복 아님)")
    void createOverPriorityAt100() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        givenOverallBudget(10_000L, 85, u, leaf, 10_000L);

        sut.createExpense(createCmdAmount(10L, 10_000L));

        // if(OVER) else-if(WARN) → 85·100 동시 초과여도 알림은 1건만(OVER)
        verify(notificationService, times(1)).createNotification(any());
    }

    @Test
    @DisplayName("updateExpense — 99.99%(9,900→9,999)에서는 초과 알림 없음")
    void updateNoOverJustBelow100() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        Expense expense = Expense.createExpense(u, leaf, null, ExpenseType.EXPENSE, 9_900L,
                "x", LocalDateTime.of(2026, 6, 1, 12, 0), null, null, null,
            null,
            null,
            null);
        ReflectionTestUtils.setField(expense, "rowId", 5L);
        given(expenseRepository.findById(5L)).willReturn(Optional.of(expense));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        ExpenseBudget budget = ExpenseBudget.createBudget(u, null, 10_000L, 2026, 6);
        given(expenseBudgetRepository.findByUser(eq(USER_ID), eq(2026), eq(6))).willReturn(List.of(budget));
        given(userService.getBudgetAlertThreshold(USER_ID)).willReturn(85);
        given(expenseRepository.findByDateRange(eq(USER_ID), any(), any())).willReturn(List.of(expense));

        var cmd = new ExpenseServiceDto.UpdateCommand(
                Patch.set(10L), Patch.absent(), Patch.set(ExpenseType.EXPENSE), Patch.set(9_999L),
                Patch.set("x"), Patch.set(LocalDateTime.of(2026, 6, 1, 12, 0)), Patch.absent(), Patch.absent(), Patch.absent(),
            Patch.absent(),
            Patch.absent(),
            Patch.absent(), Patch.absent(), Patch.absent(), null);
        sut.updateExpense(5L, USER_ID, cmd);

        // before 0.99, after 0.9999: OVER(>=1.0) 거짓, WARN(이미 0.99>=0.85) 미돌파 → 알림 없음
        verify(notificationService, never()).createNotification(any());
    }

    @Test
    @DisplayName("updateExpense — 정확히 100%(9,900→10,000)에서 초과 알림 발생")
    void updateOverAtExactly100() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        Expense expense = Expense.createExpense(u, leaf, null, ExpenseType.EXPENSE, 9_900L,
                "x", LocalDateTime.of(2026, 6, 1, 12, 0), null, null, null,
            null,
            null,
            null);
        ReflectionTestUtils.setField(expense, "rowId", 5L);
        given(expenseRepository.findById(5L)).willReturn(Optional.of(expense));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        ExpenseBudget budget = ExpenseBudget.createBudget(u, null, 10_000L, 2026, 6);
        given(expenseBudgetRepository.findByUser(eq(USER_ID), eq(2026), eq(6))).willReturn(List.of(budget));
        given(userService.getBudgetAlertThreshold(USER_ID)).willReturn(85);
        given(expenseRepository.findByDateRange(eq(USER_ID), any(), any())).willReturn(List.of(expense));

        var cmd = new ExpenseServiceDto.UpdateCommand(
                Patch.set(10L), Patch.absent(), Patch.set(ExpenseType.EXPENSE), Patch.set(10_000L),
                Patch.set("x"), Patch.set(LocalDateTime.of(2026, 6, 1, 12, 0)), Patch.absent(), Patch.absent(), Patch.absent(),
            Patch.absent(),
            Patch.absent(),
            Patch.absent(), Patch.absent(), Patch.absent(), null);
        sut.updateExpense(5L, USER_ID, cmd);

        verify(notificationService, times(1)).createNotification(any()); // before 0.99<1.0, after 1.0>=1.0 → OVER
    }

    @Test
    @DisplayName("createExpense — 성공 시 거래를 저장하고 자산 잔액 이력을 기록한다")
    void createPersistsAndRecordsBalance() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        given(expenseBudgetRepository.findByUser(eq(USER_ID), anyInt(), anyInt())).willReturn(List.of());

        var info = sut.createExpense(createCmd(10L));

        assertThat(info.amount()).isEqualTo(10_000L);
        verify(expenseRepository).save(any(Expense.class));
        verify(balanceHistoryService).recordExpenseKrw(any(), any(), eq(ExpenseType.EXPENSE), eq(10_000L), any(), any(), any());
    }

    @Test
    @DisplayName("createExpense 해외 결제 — 원화 금액과 함께 원 통화 금액 · 통화를 잔액 이력에 넘긴다(계좌 통화 환산 재료)")
    void createPassesOriginalCurrencyToBalance() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        given(expenseBudgetRepository.findByUser(eq(USER_ID), anyInt(), anyInt())).willReturn(List.of());

        sut.createExpense(new ExpenseServiceDto.CreateCommand(
                USER_ID, 10L, null, ExpenseType.EXPENSE, 6_500L,
                "커피", LocalDateTime.of(2026, 6, 1, 12, 0), "카페", "CARD", null,
                new java.math.BigDecimal("5"), "usd", new java.math.BigDecimal("1300"),
                null, null, null));

        // 통화는 거래가 대문자로 다듬은 값 — 계좌 통화와 비교된다.
        verify(balanceHistoryService).recordExpenseKrw(any(), any(), eq(ExpenseType.EXPENSE), eq(6_500L),
                eq(new java.math.BigDecimal("5")), eq("USD"), any());
    }

    @Test
    @DisplayName("getMonthlyTrend — 음수 months 는 거부")
    void getMonthlyTrendRejectsNegativeMonths() {
        assertThatThrownBy(() -> sut.getMonthlyTrend(USER_ID, -3))
                .isInstanceOf(InvalidValueException.class);
    }

    // ── 분할 합 일치화 (거래 금액 ↔ 분할 합 불변식) ─────────────────────────────
    private Expense expenseWithRowId(User u, ExpenseCategory leaf, long amount) {
        Expense expense = Expense.createExpense(u, leaf, null, ExpenseType.EXPENSE, amount,
                "x", LocalDateTime.of(2026, 6, 1, 12, 0), null, null, null,
            null,
            null,
            null);
        ReflectionTestUtils.setField(expense, "rowId", 5L);
        return expense;
    }

    private ExpenseServiceDto.UpdateCommand updateCmdWithSplits(
            long categoryRowId, long amount, List<ExpenseSplitServiceDto.SplitCommand> splits) {
        return new ExpenseServiceDto.UpdateCommand(
                Patch.set(categoryRowId), Patch.absent(), Patch.set(ExpenseType.EXPENSE), Patch.set(amount),
                Patch.set("x"), Patch.set(LocalDateTime.of(2026, 6, 1, 12, 0)), Patch.absent(), Patch.absent(), Patch.absent(),
            Patch.absent(),
            Patch.absent(),
            Patch.absent(), Patch.absent(), Patch.absent(), splits);
    }

    @Test
    @DisplayName("updateExpense — 금액을 바꿔 기존 분할 합과 어긋나는데 분할을 함께 안 보내면 거부")
    void updateRejectsAmountChangeDesyncingExistingSplits() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        Expense expense = expenseWithRowId(u, leaf, 10_000L);
        given(expenseRepository.findById(5L)).willReturn(Optional.of(expense));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        // 기존 분할 합 10,000 (6,000 + 4,000)
        given(expenseSplitRepository.findByExpense(5L)).willReturn(List.of(
                ExpenseSplit.create(expense, leaf, 6_000L, "a", 0),
                ExpenseSplit.create(expense, leaf, 4_000L, "b", 1)));

        // 금액을 20,000 으로 바꾸면서 분할 미전달(null) → 합 10,000 ≠ 20,000 → 거부
        assertThatThrownBy(() -> sut.updateExpense(5L, USER_ID, updateCmdWithSplits(10L, 20_000L, null)))
                .isInstanceOf(InvalidValueException.class);
        verify(expenseSplitService, never()).replaceSplits(any());
    }

    @Test
    @DisplayName("updateExpense — 분할 합이 새 금액과 일치하면(미전달) 그대로 허용")
    void updateAllowsWhenExistingSplitSumMatches() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        Expense expense = expenseWithRowId(u, leaf, 10_000L);
        given(expenseRepository.findById(5L)).willReturn(Optional.of(expense));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        given(expenseSplitRepository.findByExpense(5L)).willReturn(List.of(
                ExpenseSplit.create(expense, leaf, 6_000L, "a", 0),
                ExpenseSplit.create(expense, leaf, 4_000L, "b", 1)));

        // 금액 10,000 유지 → 분할 합 10,000 == 10,000 → 허용, 분할 교체 호출 없음
        var info = sut.updateExpense(5L, USER_ID, updateCmdWithSplits(10L, 10_000L, null));

        assertThat(info.amount()).isEqualTo(10_000L);
        verify(expenseSplitService, never()).replaceSplits(any());
    }

    @Test
    @DisplayName("updateExpense — 맞춘 분할을 함께 보내면 원자적으로 교체(replaceSplits 위임)")
    void updateWithReconciledSplitsReplacesAtomically() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        Expense expense = expenseWithRowId(u, leaf, 10_000L);
        given(expenseRepository.findById(5L)).willReturn(Optional.of(expense));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);

        // 20,000 으로 상향하면서 합이 20,000 인 분할을 함께 전달
        var splits = List.of(
                new ExpenseSplitServiceDto.SplitCommand(
            null,10L, 12_000L, "a", 0),
                new ExpenseSplitServiceDto.SplitCommand(
            null,10L, 8_000L, "b", 1));

        sut.updateExpense(5L, USER_ID, updateCmdWithSplits(10L, 20_000L, splits));

        ArgumentCaptor<ExpenseSplitServiceDto.ReplaceCommand> captor =
                ArgumentCaptor.forClass(ExpenseSplitServiceDto.ReplaceCommand.class);
        verify(expenseSplitService).replaceSplits(captor.capture());
        assertThat(captor.getValue().expenseRowId()).isEqualTo(5L);
        assertThat(captor.getValue().userRowId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().splits()).hasSize(2);
        // 합 검증은 replaceSplits 책임 → 위임만 확인(미전달 분기 가드 미진입)
        verify(expenseSplitRepository, never()).findByExpense(any());
    }

    @Test
    @DisplayName("updateExpense — 분할이 없는 거래는 금액만 바꿔도 통과(불변식 무관)")
    void updateWithoutExistingSplitsPasses() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        Expense expense = expenseWithRowId(u, leaf, 10_000L);
        given(expenseRepository.findById(5L)).willReturn(Optional.of(expense));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        given(expenseSplitRepository.findByExpense(5L)).willReturn(List.of()); // 분할 없음

        var info = sut.updateExpense(5L, USER_ID, updateCmdWithSplits(10L, 20_000L, null));

        assertThat(info.amount()).isEqualTo(20_000L);
        verify(expenseSplitService, never()).replaceSplits(any());
    }

    // ── split-aware 카테고리 집계 (B안: 분할은 분할 leaf+부모로 귀속) ─────────────
    private ExpenseCategory leafUnder(long rowId, User u, ExpenseCategory parent) {
        ExpenseCategory c = ExpenseCategory.createCategory(u, "leaf" + rowId, "t", "#fff", ExpenseType.EXPENSE, parent);
        ReflectionTestUtils.setField(c, "rowId", rowId);
        return c;
    }

    private ExpenseCategory parentCat(long rowId, User u, String name) {
        ExpenseCategory c = ExpenseCategory.createCategory(u, name, "t", "#fff", ExpenseType.EXPENSE, null);
        ReflectionTestUtils.setField(c, "rowId", rowId);
        return c;
    }

    @Test
    @DisplayName("getMonthlyExpenseSpendByCategory — 분할은 분할 카테고리 leaf+부모로 귀속(다른 부모 트리로 분산)")
    void monthlySpendIsSplitAwareWithParentRollup() {
        User u = user(USER_ID);
        ExpenseCategory p1 = parentCat(1L, u, "식비/음료");
        ExpenseCategory l2 = leafUnder(2L, u, p1);   // 식비
        ExpenseCategory p7 = parentCat(7L, u, "생활");
        ExpenseCategory l8 = leafUnder(8L, u, p7);   // 생활용품

        // A: 비분할 거래 5,000 (식비 l2)
        Expense a = Expense.createExpense(u, l2, null, ExpenseType.EXPENSE, 5_000L, "a",
                LocalDateTime.of(2026, 6, 3, 12, 0), null, null, null,
            null,
            null,
            null);
        ReflectionTestUtils.setField(a, "rowId", 100L);
        // B: 쿠팡 10,000, 분할 [생활용품 6,000 + 식비 4,000] (선언 카테고리는 l8)
        Expense b = Expense.createExpense(u, l8, null, ExpenseType.EXPENSE, 10_000L, "쿠팡",
                LocalDateTime.of(2026, 6, 5, 12, 0), null, null, null,
            null,
            null,
            null);
        ReflectionTestUtils.setField(b, "rowId", 200L);
        ExpenseSplit b1 = ExpenseSplit.create(b, l8, 6_000L, "생활", 0);
        ExpenseSplit b2 = ExpenseSplit.create(b, l2, 4_000L, "식비", 1);

        given(expenseRepository.findByDateRange(eq(USER_ID), any(), any())).willReturn(List.of(a, b));
        given(expenseSplitRepository.findByExpenseIds(any())).willReturn(List.of(b1, b2));

        Map<Long, Long> spend = sut.getMonthlyExpenseSpendByCategory(USER_ID, 2026, 6);

        // 식비 leaf(2) = A 5,000 + B분할 4,000 = 9,000 → 부모 식비/음료(1) 9,000
        // 생활용품 leaf(8) = B분할 6,000 → 부모 생활(7) 6,000
        assertThat(spend).containsEntry(2L, 9_000L).containsEntry(1L, 9_000L)
                .containsEntry(8L, 6_000L).containsEntry(7L, 6_000L)
                .hasSize(4);
    }

    @Test
    @DisplayName("updateExpense — 다른 카테고리로 분할하면 그 카테고리(부모) 예산 초과 알림 발생(분할 무시 회귀 방지)")
    void updateWithCrossCategorySplitAlertsSplitCategoryBudget() {
        User u = user(USER_ID);
        ExpenseCategory p1 = parentCat(1L, u, "식비/음료");
        ExpenseCategory l2 = leafUnder(2L, u, p1);   // 식비
        ExpenseCategory p7 = parentCat(7L, u, "생활");
        ExpenseCategory l8 = leafUnder(8L, u, p7);   // 생활용품(거래 선언 카테고리)

        // 거래: 선언 카테고리 생활용품(8), 10,000원, 분할 없음 상태에서 시작.
        Expense expense = Expense.createExpense(u, l8, null, ExpenseType.EXPENSE, 10_000L, "쿠팡",
                LocalDateTime.of(2026, 6, 5, 12, 0), null, null, null,
            null,
            null,
            null);
        ReflectionTestUtils.setField(expense, "rowId", 5L);
        given(expenseRepository.findById(5L)).willReturn(Optional.of(expense));
        given(expenseCategoryRepository.findById(8L)).willReturn(Optional.of(l8));
        given(expenseCategoryRepository.hasChildren(8L)).willReturn(false);

        // 식비/음료(부모 1) 예산 4,000원, warn 85%
        ExpenseBudget budget = ExpenseBudget.createBudget(u, p1, 4_000L, 2026, 6);
        ReflectionTestUtils.setField(budget, "rowId", 1L);
        given(expenseBudgetRepository.findByUser(eq(USER_ID), eq(2026), eq(6))).willReturn(List.of(budget));
        given(userService.getBudgetAlertThreshold(USER_ID)).willReturn(85);
        given(expenseRepository.findByDateRange(eq(USER_ID), any(), any())).willReturn(List.of(expense));

        // findByExpenseIds: 1번째(변경 전 기여분 캡처)=분할 없음, 2번째(notify)=새 분할 [식비 4,000 + 생활용품 6,000]
        ExpenseSplit sp2 = ExpenseSplit.create(expense, l2, 4_000L, "식비", 0);
        ExpenseSplit sp8 = ExpenseSplit.create(expense, l8, 6_000L, "생활", 1);
        given(expenseSplitRepository.findByExpenseIds(any())).willReturn(List.of(), List.of(sp2, sp8));

        var cmd = new ExpenseServiceDto.UpdateCommand(
                Patch.set(8L), Patch.absent(), Patch.set(ExpenseType.EXPENSE), Patch.set(10_000L),
                Patch.set("쿠팡"), Patch.set(LocalDateTime.of(2026, 6, 5, 12, 0)), Patch.absent(), Patch.absent(), Patch.absent(),
            Patch.absent(),
            Patch.absent(),
            Patch.absent(), Patch.absent(), Patch.absent(),
                List.of(new ExpenseSplitServiceDto.SplitCommand(
            null,2L, 4_000L, "식비", 0),
                        new ExpenseSplitServiceDto.SplitCommand(
            null,8L, 6_000L, "생활", 1)));

        sut.updateExpense(5L, USER_ID, cmd);

        // 식비/음료(1) 예산이 분할 4,000 으로 100% 도달 → OVER 알림. (구 로직은 전액을 생활용품에만 귀속해 누락)
        ArgumentCaptor<NotificationServiceDto.CreateCommand> captor =
                ArgumentCaptor.forClass(NotificationServiceDto.CreateCommand.class);
        verify(notificationService).createNotification(captor.capture());
        assertThat(captor.getValue().title()).contains("식비/음료");
    }

    @Test
    @DisplayName("getExpenses — 분할 거래는 splitCategoryRowIds 에 분할 카테고리 id 를 노출(목록 필터 split-aware용)")
    void getExpensesExposesSplitCategoryIds() {
        User u = user(USER_ID);
        ExpenseCategory p7 = parentCat(7L, u, "생활");
        ExpenseCategory l8 = leafUnder(8L, u, p7);
        ExpenseCategory p1 = parentCat(1L, u, "식비/음료");
        ExpenseCategory l2 = leafUnder(2L, u, p1);
        Expense e = Expense.createExpense(u, l8, null, ExpenseType.EXPENSE, 10_000L, "쿠팡",
                LocalDateTime.of(2026, 6, 5, 12, 0), null, null, null,
            null,
            null,
            null);
        ReflectionTestUtils.setField(e, "rowId", 50L);
        given(expenseRepository.findByUser(eq(USER_ID), any(), any(), any(), any())).willReturn(List.of(e));
        ExpenseSplit s1 = ExpenseSplit.create(e, l8, 6_000L, "생활", 0);
        ExpenseSplit s2 = ExpenseSplit.create(e, l2, 4_000L, "식비", 1);
        given(expenseSplitRepository.findByExpenseIds(any())).willReturn(List.of(s1, s2));

        var result = sut.getExpenses(USER_ID, null, null, null,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).splitCategoryRowIds()).containsExactlyInAnyOrder(8L, 2L);
    }

    @Test
    @DisplayName("searchExpenses — 분할 거래는 splitCategoryRowIds 노출(검색 필터 split-aware용, 목록과 대칭)")
    void searchExposesSplitCategoryIds() {
        User u = user(USER_ID);
        ExpenseCategory p7 = parentCat(7L, u, "생활");
        ExpenseCategory l8 = leafUnder(8L, u, p7);
        ExpenseCategory p1 = parentCat(1L, u, "식비/음료");
        ExpenseCategory l2 = leafUnder(2L, u, p1);
        Expense e = Expense.createExpense(u, l8, null, ExpenseType.EXPENSE, 10_000L, "쿠팡",
                LocalDateTime.of(2026, 6, 5, 12, 0), null, null, null,
            null,
            null,
            null);
        ReflectionTestUtils.setField(e, "rowId", 60L);
        given(expenseRepository.search(eq(USER_ID), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(List.of(e));
        ExpenseSplit s1 = ExpenseSplit.create(e, l8, 6_000L, "생활", 0);
        ExpenseSplit s2 = ExpenseSplit.create(e, l2, 4_000L, "식비", 1);
        given(expenseSplitRepository.findByExpenseIds(any())).willReturn(List.of(s1, s2));

        var cmd = new ExpenseServiceDto.SearchCommand(
                USER_ID, null, null, null, "쿠팡", null, null, null, null, null);
        var result = sut.searchExpenses(cmd);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).splitCategoryRowIds()).containsExactlyInAnyOrder(8L, 2L);
    }

    @Nested
    @DisplayName("거래 금액 부호 — 유형이 부호를 정하고 금액은 크기만 담는다")
    class AmountSign {

        @Test
        @DisplayName("음수 금액으로 지출을 만들 수 없다 — 잔액이 거꾸로 늘어난다")
        void rejectsNegativeAmountOnCreate() {
            assertThatThrownBy(() -> sut.createExpense(createCmdAmount(1L, -10_000L)))
                .isInstanceOf(InvalidValueException.class);
        }

        @Test
        @DisplayName("0원 거래도 막는다 — 잔액도 통계도 안 움직이는 빈 기록")
        void rejectsZeroAmount() {
            assertThatThrownBy(() -> sut.createExpense(createCmdAmount(1L, 0L)))
                .isInstanceOf(InvalidValueException.class);
        }

        @Test
        @DisplayName("금액이 없으면 막는다")
        void rejectsNullAmount() {
            ExpenseServiceDto.CreateCommand cmd = new ExpenseServiceDto.CreateCommand(
                USER_ID, 1L, null, ExpenseType.EXPENSE, null,
                "x", LocalDateTime.of(2026, 6, 1, 12, 0), null, null, null,
                null, null, null, null, null);
            assertThatThrownBy(() -> sut.createExpense(cmd))
                .isInstanceOf(InvalidValueException.class);
        }

        @Test
        @DisplayName("수정에서도 음수를 막는다 — 만든 뒤 뒤집는 경로를 남기지 않는다")
        void rejectsNegativeAmountOnUpdate() {
            ExpenseServiceDto.UpdateCommand cmd = new ExpenseServiceDto.UpdateCommand(
                Patch.set(1L), Patch.absent(), Patch.set(ExpenseType.EXPENSE), Patch.set(-5_000L),
                Patch.set("x"), Patch.set(LocalDateTime.of(2026, 6, 1, 12, 0)), Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), null);
            assertThatThrownBy(() -> sut.updateExpense(1L, USER_ID, cmd))
                .isInstanceOf(InvalidValueException.class);
        }
    }

    @Nested
    @DisplayName("자동 생성 거래 — 금액은 잠그고 카테고리는 연다")
    class AutoGenerated {

        private Expense autoExpense() {
            Expense e = mock(Expense.class);
            given(e.getUser()).willReturn(user(USER_ID));
            given(e.isAutoGenerated()).willReturn(true);
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));
            return e;
        }

        @Test
        @DisplayName("카테고리를 달아도 잔액 이력이 새로 생기지 않는다 — 예수금이 늘어나면 안 된다")
        void categorizingDoesNotTouchBalance() {
            Expense e = autoExpense();
            given(expenseCategoryRepository.findById(9L))
                .willReturn(Optional.of(category(9L, user(USER_ID))));

            sut.updateExpense(1L, USER_ID, updateCmd(9L));

            then(e).should().updateEditableFields(any(), any(), any());
            then(balanceHistoryService).should(never()).recordExpenseKrw(any(), any(), any(), any(), any(), any(), any());
            then(balanceHistoryService).should(never()).removeExpense(any());
        }

        @Test
        @DisplayName("직접 삭제할 수 없다 — 원 거래(매도·이체)를 지워야 한다")
        void cannotDeleteDirectly() {
            autoExpense();

            assertThatThrownBy(() -> sut.deleteExpense(1L, USER_ID))
                .isInstanceOf(InvalidValueException.class);
        }
    }

    /**
     * 결제일(12일)이 있는 신용카드 — 회차 규칙이 서는 카드다. 결제계좌는 두지 않는다 —
     * 돈을 얼마나 움직일지는 카드 결제 서비스가 정하고, 여기서는 무엇을 넘기는지만 본다.
     */
    private Asset creditCard(long rowId) {
        Asset a = Asset.createAsset(user(USER_ID), "현대카드", AssetType.CREDIT_CARD, 0L,
            null, null, null, null, null, null, null, null, null, 12, null);
        ReflectionTestUtils.setField(a, "rowId", rowId);
        return a;
    }

    @Nested
    @DisplayName("환불 마크")
    class RefundMark {

        private Expense card(long rowId, long amount, LocalDateTime at, Asset asset) {
            User u = user(USER_ID);
            Expense e = Expense.createExpense(u, null, asset, ExpenseType.EXPENSE, amount,
                "구매", at, "무신사", "CARD", null, null, null, null);
            ReflectionTestUtils.setField(e, "rowId", rowId);
            return e;
        }

        /**
         * 수정 경로를 타는 거래 — 카테고리가 있어야 한다.
         *
         * <p>{@link #card} 는 마크·삭제만 보므로 카테고리를 비워 뒀다. 수정은 "거래 유형 ==
         * 카테고리 유형" 을 검사해서 비어 있으면 그 자리에서 터진다.
         */
        private Expense cardForUpdate(long amount, LocalDateTime at, Asset asset) {
            User u = user(USER_ID);
            Expense e = Expense.createExpense(u, category(10L, u), asset,
                ExpenseType.EXPENSE, amount, "구매", at, "무신사", "CARD", null, null, null, null);
            ReflectionTestUtils.setField(e, "rowId", 1L);
            lenient().when(expenseCategoryRepository.hasChildren(10L)).thenReturn(false);
            return e;
        }

        @Test
        @DisplayName("지출을 마크하면 흐름이 지워지고 표식이 남는다 — 돈이 자산으로 돌아간다")
        void marksAndRemovesFlow() {
            Expense e = card(1L, 50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), null);
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));

            var info = sut.refund(1L, USER_ID, LocalDateTime.of(2026, 7, 13, 9, 0));

            assertThat(info.refundedAt()).isEqualTo(LocalDateTime.of(2026, 7, 13, 9, 0));
            assertThat(e.isRefunded()).isTrue();
            assertThat(e.isCountable()).isFalse();
            then(balanceHistoryService).should().removeExpense(1L);
        }

        @Test
        @DisplayName("환불일을 안 주면 지금으로 찍는다")
        void defaultsToNow() {
            Expense e = card(1L, 50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), null);
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));

            var info = sut.refund(1L, USER_ID, null);

            assertThat(info.refundedAt()).isNotNull();
        }

        @Test
        @DisplayName("수입은 마크할 수 없다 — 지출만 환불된다")
        void incomeRejected() {
            User u = user(USER_ID);
            Expense income = Expense.createExpense(u, null, null, ExpenseType.INCOME, 10_000L,
                "급여", LocalDateTime.of(2026, 7, 1, 9, 0), null, null, null, null, null, null);
            ReflectionTestUtils.setField(income, "rowId", 1L);
            given(expenseRepository.findById(1L)).willReturn(Optional.of(income));

            assertThatThrownBy(() -> sut.refund(1L, USER_ID, null))
                .isInstanceOf(InvalidValueException.class)
                .hasMessage(DeskErrorCode.REFUND_NOT_EXPENSE.getMessageKey());
        }

        @Test
        @DisplayName("두 번 마크하면 400 — 같은 거래를 두 번 되돌릴 수 없다")
        void alreadyRefunded() {
            Expense e = card(1L, 50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), null);
            e.markRefunded(LocalDateTime.of(2026, 7, 11, 9, 0));
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));

            assertThatThrownBy(() -> sut.refund(1L, USER_ID, null))
                .isInstanceOf(InvalidValueException.class)
                .hasMessage(DeskErrorCode.ALREADY_REFUNDED.getMessageKey());
        }

        @Test
        @DisplayName("환불된 거래는 수정할 수 없다 — 먼저 취소해야 한다")
        void refundedIsReadonly() {
            Expense e = card(1L, 50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), null);
            e.markRefunded(LocalDateTime.of(2026, 7, 11, 9, 0));
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));

            assertThatThrownBy(() -> sut.updateExpense(1L, USER_ID, new ExpenseServiceDto.UpdateCommand(
                    Patch.absent(), Patch.absent(), Patch.absent(), Patch.set(1_000L),
                    Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(),
                    Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), null)))
                .isInstanceOf(InvalidValueException.class)
                .hasMessage(DeskErrorCode.REFUNDED_READONLY.getMessageKey());
        }

        @Test
        @DisplayName("취소하면 표식이 지워지고 흐름이 되살아난다")
        void cancelRestoresFlow() {
            Expense e = card(1L, 50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), null);
            e.markRefunded(LocalDateTime.of(2026, 7, 11, 9, 0));
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));

            var info = sut.cancelRefund(1L, USER_ID);

            assertThat(info.refundedAt()).isNull();
            assertThat(e.isCountable()).isTrue();
            then(balanceHistoryService).should().recordExpenseKrw(
                null, 1L, ExpenseType.EXPENSE, 50_000L, null, null, LocalDateTime.of(2026, 7, 10, 12, 0));
        }

        @Test
        @DisplayName("마크하지 않은 거래의 취소는 400")
        void cancelNotRefunded() {
            Expense e = card(1L, 50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), null);
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));

            assertThatThrownBy(() -> sut.cancelRefund(1L, USER_ID))
                .isInstanceOf(InvalidValueException.class)
                .hasMessage(DeskErrorCode.NOT_REFUNDED.getMessageKey());
        }

        /**
         * 카드 원거래는 회차 정산을 거친다 — 돌려줄 돈이 있으면 그 이체를 걸어 둔다.
         *
         * <p>얼마를 돌려주는지는 {@code CardPaymentServiceImpl} 의 몫이라 여기서는
         * <b>무엇을 넘기는지</b>와 <b>결과를 연결하는지</b>만 본다.
         */
        private CardPaymentServiceDto.SettlementResult settled(Long transferRowId, long refunded) {
            return new CardPaymentServiceDto.SettlementResult(transferRowId, refunded, null, 0L);
        }

        private CardPaymentServiceDto.SettlementCommand settleCommand() {
            org.mockito.ArgumentCaptor<CardPaymentServiceDto.SettlementCommand> cmd =
                org.mockito.ArgumentCaptor.forClass(CardPaymentServiceDto.SettlementCommand.class);
            then(cardPaymentService).should().settleExpenseChange(cmd.capture());
            return cmd.getValue();
        }

        private CardPaymentServiceDto.SettlementCommand previewCommand() {
            org.mockito.ArgumentCaptor<CardPaymentServiceDto.SettlementCommand> cmd =
                org.mockito.ArgumentCaptor.forClass(CardPaymentServiceDto.SettlementCommand.class);
            then(cardPaymentService).should().previewExpenseChange(cmd.capture());
            return cmd.getValue();
        }

        private CardPaymentServiceDto.SettlementPreview previewOf(long refund) {
            return new CardPaymentServiceDto.SettlementPreview(refund, false, 0L);
        }

        @Test
        @DisplayName("카드 거래를 환불하면 환불 정산을 부르고, 이체가 생기면 걸어 둔다")
        void linksCardRefundTransfer() {
            Asset cardAsset = creditCard(9L);
            Expense e = card(1L, 50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), cardAsset);
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));
            given(cardPaymentService.settleExpenseChange(any())).willReturn(settled(77L, 50_000L));

            var info = sut.refund(1L, USER_ID, null);

            assertThat(info.refundTransferRowId()).isEqualTo(77L);
            assertThat(info.refundedAmount()).isEqualTo(50_000L);
            var cmd = settleCommand();
            assertThat(cmd.mode()).isEqualTo(CardPaymentServiceDto.SettlementMode.REFUND);
            assertThat(cmd.before().cardRowId()).isEqualTo(9L);
            assertThat(cmd.before().dues()).containsEntry(java.time.LocalDate.of(2026, 7, 1), 50_000L);
            assertThat(cmd.after().isCard()).as("환불된 거래는 어느 회차에도 없다").isFalse();
        }

        @Test
        @DisplayName("돌려줄 돈이 없으면 이체를 걸지 않는다 — 결제 전 환불")
        void noTransferWhenNoCredit() {
            Asset cardAsset = creditCard(9L);
            Expense e = card(1L, 50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), cardAsset);
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));
            given(cardPaymentService.settleExpenseChange(any())).willReturn(settled(null, 0L));

            var info = sut.refund(1L, USER_ID, null);

            assertThat(info.refundTransferRowId()).isNull();
            assertThat(info.refundedAmount()).isNull();
        }

        @Test
        @DisplayName("카드가 아니면 회차 정산을 아예 안 부른다")
        void nonCardSkipsCredit() {
            Expense e = card(1L, 50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), null);
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));

            sut.refund(1L, USER_ID, null);

            then(cardPaymentService).should(never()).settleExpenseChange(any());
        }

        @Test
        @DisplayName("환불 취소는 붙잡은 몫을 무르고 취소 정산을 부른다")
        void cancelRefundSettles() {
            Asset cardAsset = creditCard(9L);
            Expense e = card(1L, 50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), cardAsset);
            e.markRefunded(LocalDateTime.of(2026, 7, 11, 9, 0));
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));

            sut.cancelRefund(1L, USER_ID);

            then(balanceHistoryService).should().removeCardRefundHold(1L);
            var cmd = settleCommand();
            assertThat(cmd.mode()).isEqualTo(CardPaymentServiceDto.SettlementMode.CANCEL_REFUND);
            assertThat(cmd.before().isCard()).isFalse();
            assertThat(cmd.after().cardRowId()).isEqualTo(9L);
        }

        private ExpenseServiceDto.UpdateCommand amountTo(long amount) {
            return new ExpenseServiceDto.UpdateCommand(
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.set(amount),
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(),
                null);
        }

        @Test
        @DisplayName("감액하면 전후 모습을 넘기고, 메모에 감액분이라고 남는다")
        void reduceRefundsDifference() {
            Asset cardAsset = creditCard(9L);
            Expense e = cardForUpdate(50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), cardAsset);
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));
            given(cardPaymentService.settleExpenseChange(any())).willReturn(settled(88L, 30_000L));

            var info = sut.updateExpense(1L, USER_ID, amountTo(20_000L));

            assertThat(info.refundedAmount())
                .as("화면이 '결제계좌로 N원이 환급됐어요' 를 말할 재료")
                .isEqualTo(30_000L);
            var cmd = settleCommand();
            assertThat(cmd.mode()).isEqualTo(CardPaymentServiceDto.SettlementMode.CHANGE);
            assertThat(cmd.before().dues()).containsEntry(java.time.LocalDate.of(2026, 7, 1), 50_000L);
            assertThat(cmd.after().dues()).containsEntry(java.time.LocalDate.of(2026, 7, 1), 20_000L);
            assertThat(cmd.refundMemo()).endsWith("· 감액분");
        }

        @Test
        @DisplayName("날짜를 옮기면 옮긴 회차로 넘긴다 — 메모는 감액이 아니다")
        void dateMoveRefundsFull() {
            Asset cardAsset = creditCard(9L);
            Expense e = cardForUpdate(50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), cardAsset);
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));

            sut.updateExpense(1L, USER_ID, new ExpenseServiceDto.UpdateCommand(
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.set(LocalDateTime.of(2026, 9, 10, 12, 0)),
                Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(),
                null));

            var cmd = settleCommand();
            assertThat(cmd.after().dues()).containsOnlyKeys(java.time.LocalDate.of(2026, 9, 1));
            assertThat(cmd.refundMemo()).as("날짜 이동은 감액이 아니다").doesNotContain("감액분");
        }

        /**
         * 미리보기 — 확인창이 저장하기 <b>전에</b> 돈의 움직임을 묻는 자리(설계 13-1).
         *
         * <p>실제 실행과 같은 판정을 부르므로 여기서는 <b>무엇을 가정으로 넘기는지</b>와
         * 결과를 어떤 사유로 옮기는지만 본다.
         */
        @Test
        @DisplayName("삭제 미리보기는 바뀐 뒤 모습을 비워 넘긴다")
        void previewDeletionUsesFullAmount() {
            Asset cardAsset = creditCard(9L);
            Expense e = card(1L, 50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), cardAsset);
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));
            given(cardPaymentService.previewExpenseChange(any())).willReturn(previewOf(50_000L));

            var preview = sut.refundPreview(1L, USER_ID, null, null, null, null);

            assertThat(preview.applies()).isTrue();
            assertThat(preview.refundAmount()).isEqualTo(50_000L);
            assertThat(preview.reason()).isEqualTo(CardPaymentServiceDto.RefundPreview.OK);
            var cmd = previewCommand();
            assertThat(cmd.before().cardRowId()).isEqualTo(9L);
            assertThat(cmd.after().isCard()).isFalse();
        }

        @Test
        @DisplayName("감액 미리보기는 가정한 금액으로 바뀐 뒤 모습을 만든다 — 저장하지 않는다")
        void previewReduceUsesDifference() {
            Asset cardAsset = creditCard(9L);
            Expense e = card(1L, 50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), cardAsset);
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));
            given(cardPaymentService.previewExpenseChange(any())).willReturn(previewOf(30_000L));

            var preview = sut.refundPreview(1L, USER_ID, 20_000L, null, null, null);

            assertThat(preview.refundAmount()).isEqualTo(30_000L);
            var cmd = previewCommand();
            assertThat(cmd.after().cardRowId()).isEqualTo(9L);
            assertThat(cmd.after().dues()).containsEntry(java.time.LocalDate.of(2026, 7, 1), 20_000L);
            assertThat(e.getAmount()).as("미리보기는 원거래를 건드리지 않는다").isEqualTo(50_000L);
            then(expenseRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("돌려줄 게 없으면 NOT_PAID_CYCLE — 옛 앱에 '결제한 달이 지나…' 문구를 띄우지 않는다(인계 25 ⑧)")
        void previewNothingToRefund() {
            Asset cardAsset = creditCard(9L);
            Expense e = card(1L, 50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), cardAsset);
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));
            given(cardPaymentService.previewExpenseChange(any())).willReturn(previewOf(0L));

            var preview = sut.refundPreview(1L, USER_ID, null, null, null, null);

            assertThat(preview.applies()).isFalse();
            assertThat(preview.reason())
                .isEqualTo(CardPaymentServiceDto.RefundPreview.NOT_PAID_CYCLE);
            assertThat(preview.sameDayExtraPayment()).isZero();
        }

        @Test
        @DisplayName("이미 환불된 거래는 계산하지 않는다 — 환급은 그때 끝났다")
        void previewAlreadyRefunded() {
            Asset cardAsset = creditCard(9L);
            Expense e = card(1L, 50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), cardAsset);
            e.markRefunded(LocalDateTime.of(2026, 7, 11, 9, 0));
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));

            var preview = sut.refundPreview(1L, USER_ID, null, null, null, null);

            assertThat(preview.applies()).isFalse();
            assertThat(preview.reason())
                .isEqualTo(CardPaymentServiceDto.RefundPreview.ALREADY_REFUNDED);
            then(cardPaymentService).should(never()).previewExpenseChange(any());
        }

        @Test
        @DisplayName("카드가 아니면 계산하지 않는다 — 돌려줄 자리가 없다")
        void previewNonCard() {
            Expense e = card(1L, 50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), null);
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));

            var preview = sut.refundPreview(1L, USER_ID, null, null, null, null);

            assertThat(preview.reason())
                .isEqualTo(CardPaymentServiceDto.RefundPreview.NOT_CARD);
            then(cardPaymentService).should(never()).previewExpenseChange(any());
        }

        @Test
        @DisplayName("새 카드 지출 저장 미리보기 — 기록만 남는 금액을 옮기고, 당일 추가 결제는 없다(U7)")
        void savePreviewCarriesRecord() {
            Asset cardAsset = creditCard(9L);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));
            given(assetRepository.findById(9L)).willReturn(Optional.of(cardAsset));
            given(cardPaymentService.previewExpenseChange(any())).willReturn(
                new CardPaymentServiceDto.SettlementPreview(0L, false, 25_000L));

            var preview = sut.cardSavePreview(USER_ID, 9L, 25_000L,
                LocalDateTime.of(2026, 8, 20, 12, 0), null);

            assertThat(preview.newRecordAmount()).isEqualTo(25_000L);
            assertThat(preview.sameDayExtraPayment()).isZero();
            var cmd = previewCommand();
            assertThat(cmd.before().isCard()).isFalse();
            assertThat(cmd.after().dues()).containsEntry(java.time.LocalDate.of(2026, 8, 1), 25_000L);
        }

        @Test
        @DisplayName("결제된 회차의 카드 거래를 지우면 회차 정산으로 돌려준 금액을 알린다")
        void deleteChecksCredit() {
            Asset cardAsset = creditCard(9L);
            Expense e = card(1L, 50_000L, LocalDateTime.of(2026, 7, 10, 12, 0), cardAsset);
            given(expenseRepository.findById(1L)).willReturn(Optional.of(e));
            given(cardPaymentService.settleExpenseChange(any())).willReturn(settled(88L, 50_000L));

            Long refunded = sut.deleteExpense(1L, USER_ID);

            var cmd = settleCommand();
            assertThat(cmd.before().cardRowId()).isEqualTo(9L);
            assertThat(cmd.after().isCard()).isFalse();
            assertThat(refunded).as("화면이 사후 토스트로 알릴 금액").isEqualTo(50_000L);
        }
    }

    /**
     * 매일 09:00 예산 알림 배치가 쓰는 카테고리별 월 지출 — 화면의 이행률과 같은 규칙이어야 한다.
     *
     * <p>종전엔 날것 합이라 환불한 거래·아직 안 온 반복거래·카드 이월까지 예산을 깎았다.
     * 화면은 "40%" 인데 알림은 "예산을 넘었어요" 가 뜨는 모양이 된다.
     */
    @Nested
    @DisplayName("예산 알림 합계는 가계부 규칙을 따른다")
    class BudgetAlertLedgerRule {

        private Expense spend(long rowId, long amount, LocalDateTime at, ExpenseCategory cat) {
            Expense e = Expense.createExpense(user(USER_ID), cat, null, ExpenseType.EXPENSE, amount,
                "구매", at, "가맹점", "CARD", null, null, null, null);
            ReflectionTestUtils.setField(e, "rowId", rowId);
            return e;
        }

        @Test
        @DisplayName("환불·카드 이월·미래 거래는 빼고 센다")
        void excludesRefundedCarryoverAndFuture() {
            ExpenseCategory food = category(10L, user(USER_ID));
            LocalDateTime past = LocalDate.now().withDayOfMonth(1).atStartOfDay();
            Expense plain = spend(1L, 40_000L, past, food);
            Expense refunded = spend(2L, 9_000L, past, food);
            refunded.markRefunded(past.plusHours(1));
            Expense carryover = spend(3L, 300_000L, past, food);
            carryover.markAutoGenerated(Expense.AUTO_SOURCE_CARD_CARRYOVER);
            LocalDate today = LocalDate.now();
            Expense future = spend(4L, 7_000L,
                today.withDayOfMonth(today.lengthOfMonth()).atTime(23, 59, 59), food);
            given(expenseRepository.findByDateRange(eq(USER_ID), any(), any()))
                .willReturn(List.of(plain, refunded, carryover, future));
            lenient().when(expenseSplitRepository.findByExpenseIds(any())).thenReturn(List.of());

            Map<Long, Long> spent = sut.getMonthlyExpenseSpendByCategory(
                USER_ID, today.getYear(), today.getMonthValue());

            // 말일 23:59:59 가 "지금" 과 같은 드문 순간이 아니면 미래 거래는 빠진다.
            assertThat(spent.get(10L)).isEqualTo(40_000L);
        }
    }
}
