package io.groo.damda.calendar.repository;

import io.groo.damda.calendar.domain.UserCalendar;
import io.groo.damda.common.config.QueryDslConfig;
import io.groo.damda.common.config.database.JpaAuditingConfig;
import io.groo.damda.common.config.database.LoginUserAuditorAware;
import io.groo.damda.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserCalendar QueryDsl 리포 슬라이스 테스트.
 * 커스텀 조건: 사용자 스코프 + soft-delete 제외 정렬, id IN 조회(빈 가드),
 * 초대코드 조회, 기본 캘린더(is_default=Y) 조회.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueryDslConfig.class, JpaAuditingConfig.class, LoginUserAuditorAware.class,
        UserCalendarQueryDslRepository.class})
@ActiveProfiles("test")
class UserCalendarRepositoryTest {

    @Autowired private TestEntityManager em;
    @Autowired private UserCalendarRepository repository;

    private User persistUser(String userId) {
        return em.persist(User.createUser(null, userId, "테스터", userId + "@porest.com"));
    }

    private UserCalendar persistCalendar(User user, String name, int sortOrder, boolean isDefault) {
        return em.persist(UserCalendar.createCalendar(user, name, null, sortOrder, isDefault));
    }

    @Test
    @DisplayName("findById — soft delete 된 캘린더는 조회되지 않는다")
    void findByIdExcludesSoftDeleted() {
        User user = persistUser("u1");
        UserCalendar active = persistCalendar(user, "내 캘린더", 0, true);
        UserCalendar deleted = persistCalendar(user, "지운 캘린더", 1, false);
        deleted.deleteCalendar();
        em.flush();
        em.clear();

        assertThat(repository.findById(active.getRowId())).isPresent();
        assertThat(repository.findById(deleted.getRowId())).isEmpty();
    }

    @Test
    @DisplayName("findAllByUser — 본인 미삭제 캘린더만 sortOrder→rowId 오름차순으로 반환")
    void findAllByUserScopeSoftDeleteOrder() {
        User user = persistUser("u1");
        User other = persistUser("u2");
        persistCalendar(user, "A(order2)", 2, false);
        persistCalendar(user, "B(order0)", 0, true);
        persistCalendar(user, "C(order0)", 0, false); // sortOrder 동률 → rowId tiebreak
        UserCalendar deleted = persistCalendar(user, "삭제", 0, false);
        deleted.deleteCalendar();
        persistCalendar(other, "남의것", 0, true); // 다른 사용자 → 제외
        em.flush();
        em.clear();

        List<UserCalendar> result = repository.findAllByUser(user.getRowId());

        assertThat(result).extracting(UserCalendar::getCalendarName)
                .containsExactly("B(order0)", "C(order0)", "A(order2)");
    }

    @Test
    @DisplayName("findAllByIds — 빈 목록은 빈 리스트, IN 목록은 소유자 무관·미삭제만 정렬 반환")
    void findAllByIdsInEmptyGuardAndSoftDelete() {
        User user = persistUser("u1");
        User other = persistUser("u2");
        UserCalendar mine = persistCalendar(user, "내것(order1)", 1, true);
        UserCalendar others = persistCalendar(other, "남의것(order0)", 0, true); // IN 이면 소유자 무관 포함
        UserCalendar deleted = persistCalendar(user, "삭제", 0, false);
        deleted.deleteCalendar();
        em.flush();
        em.clear();

        assertThat(repository.findAllByIds(List.of())).isEmpty();

        List<UserCalendar> result = repository.findAllByIds(
                List.of(mine.getRowId(), others.getRowId(), deleted.getRowId()));

        // deleted 제외, sortOrder 오름차순(others:0 → mine:1)
        assertThat(result).extracting(UserCalendar::getCalendarName)
                .containsExactly("남의것(order0)", "내것(order1)");
    }

    @Test
    @DisplayName("findByInviteCode — 초대코드로 조회하며 soft delete 는 제외")
    void findByInviteCode() {
        User user = persistUser("u1");
        UserCalendar cal = persistCalendar(user, "공유 캘린더", 0, true);
        UserCalendar deleted = persistCalendar(user, "지운 공유", 1, false);
        // 초대 코드는 공유를 켤 때만 생긴다(DAM-4)
        cal.issueInviteCode();
        deleted.issueInviteCode();
        deleted.deleteCalendar();
        em.flush();
        String activeCode = cal.getInviteCode();
        String deletedCode = deleted.getInviteCode();
        em.clear();

        assertThat(repository.findByInviteCode(activeCode)).isPresent();
        assertThat(repository.findByInviteCode(deletedCode)).isEmpty();     // soft-delete 제외
        assertThat(repository.findByInviteCode("NOPE1234")).isEmpty();      // 없는 코드
        assertThat(activeCode).hasSize(32).matches("[0-9A-F]{32}");        // 128비트 16진수
    }

    @Test
    @DisplayName("findDefaultByUser — is_default=Y 미삭제 캘린더를 반환, 없으면 empty")
    void findDefaultByUser() {
        User user = persistUser("u1");
        User noDefaultUser = persistUser("u2");
        persistCalendar(user, "기본", 0, true);
        persistCalendar(user, "보조", 1, false);
        persistCalendar(noDefaultUser, "비기본", 0, false); // 기본 없음
        em.flush();
        em.clear();

        assertThat(repository.findDefaultByUser(user.getRowId()))
                .get()
                .extracting(UserCalendar::getCalendarName)
                .isEqualTo("기본");
        assertThat(repository.findDefaultByUser(noDefaultUser.getRowId())).isEmpty();
    }

    @Test
    @DisplayName("findDefaultByUser — 기본 캘린더가 soft delete 되면 empty")
    void findDefaultByUserExcludesSoftDeleted() {
        User user = persistUser("u1");
        UserCalendar def = persistCalendar(user, "기본", 0, true);
        def.deleteCalendar();
        em.flush();
        em.clear();

        assertThat(repository.findDefaultByUser(user.getRowId())).isEmpty();
    }
}
