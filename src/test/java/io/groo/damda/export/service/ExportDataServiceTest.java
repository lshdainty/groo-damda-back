package io.groo.damda.export.service;

import io.groo.core.time.ServiceClock;
import io.groo.core.time.UserClock;
import io.groo.core.time.UserZoneProvider;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.calendar.domain.CalendarEvent;
import io.groo.damda.calendar.repository.CalendarEventRepository;
import io.groo.damda.calendar.type.CalendarEventType;
import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.domain.ExpenseBudget;
import io.groo.damda.expense.repository.ExpenseBudgetRepository;
import io.groo.damda.expense.repository.ExpenseCategoryRepository;
import io.groo.damda.expense.repository.ExpenseRepository;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.export.repository.ExportCountRepository;
import io.groo.damda.export.type.ExportType;
import io.groo.damda.memo.domain.Memo;
import io.groo.damda.memo.repository.MemoRepository;
import io.groo.damda.todo.domain.Todo;
import io.groo.damda.todo.repository.TodoRepository;
import io.groo.damda.todo.type.TodoPriority;
import io.groo.damda.todo.type.TodoType;
import io.groo.damda.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 내보내기 표의 <b>일시 열</b> 규칙 검증 (QA #51).
 *
 * <p>지키는 계약은 두 가지다.
 * <ol>
 *   <li>{@code [userClock]} 열(거래일·일정 시작/종료)은 <b>변환하지 않는다</b> — 이미 사용자 벽시계다.
 *       한 번 더 변환하면 자정 근처가 하루 밀린다.</li>
 *   <li>{@code [UTC]} 열(메모 생성일·할 일 완료일)만 사용자 타임존으로 바꾼다 — 같은 ZIP 안에서
 *       9시간이 어긋나던 원인.</li>
 * </ol>
 * 형식은 두 경우 모두 {@code yyyy-MM-dd HH:mm}(날짜 전용 열은 {@code yyyy-MM-dd}).
 */
@ExtendWith(MockitoExtension.class)
class ExportDataServiceTest {

    @Mock private ExpenseRepository expenseRepository;
    @Mock private ExpenseCategoryRepository expenseCategoryRepository;
    @Mock private ExpenseBudgetRepository expenseBudgetRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private MemoRepository memoRepository;
    @Mock private CalendarEventRepository calendarEventRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private ExportCountRepository exportCountRepository;

    private static final long USER_ID = 7L;
    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    /** 사용자 타임존을 바꿔 가며 같은 데이터를 내보내 본다 — 값이 하드코딩이 아님을 증명하기 위해. */
    private ExportDataService sut(UserZoneProvider zoneProvider) {
        UserClock userClock = new UserClock(zoneProvider, new ServiceClock("Asia/Seoul"));
        return new ExportDataService(
            expenseRepository, expenseCategoryRepository, expenseBudgetRepository, assetRepository,
            memoRepository, calendarEventRepository, todoRepository, exportCountRepository, userClock);
    }

    private ExportDataService sutInSeoul() {
        return sut(rowId -> ZoneId.of("Asia/Seoul"));
    }

    private User user() {
        User u = User.createUser(null, "tester", "테스터", "tester@porest.com");
        ReflectionTestUtils.setField(u, "rowId", USER_ID);
        return u;
    }

    private Memo memo(LocalDateTime createAtUtc) {
        Memo m = Memo.createMemo(user(), "회의록", "내용", null, null, "#000000");
        ReflectionTestUtils.setField(m, "createAt", createAtUtc);
        return m;
    }

    private Todo todo(LocalDate dueDate, LocalDateTime completedAtUtc) {
        Todo t = Todo.createTodo(user(), "장보기", "우유", TodoPriority.HIGH, "생활", dueDate, TodoType.TASK);
        ReflectionTestUtils.setField(t, "completedAt", completedAtUtc);
        return t;
    }

    private Expense expense(LocalDateTime expenseDate) {
        return Expense.createExpense(user(), null, null, ExpenseType.EXPENSE, 1000L, "커피",
            expenseDate, "카페", "CARD", null, null, null, null);
    }

    private CalendarEvent event(LocalDateTime start, LocalDateTime end) {
        return CalendarEvent.createEvent(user(), "회의", "설명", CalendarEventType.WORK, null,
            start, end, null, null, "회의실", null, null);
    }

    // ── [UTC] 열: 사용자 타임존으로 변환 ──────────────────────────────
    @Nested
    @DisplayName("[UTC] 열 — toUserZone 후 출력")
    class UtcColumns {

        @Test
        @DisplayName("메모 생성일 — UTC 00:01:36.625966 → KST 09:01 (마이크로초 제거)")
        void memoCreateAtConvertedToUserZone() {
            given(memoRepository.findAllByUser(USER_ID, null))
                .willReturn(List.of(memo(LocalDateTime.of(2026, 9, 3, 0, 1, 36, 625_966_000))));

            ExportTable t = sutInSeoul().buildTable(ExportType.MEMO, USER_ID, START, END, false);

            assertThat(t.headers().get(3)).isEqualTo("생성일");
            assertThat(t.rows().get(0).get(3)).isEqualTo("2026-09-03 09:01");
        }

        @Test
        @DisplayName("할 일 완료일 — 날짜가 넘어가는 경계(UTC 09-02 15:00 → KST 09-03 00:00)")
        void todoCompletedAtCrossesDate() {
            given(todoRepository.findByUserAndDueDateBetween(USER_ID, START, END))
                .willReturn(List.of(todo(LocalDate.of(2026, 9, 3), LocalDateTime.of(2026, 9, 2, 15, 0))));

            ExportTable t = sutInSeoul().buildTable(ExportType.TODO, USER_ID, START, END, false);

            assertThat(t.headers().get(6)).isEqualTo("완료일");
            assertThat(t.rows().get(0).get(6)).isEqualTo("2026-09-03 00:00");
        }

        @Test
        @DisplayName("타임존은 사용자마다 해석된다 — UTC 사용자에게는 변환 없이 그대로")
        void conversionFollowsUserZone() {
            given(memoRepository.findAllByUser(USER_ID, null))
                .willReturn(List.of(memo(LocalDateTime.of(2026, 9, 3, 0, 1, 36, 625_966_000))));

            ExportTable t = sut(rowId -> ZoneId.of("UTC"))
                .buildTable(ExportType.MEMO, USER_ID, START, END, false);

            assertThat(t.rows().get(0).get(3)).isEqualTo("2026-09-03 00:01");
        }

        @Test
        @DisplayName("사용자 타임존을 못 구하면 서비스 기준(Asia/Seoul)으로 폴백한다")
        void fallsBackToServiceZone() {
            given(memoRepository.findAllByUser(USER_ID, null))
                .willReturn(List.of(memo(LocalDateTime.of(2026, 9, 3, 0, 1, 36, 625_966_000))));

            ExportTable t = sut(rowId -> null).buildTable(ExportType.MEMO, USER_ID, START, END, false);

            assertThat(t.rows().get(0).get(3)).isEqualTo("2026-09-03 09:01");
        }

        @Test
        @DisplayName("완료되지 않은 할 일 — 완료일은 빈 문자열")
        void nullCompletedAtIsBlank() {
            given(todoRepository.findByUserAndDueDateBetween(USER_ID, START, END))
                .willReturn(List.of(todo(LocalDate.of(2026, 9, 3), null)));

            ExportTable t = sutInSeoul().buildTable(ExportType.TODO, USER_ID, START, END, false);

            assertThat(t.rows().get(0).get(6)).isEmpty();
        }
    }

    // ── [userClock] 열: 변환하지 않고 형식만 통일 ────────────────────
    @Nested
    @DisplayName("[userClock] 열 — 변환 없이 형식만 통일")
    class WallClockColumns {

        @Test
        @DisplayName("거래일 — 벽시계 09:22 는 09:22 그대로(9시간 더 밀지 않는다), 초·나노는 잘린다")
        void expenseDateNotConverted() {
            given(expenseRepository.findByDateRange(USER_ID, START, END))
                .willReturn(List.of(expense(LocalDateTime.of(2026, 9, 3, 9, 22, 33, 500_000_000))));

            ExportTable t = sutInSeoul().buildTable(ExportType.EXPENSE, USER_ID, START, END, false);

            assertThat(t.headers().get(0)).isEqualTo("날짜");
            assertThat(t.rows().get(0).get(0)).isEqualTo("2026-09-03 09:22");
        }

        /**
         * 환불된 거래도 <b>행으로는 실린다</b> — 받아 간 자료가 화면과 달라지면 안 된다.
         *
         * <p>다만 합계에는 안 들어가므로 그 사실을 칸으로 알려 준다. 칸이 없으면 사용자가
         * 스프레드시트에서 직접 더했을 때 앱이 보여 준 합계와 어긋난다(설계 5절).
         */
        @Test
        @DisplayName("거래 — 환불된 행은 '환불' 칸이 Y 다")
        void refundedRowIsMarked() {
            Expense refunded = expense(LocalDateTime.of(2026, 9, 3, 9, 22));
            refunded.markRefunded(LocalDateTime.of(2026, 9, 5, 10, 0));
            given(expenseRepository.findByDateRange(USER_ID, START, END))
                .willReturn(List.of(expense(LocalDateTime.of(2026, 9, 2, 9, 0)), refunded));

            ExportTable t = sutInSeoul().buildTable(ExportType.EXPENSE, USER_ID, START, END, false);

            assertThat(t.headers()).endsWith("환불");
            assertThat(t.rows()).hasSize(2);
            assertThat(t.rows().get(0)).endsWith("N");
            assertThat(t.rows().get(1)).endsWith("Y");
        }

        @Test
        @DisplayName("일정 시작·종료 — 벽시계 그대로, 초가 0 이어도 자릿수가 같다")
        void calendarDatesNotConverted() {
            given(calendarEventRepository.findByUserAndDateRange(anyLong(), any(), any()))
                .willReturn(List.of(event(
                    LocalDateTime.of(2026, 9, 3, 9, 0),
                    LocalDateTime.of(2026, 9, 3, 10, 30, 15))));

            ExportTable t = sutInSeoul().buildTable(ExportType.CALENDAR, USER_ID, START, END, false);

            assertThat(t.headers().get(1)).isEqualTo("시작");
            assertThat(t.headers().get(2)).isEqualTo("종료");
            assertThat(t.rows().get(0).get(1)).isEqualTo("2026-09-03 09:00");
            assertThat(t.rows().get(0).get(2)).isEqualTo("2026-09-03 10:30");
        }

        @Test
        @DisplayName("할 일 마감일 — date 컬럼이라 시:분을 만들지 않는다(yyyy-MM-dd 유지)")
        void todoDueDateStaysDateOnly() {
            given(todoRepository.findByUserAndDueDateBetween(USER_ID, START, END))
                .willReturn(List.of(todo(LocalDate.of(2026, 9, 3), null)));

            ExportTable t = sutInSeoul().buildTable(ExportType.TODO, USER_ID, START, END, false);

            assertThat(t.headers().get(5)).isEqualTo("마감일");
            assertThat(t.rows().get(0).get(5)).isEqualTo("2026-09-03");
        }
    }

    @Nested
    @DisplayName("예산 표 — 기간 폭과 무관하게 한 번만 조회한다(보안 점검 DAM-2)")
    class BudgetTable {

        private ExpenseBudget budget(int year, int month, long amount) {
            return ExpenseBudget.createBudget(user(), null, amount, year, month);
        }

        @Test
        @DisplayName("10년 기간도 저장소 조회는 1번 — 종전엔 달마다(120번) 조회했다")
        void singleQueryForWideRange() {
            given(expenseBudgetRepository.findByUser(USER_ID, null, null)).willReturn(List.of());

            sutInSeoul().buildTable(ExportType.BUDGET, USER_ID,
                LocalDate.of(2017, 1, 1), LocalDate.of(2026, 12, 31), false);

            verify(expenseBudgetRepository, times(1)).findByUser(anyLong(), any(), any());
        }

        @Test
        @DisplayName("기간 밖 달은 빼고, 이른 달부터 싣는다")
        void filtersAndSortsByMonth() {
            // 저장소는 최근 달부터 준다(year desc, month desc)
            given(expenseBudgetRepository.findByUser(USER_ID, null, null)).willReturn(List.of(
                budget(2026, 10, 400L),
                budget(2026, 9, 300L),
                budget(2026, 8, 200L),
                budget(2026, 7, 100L)));

            ExportTable t = sutInSeoul().buildTable(ExportType.BUDGET, USER_ID,
                LocalDate.of(2026, 8, 15), LocalDate.of(2026, 9, 10), false);

            assertThat(t.rows()).extracting(r -> r.get(1) + ":" + r.get(3))
                .containsExactly("8:200", "9:300");
        }
    }
}
