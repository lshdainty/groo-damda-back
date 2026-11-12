package io.groo.damda.expense.repository;

import io.groo.damda.common.config.QueryDslConfig;
import io.groo.damda.common.config.database.JpaAuditingConfig;
import io.groo.damda.common.config.database.LoginUserAuditorAware;
import io.groo.core.type.YNType;
import io.groo.damda.calendar.domain.CalendarEvent;
import io.groo.damda.calendar.type.CalendarEventType;
import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.domain.ExpenseCategory;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.todo.domain.Todo;
import io.groo.damda.todo.type.TodoPriority;
import io.groo.damda.todo.type.TodoType;
import io.groo.damda.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Expense QueryDsl 리포 슬라이스 테스트 — H2 에서 카테고리 존재여부·롤업 합산·필터 조회 검증.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueryDslConfig.class, JpaAuditingConfig.class, LoginUserAuditorAware.class,
        ExpenseQueryDslRepository.class})
@ActiveProfiles("test")
class ExpenseRepositoryTest {

    @Autowired private TestEntityManager em;
    @Autowired private ExpenseRepository repository;

    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);

    private User persistUser() {
        return em.persist(User.createUser(null, "u1", "테스터", "u1@porest.com"));
    }

    private ExpenseCategory persistCategory(User user, String name, ExpenseCategory parent) {
        return em.persist(ExpenseCategory.createCategory(user, name, "tag", "#fff", ExpenseType.EXPENSE, parent));
    }

    private Expense persistExpense(User user, ExpenseCategory cat, long amount) {
        return em.persist(Expense.createExpense(user, cat, null, ExpenseType.EXPENSE, amount,
                "거래", LocalDateTime.of(2026, 6, 15, 12, 0), "가게", "CARD", null,
            null,
            null,
            null));
    }

    private Expense persistExpenseFull(User user, ExpenseCategory cat, long amount,
                                       String description, LocalDateTime when) {
        return em.persist(Expense.createExpense(user, cat, null, ExpenseType.EXPENSE, amount,
                description, when, "가게", "CARD", null,
            null,
            null,
            null));
    }

    @Test
    @DisplayName("existsByCategory — 거래가 있으면 true, 없으면 false")
    void existsByCategory() {
        User user = persistUser();
        ExpenseCategory used = persistCategory(user, "식비", null);
        ExpenseCategory empty = persistCategory(user, "교통", null);
        persistExpense(user, used, 10_000L);
        em.flush();
        em.clear();

        assertThat(repository.existsByCategory(used.getRowId())).isTrue();
        assertThat(repository.existsByCategory(empty.getRowId())).isFalse();
    }

    @Test
    @DisplayName("sumAmountByCategoryRollup — 부모 + 자식 카테고리 지출을 합산한다")
    void sumRollup() {
        User user = persistUser();
        ExpenseCategory parent = persistCategory(user, "건강", null);
        ExpenseCategory child = persistCategory(user, "의료비", parent);
        persistExpense(user, parent, 5_000L);   // 부모 직접 지출
        persistExpense(user, child, 10_000L);   // 자식 지출 → 부모로 roll-up
        em.flush();
        em.clear();

        long sum = repository.sumAmountByCategoryRollup(
                user.getRowId(), parent.getRowId(), ExpenseType.EXPENSE, START, END);

        assertThat(sum).isEqualTo(15_000L);
    }

    @Test
    @DisplayName("findByUser — categoryRowId 필터로 해당 카테고리 거래만 반환")
    void findByUserCategoryFilter() {
        User user = persistUser();
        ExpenseCategory food = persistCategory(user, "식비", null);
        ExpenseCategory transport = persistCategory(user, "교통", null);
        persistExpense(user, food, 10_000L);
        persistExpense(user, transport, 3_000L);
        em.flush();
        em.clear();

        List<Expense> result = repository.findByUser(
                user.getRowId(), food.getRowId(), ExpenseType.EXPENSE, START, END);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCategory().getCategoryName()).isEqualTo("식비");
    }

    @Test
    @DisplayName("search — 키워드(설명)로 부분일치 필터")
    void searchByKeyword() {
        User user = persistUser();
        ExpenseCategory cat = persistCategory(user, "식비", null);
        persistExpenseFull(user, cat, 5_000L, "스타벅스 커피", LocalDateTime.of(2026, 6, 10, 9, 0));
        persistExpenseFull(user, cat, 1_200L, "버스 요금", LocalDateTime.of(2026, 6, 11, 9, 0));
        em.flush();
        em.clear();

        List<Expense> result = repository.search(user.getRowId(), null, null, null,
                "커피", null, null, null, START, END);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDescription()).contains("커피");
    }

    @Test
    @DisplayName("search — 키워드는 거래처에도 걸린다(메모 없이 거래처만 적은 거래, QA 30 11)")
    void searchKeywordMatchesMerchant() {
        User user = persistUser();
        ExpenseCategory cat = persistCategory(user, "식비", null);
        em.persist(Expense.createExpense(user, cat, null, ExpenseType.EXPENSE, 4_500L,
            null, LocalDateTime.of(2026, 6, 12, 9, 0), "스타벅스 강남점", "CARD", null, null, null, null));
        persistExpenseFull(user, cat, 1_200L, "버스 요금", LocalDateTime.of(2026, 6, 11, 9, 0));
        em.flush();
        em.clear();

        List<Expense> result = repository.search(user.getRowId(), null, null, null,
                "스타벅스", null, null, null, START, END);

        assertThat(result).extracting(Expense::getMerchant).containsExactly("스타벅스 강남점");
    }

    @Test
    @DisplayName("search — 금액 범위(min~max)로 필터")
    void searchByAmountRange() {
        User user = persistUser();
        ExpenseCategory cat = persistCategory(user, "식비", null);
        persistExpenseFull(user, cat, 1_000L, "a", LocalDateTime.of(2026, 6, 10, 9, 0));
        persistExpenseFull(user, cat, 5_000L, "b", LocalDateTime.of(2026, 6, 10, 9, 0));
        persistExpenseFull(user, cat, 10_000L, "c", LocalDateTime.of(2026, 6, 10, 9, 0));
        em.flush();
        em.clear();

        List<Expense> result = repository.search(user.getRowId(), null, null, null,
                null, null, 3_000L, 8_000L, START, END);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAmount()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("search — 기간 경계는 포함(start 00:00 ~ end 23:59), 범위 밖은 제외")
    void searchDateBoundaryInclusive() {
        User user = persistUser();
        ExpenseCategory cat = persistCategory(user, "식비", null);
        persistExpenseFull(user, cat, 1_000L, "start경계", LocalDateTime.of(2026, 6, 1, 0, 0));
        persistExpenseFull(user, cat, 2_000L, "end경계", LocalDateTime.of(2026, 6, 30, 23, 0));
        persistExpenseFull(user, cat, 3_000L, "범위전", LocalDateTime.of(2026, 5, 31, 12, 0));
        em.flush();
        em.clear();

        List<Expense> result = repository.search(user.getRowId(), null, null, null,
                null, null, null, null, START, END);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Expense::getDescription)
                .containsExactlyInAnyOrder("start경계", "end경계");
    }

    @Test
    @DisplayName("findByTodo — 같은 할일에 붙은 지출이라도 요청한 사용자 것만 (보안 점검 DAM-1)")
    void findByTodoOnlyOwn() {
        User me = persistUser();
        User other = em.persist(User.createUser(null, "u2", "남", "u2@porest.com"));
        Todo todo = em.persist(Todo.createTodo(me, "장보기", null, TodoPriority.MEDIUM, null, null, TodoType.TASK));
        Expense mine = persistExpense(me, persistCategory(me, "식비", null), 10_000L);
        Expense theirs = persistExpense(other, persistCategory(other, "식비", null), 99_000L);
        mine.setTodo(todo);
        theirs.setTodo(todo);
        em.flush();
        em.clear();

        assertThat(repository.findByTodo(me.getRowId(), todo.getRowId()))
                .extracting(Expense::getRowId).containsExactly(mine.getRowId());
        assertThat(repository.findByTodo(other.getRowId(), todo.getRowId()))
                .extracting(Expense::getRowId).containsExactly(theirs.getRowId());
    }

    @Test
    @DisplayName("findByCalendarEvent — 같은 일정에 붙은 지출이라도 요청한 사용자 것만 (보안 점검 DAM-1)")
    void findByCalendarEventOnlyOwn() {
        User me = persistUser();
        User other = em.persist(User.createUser(null, "u2", "남", "u2@porest.com"));
        CalendarEvent event = em.persist(CalendarEvent.createEvent(me, "회식", null, CalendarEventType.PERSONAL, null,
                LocalDateTime.of(2026, 6, 15, 19, 0), LocalDateTime.of(2026, 6, 15, 21, 0),
                YNType.N, null, null, null, null));
        Expense mine = persistExpense(me, persistCategory(me, "식비", null), 10_000L);
        Expense theirs = persistExpense(other, persistCategory(other, "식비", null), 99_000L);
        mine.setCalendarEvent(event);
        theirs.setCalendarEvent(event);
        em.flush();
        em.clear();

        assertThat(repository.findByCalendarEvent(me.getRowId(), event.getRowId()))
                .extracting(Expense::getRowId).containsExactly(mine.getRowId());
        assertThat(repository.findByCalendarEvent(999_999L, event.getRowId())).isEmpty();
    }
}
