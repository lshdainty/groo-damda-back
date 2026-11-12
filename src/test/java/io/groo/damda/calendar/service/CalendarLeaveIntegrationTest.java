package io.groo.damda.calendar.service;

import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.calendar.repository.UserCalendarMemberRepository;
import io.groo.damda.calendar.service.dto.UserCalendarServiceDto;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 남의 공유 캘린더에서 <b>내가 스스로 나가는</b> 길 — 진짜 권한 검사를 지나는지.
 *
 * <p>desk 이용 해지가 남의 캘린더 멤버인 사람에게 <b>403 으로 통째로 롤백</b>됐다
 * (재인증 티켓만 태우고 아무것도 안 됨, 2026-09-17 QA 실측). 원인은 해지 루틴이
 * {@code removeMember} 를 불렀기 때문이다 — 그건 <b>소유자가 남을 내보내는</b> 길이라
 * {@code validateOwner} 를 지난다.
 *
 * <p><b>단위 테스트는 이걸 못 잡는다.</b> 거기서는 {@code removeMember} 자체가 mock 이라
 * 권한 검사를 아예 지나지 않아 늘 통과했다. 그래서 이 검증만 진짜 컨텍스트에 붙인다 —
 * mock 이 아닌 서비스가 실제로 권한 검사를 지나야 의미가 있다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("캘린더 나가기(권한 검사 실통과)")
class CalendarLeaveIntegrationTest {

    @Autowired private UserCalendarService userCalendarService;
    @Autowired private UserCalendarMemberRepository memberRepository;
    @Autowired private UserRepository userRepository;
    // QueryDsl 리포지토리의 save 는 `entityManager.persist` 라 트랜잭션 없이는 못 쓴다.
    @Autowired private TransactionTemplate tx;

    private Long ownerRowId;
    private Long memberRowId;
    private Long calendarId;

    private Long newUser() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(st -> userRepository.save(
                User.createUser(null, "u_" + tag, "이름", tag + "@example.com")).getRowId());
    }

    /** 멤버 행이 살아 있나 — 읽기도 EntityManager 가 필요해 트랜잭션 안에서 본다. */
    private boolean isMember(Long calId, Long userRowId) {
        return Boolean.TRUE.equals(tx.execute(st ->
                memberRepository.findByCalendarAndUser(calId, userRowId).isPresent()));
    }

    @BeforeEach
    void setUp() {
        ownerRowId = newUser();
        memberRowId = newUser();
        tx.executeWithoutResult(st -> {
            // 첫 캘린더는 기본 캘린더가 되므로, 공유용으로 하나 더 만든다.
            userCalendarService.getOrCreateDefault(ownerRowId);
            UserCalendarServiceDto.CalendarInfo shared = userCalendarService.createCalendar(
                    new UserCalendarServiceDto.CreateCommand(ownerRowId, "같이 보는 일정", "#2c70bf"));
            calendarId = shared.rowId();
            String code = userCalendarService.regenerateInviteCode(calendarId, ownerRowId);
            userCalendarService.joinByInviteCode(memberRowId, code, "127.0.0.1");
        });
        assertThat(isMember(calendarId, memberRowId)).isTrue();
    }

    @Test
    @DisplayName("멤버는 소유자 권한 없이 스스로 나간다 — 해지가 여기서 403 으로 멈추면 안 된다")
    void memberCanLeaveWithoutOwnerRights() {
        tx.executeWithoutResult(st ->
                userCalendarService.leaveCalendar(calendarId, memberRowId));

        assertThat(isMember(calendarId, memberRowId)).isFalse();
    }

    @Test
    @DisplayName("옛 경로(removeMember)는 여전히 소유자만 쓴다 — 이 403 이 사고의 원인이었다")
    void removeMemberStillRequiresOwner() {
        Long memberId = tx.execute(st -> memberRepository
                .findByCalendarAndUser(calendarId, memberRowId).orElseThrow().getRowId());

        assertThatThrownBy(() -> tx.executeWithoutResult(st ->
                userCalendarService.removeMember(calendarId, memberId, memberRowId)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("소유자는 나갈 수 없다 — 나가면 그 캘린더에 주인이 없어진다")
    void ownerCannotLeave() {
        assertThatThrownBy(() -> tx.executeWithoutResult(st ->
                userCalendarService.leaveCalendar(calendarId, ownerRowId)))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("멤버가 아니면 조용히 아무 일도 안 한다 — 두 번 불려도 해지가 깨지지 않는다")
    void leavingTwiceIsHarmless() {
        Long stranger = newUser();
        tx.executeWithoutResult(st -> {
            userCalendarService.leaveCalendar(calendarId, memberRowId);
            userCalendarService.leaveCalendar(calendarId, memberRowId);
            userCalendarService.leaveCalendar(calendarId, stranger);
        });

        assertThat(isMember(calendarId, memberRowId)).isFalse();
    }
}
