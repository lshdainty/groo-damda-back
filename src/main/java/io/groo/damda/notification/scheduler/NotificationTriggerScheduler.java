package io.groo.damda.notification.scheduler;

import io.groo.damda.calendar.domain.CalendarEvent;
import io.groo.damda.calendar.domain.EventReminder;
import io.groo.damda.calendar.repository.EventReminderRepository;
import io.groo.damda.expense.domain.ExpenseBudget;
import io.groo.damda.expense.repository.ExpenseBudgetRepository;
import io.groo.damda.expense.service.ExpenseService;
import io.groo.damda.notification.repository.NotificationRepository;
import io.groo.damda.notification.service.NotificationMessages;
import io.groo.damda.notification.service.NotificationService;
import io.groo.damda.notification.service.dto.NotificationServiceDto;
import io.groo.damda.notification.type.NotificationType;
import io.groo.damda.notification.type.ReferenceType;
import io.groo.damda.todo.domain.Todo;
import io.groo.damda.todo.repository.TodoRepository;
import io.groo.damda.user.service.UserService;
import io.groo.core.time.ServiceClock;
import io.groo.core.time.UserClock;
import io.groo.core.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationTriggerScheduler {
    private final NotificationService notificationService;
    private final NotificationMessages notificationMessages;
    private final NotificationRepository notificationRepository;
    private final EventReminderRepository eventReminderRepository;
    private final ExpenseBudgetRepository expenseBudgetRepository;
    private final ExpenseService expenseService;
    private final ServiceClock serviceClock;
    private final UserClock userClock;
    private final TodoRepository todoRepository;
    private final UserService userService;

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void checkEventReminders() {
        // startDate 는 사용자가 쓴 벽시계([userClock])다. UTC 인 LocalDateTime.now() 와 SQL 에서
        // 직접 비교하면 KST 사용자의 리마인더가 9시간 늦게 나간다 — 후보만 넓게 가져와
        // 소유자 타임존의 "지금"으로 도래를 판정한다.
        List<EventReminder> reminders =
            eventReminderRepository.findUnsentRemindersStartingBefore(serviceClock.now().plusDays(2));

        for (EventReminder reminder : reminders) {
            try {
                CalendarEvent event = reminder.getEvent();
                LocalDateTime nowWall = LocalDateTime.now(
                    userClock.zoneOfTimezone(event.getUser().getTimezone()));
                int minutesBefore = reminder.getMinutesBefore() != null ? reminder.getMinutesBefore() : 0;
                if (event.getStartDate().minusMinutes(minutesBefore).isAfter(nowWall)) {
                    continue;
                }
                // 사용자가 일정 알림을 껐으면 만들지 않는다. 도래한 리마인더는 그래도 소비한다 —
                // 안 그러면 매 분 다시 후보로 올라오고, 나중에 토글을 켜는 순간 이미 지나간 일정의
                // 리마인더가 한꺼번에 쏟아진다. 사용자 객체는 바로 위 타임존 판정에서 이미 초기화돼
                // 있어 조회가 더 들지 않는다.
                if (!event.getUser().allowsCalendarNotification()) {
                    reminder.markSent();
                    log.debug("일정 알림 꺼짐 — 리마인더 생성 생략: reminderId={}, eventId={}",
                        reminder.getRowId(), event.getRowId());
                    continue;
                }
                NotificationServiceDto.CreateCommand command = new NotificationServiceDto.CreateCommand(
                    reminder.getEvent().getUser().getRowId(),
                    NotificationType.EVENT_REMINDER,
                    notificationMessages.eventReminderTitle(reminder.getEvent().getTitle()),
                    notificationMessages.eventReminderMessage(minutesBefore),
                    ReferenceType.CALENDAR_EVENT,
                    reminder.getEvent().getRowId()
                );
                notificationService.createNotification(command);
                reminder.markSent();
                log.info("이벤트 리마인더 알림 전송 완료: reminderId={}, eventId={}",
                    reminder.getRowId(), reminder.getEvent().getRowId());
            } catch (Exception e) {
                log.error("이벤트 리마인더 알림 전송 실패: reminderId={}", reminder.getRowId(), e);
            }
        }
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "${app.scheduler.zone:Asia/Seoul}")
    public void checkBudgetAlerts() {
        log.info("예산 알림 스케줄러 실행 시작");
        // 배치 — 서비스 운영 기준 날짜
        LocalDate now = serviceClock.today();
        int year = now.getYear();
        int month = now.getMonthValue();

        List<ExpenseBudget> budgets = expenseBudgetRepository.findAllByYearAndMonth(year, month);

        // 사용자별 split-aware 카테고리 지출(leaf+부모 롤업) 캐시 — 같은 사용자 예산이 여럿이어도 1회만 집계.
        Map<Long, Map<Long, Long>> spendByUser = new HashMap<>();
        Map<Long, Long> totalByUser = new HashMap<>();

        for (ExpenseBudget budget : budgets) {
            try {
                if (budget.getBudgetAmount() == 0) {
                    continue;
                }

                // 0/음수 예산은 사용률 계산에서 0 나눗셈(ArithmeticException)을 유발 — 방어적으로 건너뜀.
                if (budget.getBudgetAmount() == null || budget.getBudgetAmount() <= 0) {
                    continue;
                }

                Long userRowId = budget.getUser().getRowId();

                // 사용자가 예산 알림을 껐으면 만들지 않는다. 종전엔 설정을 아예 안 봐서 꺼도 계속 쌓였다.
                // 사용자당 조회를 더 하지 않는다 — findAllByYearAndMonth 가 user 를 fetch join 으로
                // 함께 실어 오므로(ExpenseBudgetJpaRepository) 이 객체에서 그냥 읽는다.
                if (!budget.getUser().allowsBudgetNotification()) {
                    continue;
                }

                Long categoryRowId = budget.getCategory() != null ? budget.getCategory().getRowId() : null;

                LocalDate startDate = LocalDate.of(year, month, 1);

                // 카테고리 예산은 자식 지출까지 합산(roll-up, split-aware), 전체(=null)는 월 전체 지출.
                // 분할이 있는 거래는 분할 항목 카테고리로 귀속(거래의 단일 카테고리가 아님).
                long totalSpending;
                if (categoryRowId != null) {
                    Map<Long, Long> userSpend = spendByUser.computeIfAbsent(
                        userRowId, u -> expenseService.getMonthlyExpenseSpendByCategory(u, year, month));
                    totalSpending = userSpend.getOrDefault(categoryRowId, 0L);
                } else {
                    // 전체 예산도 가계부 합계와 같은 규칙 — 예정·환불·카드 이월을 뺀다(23차 12). 날것 합이면
                    // 환불한 거래와 아직 안 온 반복거래까지 더해 화면 이행률과 알림이 갈렸다.
                    totalSpending = totalByUser.computeIfAbsent(
                        userRowId, u -> expenseService.getMonthlyExpenseTotal(u, year, month));
                }

                // 임계값은 사용자 설정(user.budget_alert_threshold, %) 사용 — 미설정 시 85%.
                int thresholdPct = userService.getBudgetAlertThreshold(userRowId);
                // 85% 같은 임계를 double 로 만들면(85/100.0) 이진 오차 때문에 경계에서 한 발 빨리·늦게 터진다.
                // 양변에 100 을 곱해 정수만으로 비교한다 — 금액도 임계도 정수라 이걸로 충분하다.
                if (totalSpending * 100L >= (long) budget.getBudgetAmount() * thresholdPct) {
                    // create_at 은 [UTC] — 서비스 기준 자정(벽시계)을 UTC 로 옮겨 비교한다.
                    // naive 하게 넘기면 경계가 9시간 밀려, 크론 시각이 바뀌면 중복 알림이 샌다.
                    boolean alreadyNotified = notificationRepository.existsByUserAndReferenceAndCreatedAfter(
                        userRowId, ReferenceType.EXPENSE_BUDGET, budget.getRowId(),
                        TimeUtils.toUtc(startDate.atStartOfDay(), serviceClock.zone().getId()));

                    if (!alreadyNotified) {
                        long limit = budget.getBudgetAmount();
                        String categoryName = budget.getCategory() != null
                            ? budget.getCategory().getCategoryName()
                            : notificationMessages.budgetCategoryAll();

                        // 문구는 거래 저장 직후 알림(ExpenseServiceImpl)과 같은 키를 쓴다.
                        // 종전엔 같은 상황을 두 문장·두 말투로 알렸다(QA 2026-09-04 #76) —
                        //   여기  "식비 예산 초과 경고" / "식비 카테고리 예산의 90%를 사용했습니다."
                        //   거기  "식비 예산 90% 사용" / "식비 예산의 90%를 사용했어요 (…)."
                        // 임계는 넘었지만 아직 초과가 아닌 구간까지 "초과 경고" 라 부르던 것도 여기서 갈린다.
                        // 한도와 같으면 초과가 아니다(2026-11-11) — 거래 저장 경로(ExpenseServiceImpl)와 같은 규칙.
                        boolean over = totalSpending > limit;
                        int pct = NotificationMessages.usagePercent(totalSpending, limit);

                        NotificationServiceDto.CreateCommand command = new NotificationServiceDto.CreateCommand(
                            userRowId,
                            NotificationType.BUDGET_ALERT,
                            over ? notificationMessages.budgetOverTitle(categoryName)
                                 : notificationMessages.budgetWarnTitle(categoryName, pct),
                            over ? notificationMessages.budgetOverMessage(categoryName, limit, totalSpending)
                                 : notificationMessages.budgetWarnMessage(categoryName, pct, totalSpending, limit),
                            ReferenceType.EXPENSE_BUDGET,
                            budget.getRowId()
                        );
                        notificationService.createNotification(command);
                        log.info("예산 알림 전송 완료: userRowId={}, budgetId={}, spent={}, limit={}, over={}",
                            userRowId, budget.getRowId(), totalSpending, limit, over);
                    }
                }
            } catch (Exception e) {
                log.error("예산 알림 처리 실패: budgetId={}", budget.getRowId(), e);
            }
        }
        log.info("예산 알림 스케줄러 실행 완료");
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "${app.scheduler.zone:Asia/Seoul}")
    public void checkTodoReminders() {
        log.info("할일 리마인더 스케줄러 실행 시작");
        // 배치 — 서비스 운영 기준 날짜
        LocalDate today = serviceClock.today();
        LocalDate tomorrow = today.plusDays(1);

        // 예산·일정과 달리 여기엔 끄는 토글이 없다 — users 에 대응 컬럼(notify_todo)이 없어서다.
        // 빠뜨린 게 아니라 아직 없는 것이다. 컬럼이 생기면 위 둘과 같은 자리에서 본다.
        List<Todo> todos = todoRepository.findDueTodosForReminder(today, tomorrow);

        for (Todo todo : todos) {
            try {
                Long userRowId = todo.getUser().getRowId();

                // create_at 은 [UTC] — 서비스 기준 자정(벽시계)을 UTC 로 옮겨 비교한다.
                boolean alreadyNotified = notificationRepository.existsByUserAndReferenceAndCreatedAfter(
                    userRowId, ReferenceType.TODO, todo.getRowId(),
                    TimeUtils.toUtc(today.atStartOfDay(), serviceClock.zone().getId()));

                if (!alreadyNotified) {
                    String message = notificationMessages.todoDueMessage(todo.getDueDate().equals(today));

                    NotificationServiceDto.CreateCommand command = new NotificationServiceDto.CreateCommand(
                        userRowId,
                        NotificationType.TODO_REMINDER,
                        todo.getTitle(),
                        message,
                        ReferenceType.TODO,
                        todo.getRowId()
                    );
                    notificationService.createNotification(command);
                    log.info("할일 리마인더 알림 전송 완료: userRowId={}, todoId={}", userRowId, todo.getRowId());
                }
            } catch (Exception e) {
                log.error("할일 리마인더 알림 처리 실패: todoId={}", todo.getRowId(), e);
            }
        }
        log.info("할일 리마인더 스케줄러 실행 완료");
    }
}
