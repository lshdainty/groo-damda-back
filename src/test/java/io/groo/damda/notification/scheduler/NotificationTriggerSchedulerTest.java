package io.groo.damda.notification.scheduler;

import io.groo.core.time.ServiceClock;
import io.groo.core.type.YNType;
import io.groo.core.time.UserClock;
import io.groo.damda.calendar.domain.CalendarEvent;
import io.groo.damda.calendar.domain.EventReminder;
import io.groo.damda.calendar.repository.EventReminderRepository;
import io.groo.damda.calendar.type.CalendarEventType;
import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.domain.ExpenseBudget;
import io.groo.damda.expense.domain.ExpenseCategory;
import io.groo.damda.expense.repository.ExpenseBudgetRepository;
import io.groo.damda.expense.repository.ExpenseRepository;
import io.groo.damda.expense.service.ExpenseService;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.notification.repository.NotificationRepository;
import io.groo.damda.notification.service.NotificationMessages;
import io.groo.damda.notification.service.NotificationService;
import io.groo.damda.notification.service.dto.NotificationServiceDto;
import io.groo.damda.support.message.TestMessages;
import io.groo.damda.todo.domain.Todo;
import io.groo.damda.todo.repository.TodoRepository;
import io.groo.damda.todo.type.TodoPriority;
import io.groo.damda.todo.type.TodoType;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 스케줄러가 <b>DB 에 굳히는 알림 문자열</b>을 고정한다(QA 2026-09-04 #76).
 *
 * <p>알림은 응답과 달리 문자열 그대로 저장돼 나중에 다시 렌더할 수 없다. 그래서 "무슨 문장이
 * 저장되는가" 자체가 회귀 대상이다 — 여기서는 {@code createNotification} 에 실려 가는
 * 커맨드를 잡아 제목·본문을 글자까지 확인한다.
 *
 * <p>고치기 전 실측(2026-09-04, 아래 케이스 그대로 돌려 얻은 값):
 * <pre>
 * 예산(9,000/10,000, 임계 85%)  "식비 예산 초과 경고" / "식비 카테고리 예산의 90%를 사용했습니다."
 * 할일(오늘 마감)               "보고서 제출"        / "오늘 마감인 할일이 있습니다."
 * 일정(30분 전)                 "치과 예약 알림"      / "30분 전 알림"
 * </pre>
 * 같은 90% 상황을 거래 저장 경로({@code ExpenseServiceImpl})는 <b>다른 문장·다른 말투</b>로
 * 알렸다("식비 예산 90% 사용" / "식비 예산의 90%를 사용했어요 (9,000 / 10,000원)."). 그게 결함이었다.
 */
@ExtendWith(MockitoExtension.class)
class NotificationTriggerSchedulerTest {

    private static final long USER_ID = 1L;

    @Mock private NotificationService notificationService;
    @Mock private NotificationRepository notificationRepository;
    @Mock private EventReminderRepository eventReminderRepository;
    @Mock private ExpenseBudgetRepository expenseBudgetRepository;
    @Mock private ExpenseRepository expenseRepository;
    @Mock private ExpenseService expenseService;
    @Mock private TodoRepository todoRepository;
    @Mock private UserService userService;

    private final ServiceClock serviceClock = new ServiceClock("Asia/Seoul");
    private final UserClock userClock = new UserClock(rowId -> null, serviceClock);
    // 실물 번들을 읽는다 — 여기서 mock 을 쓰면 "무슨 문장이 저장되는가" 를 아무것도 안 지킨다.
    private final NotificationMessages notificationMessages = TestMessages.notificationMessages();

    @BeforeEach
    void clearBoundLocale() {
        // 스케줄러 스레드에는 요청 로케일이 없다. 같은 JVM 의 다른 테스트가 흘린 컨텍스트가
        // 남아 있으면 그 조건이 깨지므로 명시적으로 비운다.
        LocaleContextHolder.resetLocaleContext();
    }

    private NotificationTriggerScheduler scheduler() {
        return new NotificationTriggerScheduler(
                notificationService, notificationMessages, notificationRepository, eventReminderRepository,
                expenseBudgetRepository, expenseService,
                serviceClock, userClock, todoRepository, userService);
    }

    private static User user() {
        User u = User.createUser(null, "tester", "테스터", "tester@porest.com");
        ReflectionTestUtils.setField(u, "rowId", USER_ID);
        return u;
    }

    /**
     * 알림 토글만 바꾼 사용자. 필드 이름 문자열 대신 저장 API 를 그대로 쓴다 —
     * 앱 알림 설정 화면이 부르는 것과 같은 경로다. null 인 항목은 기본값(대개 Y) 그대로.
     */
    private static User userWithToggles(Boolean notifyBudget, Boolean notifyCalendar) {
        User u = user();
        u.updateNotificationPreferences(null, null, notifyBudget, null, null, notifyCalendar,
                null, null, null, null, null, null, null, null, null);
        return u;
    }

    private static ExpenseCategory category(User u, String name) {
        ExpenseCategory c = ExpenseCategory.createCategory(u, name, "t", "#fff", ExpenseType.EXPENSE, null);
        ReflectionTestUtils.setField(c, "rowId", 10L);
        return c;
    }

    private NotificationServiceDto.CreateCommand captureOne() {
        ArgumentCaptor<NotificationServiceDto.CreateCommand> captor =
                ArgumentCaptor.forClass(NotificationServiceDto.CreateCommand.class);
        verify(notificationService).createNotification(captor.capture());
        return captor.getValue();
    }

    private void givenCategoryBudget(long limit, long spent, int thresholdPct, String categoryName) {
        User u = user();
        LocalDate today = serviceClock.today();
        ExpenseBudget budget = ExpenseBudget.createBudget(
                u, category(u, categoryName), limit, today.getYear(), today.getMonthValue());
        ReflectionTestUtils.setField(budget, "rowId", 7L);

        given(expenseBudgetRepository.findAllByYearAndMonth(today.getYear(), today.getMonthValue()))
                .willReturn(List.of(budget));
        given(userService.getBudgetAlertThreshold(USER_ID)).willReturn(thresholdPct);
        given(expenseService.getMonthlyExpenseSpendByCategory(
                eq(USER_ID), eq(today.getYear()), eq(today.getMonthValue())))
                .willReturn(Map.of(10L, spent));
        given(notificationRepository.existsByUserAndReferenceAndCreatedAfter(
                eq(USER_ID), any(), anyLong(), any())).willReturn(false);
    }

    @Test
    @DisplayName("예산 경고(90%) — 거래 저장 경로와 같은 문장이 저장된다 (종전: `… 초과 경고` · `…했습니다.`)")
    void budgetWarnUsesTheSharedSentence() {
        givenCategoryBudget(10_000L, 9_000L, 85, "식비");

        scheduler().checkBudgetAlerts();

        NotificationServiceDto.CreateCommand cmd = captureOne();
        assertThat(cmd.title()).isEqualTo("식비 예산 90% 사용");
        assertThat(cmd.message()).isEqualTo("식비 예산의 90%를 사용했어요 (9,000 / 10,000원)");
        // 임계는 넘었지만 아직 초과가 아니다 — 종전엔 이 자리를 "초과 경고" 라 불렀다.
        assertThat(cmd.title()).doesNotContain("초과");
    }

    @Test
    @DisplayName("예산 초과(100%) — 초과 문장은 `초과` 로 말한다")
    void budgetOverUsesTheOverSentence() {
        givenCategoryBudget(10_000L, 12_000L, 85, "식비");

        scheduler().checkBudgetAlerts();

        NotificationServiceDto.CreateCommand cmd = captureOne();
        assertThat(cmd.title()).isEqualTo("식비 예산 초과");
        assertThat(cmd.message()).isEqualTo("식비 예산 10,000원을 초과했어요 (현재 12,000원)");
    }

    @Test
    @DisplayName("카테고리 없는(월 전체) 예산 이름도 번들에서 온다 — 코드에 박힌 `전체` 를 뺐다")
    void overallBudgetNameComesFromTheBundle() {
        User u = user();
        LocalDate today = serviceClock.today();
        ExpenseBudget budget = ExpenseBudget.createBudget(u, null, 10_000L, today.getYear(), today.getMonthValue());
        ReflectionTestUtils.setField(budget, "rowId", 7L);
        given(expenseBudgetRepository.findAllByYearAndMonth(today.getYear(), today.getMonthValue()))
                .willReturn(List.of(budget));
        given(userService.getBudgetAlertThreshold(USER_ID)).willReturn(85);
        // 전체 예산은 카테고리 집계가 아니라 월 전체 지출 합을 본다(가계부 합계와 같은 규칙).
        given(expenseService.getMonthlyExpenseTotal(USER_ID, today.getYear(), today.getMonthValue()))
                .willReturn(9_000L);
        given(notificationRepository.existsByUserAndReferenceAndCreatedAfter(
                eq(USER_ID), any(), anyLong(), any())).willReturn(false);

        scheduler().checkBudgetAlerts();

        NotificationServiceDto.CreateCommand cmd = captureOne();
        assertThat(cmd.title()).isEqualTo("전체 예산 90% 사용");
        assertThat(cmd.message()).isEqualTo("전체 예산의 90%를 사용했어요 (9,000 / 10,000원)");
    }

    @Test
    @DisplayName("할일 리마인더 — `~어요` (종전: 오늘 마감인 할일이 있습니다.)")
    void todoReminderSpeaksProductTone() {
        User u = user();
        LocalDate today = serviceClock.today();
        Todo todo = Todo.createTodo(u, "보고서 제출", "본문", TodoPriority.HIGH, null, today, TodoType.TASK);
        ReflectionTestUtils.setField(todo, "rowId", 3L);
        given(todoRepository.findDueTodosForReminder(today, today.plusDays(1))).willReturn(List.of(todo));
        given(notificationRepository.existsByUserAndReferenceAndCreatedAfter(
                eq(USER_ID), any(), anyLong(), any())).willReturn(false);

        scheduler().checkTodoReminders();

        NotificationServiceDto.CreateCommand cmd = captureOne();
        assertThat(cmd.title()).isEqualTo("보고서 제출");     // 할일 제목 그대로 — 번역 대상이 아니다
        assertThat(cmd.message()).isEqualTo("오늘 마감인 할 일이 있어요");
    }

    @Test
    @DisplayName("할일 리마인더 — 내일 마감은 다른 키를 쓴다")
    void todoReminderTomorrowUsesTheOtherKey() {
        User u = user();
        LocalDate today = serviceClock.today();
        Todo todo = Todo.createTodo(u, "보고서 제출", "본문", TodoPriority.HIGH, null,
                today.plusDays(1), TodoType.TASK);
        ReflectionTestUtils.setField(todo, "rowId", 3L);
        given(todoRepository.findDueTodosForReminder(today, today.plusDays(1))).willReturn(List.of(todo));
        given(notificationRepository.existsByUserAndReferenceAndCreatedAfter(
                eq(USER_ID), any(), anyLong(), any())).willReturn(false);

        scheduler().checkTodoReminders();

        assertThat(captureOne().message()).isEqualTo("내일 마감인 할 일이 있어요");
    }

    @Test
    @DisplayName("일정 리마인더 — 문장이 번들에서 온다(minutesBefore 가 null 이면 0분)")
    void eventReminderComesFromTheBundle() {
        User u = user();
        CalendarEvent event = CalendarEvent.createEvent(u, "치과 예약", null,
                CalendarEventType.PERSONAL, null, LocalDateTime.now().plusMinutes(1), null,
                null, null, null, null, null);
        ReflectionTestUtils.setField(event, "rowId", 11L);
        EventReminder reminder = EventReminder.create(event, "PUSH", 30);
        ReflectionTestUtils.setField(reminder, "rowId", 12L);
        given(eventReminderRepository.findUnsentRemindersStartingBefore(any())).willReturn(List.of(reminder));

        scheduler().checkEventReminders();

        NotificationServiceDto.CreateCommand cmd = captureOne();
        assertThat(cmd.title()).isEqualTo("치과 예약 알림");
        assertThat(cmd.message()).isEqualTo("30분 전 알림");
    }

    @Test
    @DisplayName("일정 리마인더 — minutesBefore 가 null 이면 `null분 전 알림` 대신 0분으로 쓴다")
    void eventReminderWithoutMinutesDoesNotPrintNull() {
        User u = user();
        CalendarEvent event = CalendarEvent.createEvent(u, "치과 예약", null,
                CalendarEventType.PERSONAL, null, LocalDateTime.now().minusMinutes(1), null,
                null, null, null, null, null);
        ReflectionTestUtils.setField(event, "rowId", 11L);
        EventReminder reminder = EventReminder.create(event, "PUSH", null);
        ReflectionTestUtils.setField(reminder, "rowId", 12L);
        given(eventReminderRepository.findUnsentRemindersStartingBefore(any())).willReturn(List.of(reminder));

        scheduler().checkEventReminders();

        assertThat(captureOne().message()).isEqualTo("0분 전 알림");
    }

    // ── 알림 설정 토글 (종전: 설정을 하나도 안 봐서 꺼도 계속 만들어졌다) ─────────────────

    @Test
    @DisplayName("예산 알림 — 사용자가 껐으면(notify_budget=N) 배치가 만들지 않는다")
    void budgetAlertIsSkippedWhenUserTurnedItOff() {
        User u = userWithToggles(false, null);
        LocalDate today = serviceClock.today();
        ExpenseBudget budget = ExpenseBudget.createBudget(
                u, category(u, "식비"), 10_000L, today.getYear(), today.getMonthValue());
        ReflectionTestUtils.setField(budget, "rowId", 7L);
        given(expenseBudgetRepository.findAllByYearAndMonth(today.getYear(), today.getMonthValue()))
                .willReturn(List.of(budget));
        // 아래 셋은 "검사가 없었다면 알림이 실제로 터지는" 상황(9,000/10,000, 임계 85%)을 세워 둔 것이다.
        // 검사가 있으면 쓰이지 않으므로 lenient — 그렇다고 빼면 이 테스트는 검사를 되돌려도 통과한다
        // (예산이 없어서 조용한 것과 구분이 안 된다).
        lenient().when(userService.getBudgetAlertThreshold(USER_ID)).thenReturn(85);
        lenient().when(expenseService.getMonthlyExpenseSpendByCategory(
                        eq(USER_ID), eq(today.getYear()), eq(today.getMonthValue())))
                .thenReturn(Map.of(10L, 9_000L));
        lenient().when(notificationRepository.existsByUserAndReferenceAndCreatedAfter(
                eq(USER_ID), any(), anyLong(), any())).thenReturn(false);

        scheduler().checkBudgetAlerts();

        verify(notificationService, never()).createNotification(any());
    }

    @Test
    @DisplayName("일정 리마인더 — 사용자가 껐으면(notify_calendar=N) 만들지 않고 그 리마인더는 소비한다")
    void eventReminderIsSkippedWhenUserTurnedItOff() {
        User u = userWithToggles(null, false);
        CalendarEvent event = CalendarEvent.createEvent(u, "치과 예약", null,
                CalendarEventType.PERSONAL, null, LocalDateTime.now().plusMinutes(1), null,
                null, null, null, null, null);
        ReflectionTestUtils.setField(event, "rowId", 11L);
        EventReminder reminder = EventReminder.create(event, "PUSH", 30);
        ReflectionTestUtils.setField(reminder, "rowId", 12L);
        given(eventReminderRepository.findUnsentRemindersStartingBefore(any())).willReturn(List.of(reminder));

        scheduler().checkEventReminders();

        verify(notificationService, never()).createNotification(any());
        // 안 보낸 채로 두면 매 분 다시 후보로 올라오고, 나중에 토글을 켜는 순간 이미 지나간
        // 일정의 리마인더가 한꺼번에 쏟아진다.
        assertThat(reminder.getIsSent()).isEqualTo(YNType.Y);
    }

    @Test
    @DisplayName("할일 리마인더 — 알림 토글을 다 꺼도 종전대로 생긴다(대응 컬럼이 아직 없다)")
    void todoReminderIgnoresNotificationToggles() {
        User u = user();
        u.updateNotificationPreferences(false, false, false, false, false, false, false, false,
                null, null, null, null, null, null, null);
        LocalDate today = serviceClock.today();
        Todo todo = Todo.createTodo(u, "보고서 제출", "본문", TodoPriority.HIGH, null, today, TodoType.TASK);
        ReflectionTestUtils.setField(todo, "rowId", 3L);
        given(todoRepository.findDueTodosForReminder(today, today.plusDays(1))).willReturn(List.of(todo));
        given(notificationRepository.existsByUserAndReferenceAndCreatedAfter(
                eq(USER_ID), any(), anyLong(), any())).willReturn(false);

        scheduler().checkTodoReminders();

        verify(notificationService).createNotification(any());
    }
}
