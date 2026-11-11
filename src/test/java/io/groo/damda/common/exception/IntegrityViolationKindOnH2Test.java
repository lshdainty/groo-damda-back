package io.groo.damda.common.exception;

import io.groo.core.exception.IntegrityViolations;
import io.groo.core.exception.IntegrityViolationKind;

import io.groo.damda.calendar.domain.EventLabel;
import io.groo.damda.calendar.repository.EventLabelQueryDslRepository;
import io.groo.damda.calendar.repository.EventLabelRepository;
import io.groo.damda.common.config.QueryDslConfig;
import io.groo.damda.common.config.database.JpaAuditingConfig;
import io.groo.damda.common.config.database.LoginUserAuditorAware;
import io.groo.damda.todo.domain.Todo;
import io.groo.damda.todo.domain.TodoTag;
import io.groo.damda.todo.domain.TodoTagMapping;
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
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 판정이 <b>진짜 DB 예외</b>에도 서는지 — 손으로 만든 예외가 아니라 H2 가 실제로 던진 것으로 건다.
 *
 * <p>{@link IntegrityViolationsTest} 는 예외를 조립해 분기표를 고정하고, 여기서는 그 조립이
 * 실물과 같은 모양인지를 확인한다. 둘 중 하나만 있으면 위험하다 — 조립 테스트만 있으면
 * 하이버네이트가 사슬 모양을 바꾸는 날 조용히 통과하고, 실물 테스트만 있으면 H2 에 없는
 * MariaDB 코드가 영영 안 걸린다.
 *
 * <p>{@code PersistenceExceptionTranslationPostProcessor} 를 명시적으로 들이는 이유는
 * {@code @DataJpaTest} 슬라이스가 그것을 자동으로 안 붙이기 때문이다. 운영에서는 부트가
 * 붙여 주고, 그래서 {@code @Repository} 의 flush 가 {@code DataIntegrityViolationException}
 * 으로 나온다 — 서비스 열 곳의 catch 가 기대는 것이 바로 그 번역이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueryDslConfig.class, JpaAuditingConfig.class, LoginUserAuditorAware.class,
        EventLabelQueryDslRepository.class, PersistenceExceptionTranslationPostProcessor.class})
@ActiveProfiles("test")
class IntegrityViolationKindOnH2Test {

    @Autowired private TestEntityManager em;
    @Autowired private EventLabelRepository eventLabelRepository;

    private User persistUser(String id) {
        User user = em.persist(User.createUser(null, id, "테스터", id + "@porest.com"));
        em.flush();
        return user;
    }

    @Test
    @DisplayName("NOT NULL 위반은 NOT_NULL 로 판정된다 — 스프링 번역을 거쳐도 원인 사슬이 남는다")
    void notNullViolation() {
        User user = persistUser("k1");

        // event_label.color 는 NOT NULL. QA #81 이 신고한 calendar_event.event_type 과 같은 부류다.
        // IDENTITY 키라 INSERT 가 save 시점에 나간다 — 그래서 save 와 flush 를 함께 감싼다.
        assertThatThrownBy(() -> {
            eventLabelRepository.save(EventLabel.createLabel(user, "이름", null, 0));
            eventLabelRepository.flush();
        }).satisfies(t -> assertThat(IntegrityViolations.classify(t)).isEqualTo(IntegrityViolationKind.NOT_NULL));
    }

    @Test
    @DisplayName("UNIQUE 위반은 UNIQUE 로 판정된다 — 이것만 409 로 나가야 한다")
    void uniqueViolation() {
        User user = persistUser("k2");
        Todo todo = em.persist(Todo.createTodo(user, "할일", null, TodoPriority.LOW, null, null, TodoType.TASK));
        TodoTag tag = em.persist(TodoTag.createTag(user, "태그", "#111111"));
        em.flush();

        // UK_todo_tag(todo_row_id, tag_row_id) — 엔티티에 선언된 유일성이라 H2 에도 실제로 만들어진다.
        em.persist(TodoTagMapping.create(todo, tag));

        assertThatThrownBy(() -> {
            em.persist(TodoTagMapping.create(todo, tag));
            em.flush();
        }).satisfies(t -> assertThat(IntegrityViolations.classify(t)).isEqualTo(IntegrityViolationKind.UNIQUE));
    }

    /**
     * 제약 <b>이름</b>이 실제로 읽히는지 — 한 테이블에 UNIQUE 가 둘 이상인 곳이 이걸로 갈린다.
     *
     * <p>{@code dutch_pay_participant} 가 그렇다(활성 참가자 이름·활성 결제자). 이름을 못 읽으면
     * 결제자가 부딪힌 요청이 "같은 참가자를 중복으로 추가할 수 없어요" 를 듣는다.
     *
     * <p>정확히 같은지가 아니라 <b>포함</b>으로 보는 이유를 여기서 실측으로 붙든다. H2 가 실제로
     * 돌려준 값은 {@code "PUBLIC.UK_TODO_TAG INDEX PUBLIC.UK_TODO_TAG_INDEX_1"} 이었다 —
     * 우리가 지은 이름 앞에 스키마가 붙고, 대문자로 접히고, 뒤에 인덱스 이름이 따라온다.
     * 같은지로 비교하는 코드는 여기서 조용히 빗나간다.
     */
    @Test
    @DisplayName("제약 이름을 원인 사슬에서 읽어 낸다 — 앞에 뭐가 붙어도 우리가 지은 이름이 들어 있다")
    void constraintNameIsReadable() {
        User user = persistUser("k4");
        Todo todo = em.persist(Todo.createTodo(user, "할일", null, TodoPriority.LOW, null, null, TodoType.TASK));
        TodoTag tag = em.persist(TodoTag.createTag(user, "태그", "#111111"));
        em.flush();
        em.persist(TodoTagMapping.create(todo, tag));

        assertThatThrownBy(() -> {
            em.persist(TodoTagMapping.create(todo, tag));
            em.flush();
        }).satisfies(t -> assertThat(IntegrityViolations.constraintName(t))
                .isNotNull()
                .satisfies(name -> assertThat(name.toLowerCase()).contains("uk_todo_tag")));
    }

    @Test
    @DisplayName("FK 위반은 FOREIGN_KEY 로 판정된다")
    void foreignKeyViolation() {
        User user = persistUser("k3");

        // 없는 이벤트를 가리키는 댓글 — JPA 로는 만들 수 없어 네이티브로 넣는다.
        assertThatThrownBy(() -> em.getEntityManager().createNativeQuery(
                "insert into event_comment (event_row_id, user_row_id, content, is_deleted, version,"
                        + " create_at, modify_at) values (999999, " + user.getRowId()
                        + ", '내용', 'N', 0, current_timestamp, current_timestamp)")
                .executeUpdate())
                .satisfies(t -> assertThat(IntegrityViolations.classify(t)).isEqualTo(IntegrityViolationKind.FOREIGN_KEY));
    }
}
