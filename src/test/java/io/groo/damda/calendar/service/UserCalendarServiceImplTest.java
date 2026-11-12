package io.groo.damda.calendar.service;

import io.groo.damda.calendar.service.dto.UserCalendarServiceDto;

import static org.mockito.Mockito.verify;

import static org.mockito.Mockito.times;

import static org.mockito.Mockito.never;

import static org.mockito.ArgumentMatchers.any;

import io.groo.core.exception.BusinessRuleViolationException;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.core.type.YNType;
import io.groo.damda.calendar.domain.UserCalendar;
import io.groo.damda.calendar.domain.UserCalendarMember;
import io.groo.damda.calendar.repository.CalendarEventRepository;
import io.groo.damda.calendar.repository.UserCalendarMemberRepository;
import io.groo.damda.calendar.repository.UserCalendarRepository;
import io.groo.damda.calendar.type.CalendarRole;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 사용자 캘린더(공유) 서비스 회귀 방지 단위 테스트 —
 * 기본 캘린더 삭제·숨김 금지, 소유권, 소유자 제거/OWNER 권한변경 금지, 중복 가입 금지.
 */
@ExtendWith(MockitoExtension.class)
class UserCalendarServiceImplTest {

    @Mock private UserCalendarRepository userCalendarRepository;
    @Mock private UserCalendarMemberRepository memberRepository;
    @Mock private CalendarEventRepository calendarEventRepository;
    @Mock private UserRepository userRepository;
    @Mock private CalendarMembershipValidator membershipValidator;
    @Mock private CalendarJoinRateLimiter joinRateLimiter;

    @InjectMocks private UserCalendarServiceImpl sut;

    private static final long CAL_ID = 100L;
    private static final long MEMBER_ID = 7L;
    private static final long USER_ID = 1L;

    private User user(long rowId) {
        User u = User.createUser(null, "tester", "테스터", "tester@porest.com");
        ReflectionTestUtils.setField(u, "rowId", rowId);
        return u;
    }

    /** 토글은 실제 값이 뒤집히는지가 요점이라 mock 이 아니라 진짜 엔티티를 쓴다(생성 직후 isVisible=Y). */
    private UserCalendar calendar(boolean isDefault) {
        return UserCalendar.createCalendar(user(USER_ID), "내 캘린더", "#2c70bf", 0, isDefault);
    }

    @Test
    @DisplayName("toggleVisibility — 기본 캘린더는 숨길 수 없다(전용 코드 · 400 · 값 그대로)")
    void toggleRejectsDefaultCalendar() {
        UserCalendar calendar = calendar(true);
        given(userCalendarRepository.findById(CAL_ID)).willReturn(Optional.of(calendar));

        assertThatThrownBy(() -> sut.toggleVisibility(CAL_ID, USER_ID))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).getErrorCode())
                // 삭제 거절을 재사용하지 않는다 — "삭제할 수 없어요" 로는 무엇을 하다 막혔는지 알 수 없다.
                .isEqualTo(DeskErrorCode.USER_CALENDAR_DEFAULT_HIDE);

        assertThat(DeskErrorCode.USER_CALENDAR_DEFAULT_HIDE.getHttpStatus())
                .as("숨김 거절은 400 이다")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(DeskErrorCode.USER_CALENDAR_DEFAULT_HIDE.getCode())
                .isNotEqualTo(DeskErrorCode.USER_CALENDAR_DEFAULT_DELETE.getCode());
        // 막았는데 값이 뒤집혀 있으면 저장 시점에 그대로 숨겨진다 — 거절 뒤 상태까지 확인한다.
        assertThat(calendar.getIsVisible()).isEqualTo(YNType.Y);
    }

    @Test
    @DisplayName("toggleVisibility — 일반 캘린더는 종전대로 숨김·표시를 왕복한다")
    void toggleFlipsNonDefaultCalendar() {
        UserCalendar calendar = calendar(false);
        given(userCalendarRepository.findById(CAL_ID)).willReturn(Optional.of(calendar));

        assertThat(sut.toggleVisibility(CAL_ID, USER_ID).isVisible()).isFalse();
        assertThat(calendar.getIsVisible()).isEqualTo(YNType.N);

        assertThat(sut.toggleVisibility(CAL_ID, USER_ID).isVisible()).isTrue();
        assertThat(calendar.getIsVisible()).isEqualTo(YNType.Y);
    }

    @Test
    @DisplayName("toggleVisibility — 읽기 전용 멤버는 바꿀 수 없다(한 값을 모두가 같이 쓰므로 소유자 화면에서도 숨겨지던 자리, 보안 점검 DAM-8)")
    void toggleRejectsReadOnlyMember() {
        UserCalendar calendar = calendar(false);
        UserCalendarMember readOnly = UserCalendarMember.create(calendar, user(MEMBER_ID), CalendarRole.READ);
        given(userCalendarRepository.findById(CAL_ID)).willReturn(Optional.of(calendar));
        given(memberRepository.findByCalendarAndUser(CAL_ID, MEMBER_ID)).willReturn(Optional.of(readOnly));
        // 실제 권한 판정을 태운다 — 서비스가 어느 검증을 부르는지가 요점이다.
        UserCalendarServiceImpl real = new UserCalendarServiceImpl(userCalendarRepository, memberRepository,
                calendarEventRepository, userRepository,
                new CalendarMembershipValidator(memberRepository, userCalendarRepository), joinRateLimiter);

        assertThatThrownBy(() -> real.toggleVisibility(CAL_ID, MEMBER_ID))
                .isInstanceOf(ForbiddenException.class);
        assertThat(calendar.getIsVisible()).isEqualTo(YNType.Y);
    }

    @Test
    @DisplayName("toggleVisibility — 편집 멤버는 종전대로 바꿀 수 있다")
    void toggleAllowsEditMember() {
        UserCalendar calendar = calendar(false);
        UserCalendarMember editor = UserCalendarMember.create(calendar, user(MEMBER_ID), CalendarRole.EDIT);
        given(userCalendarRepository.findById(CAL_ID)).willReturn(Optional.of(calendar));
        given(memberRepository.findByCalendarAndUser(CAL_ID, MEMBER_ID)).willReturn(Optional.of(editor));
        UserCalendarServiceImpl real = new UserCalendarServiceImpl(userCalendarRepository, memberRepository,
                calendarEventRepository, userRepository,
                new CalendarMembershipValidator(memberRepository, userCalendarRepository), joinRateLimiter);

        assertThat(real.toggleVisibility(CAL_ID, MEMBER_ID).isVisible()).isFalse();
    }

    @Test
    @DisplayName("초대 코드는 소유자 응답에만 실린다 — 편집 · 읽기 전용 멤버의 목록에는 없다(보안 점검 DAM-4 잔여)")
    void inviteCodeOnlyForOwner() {
        UserCalendar calendar = calendar(false);
        String code = calendar.issueInviteCode();

        assertThat(UserCalendarServiceDto.CalendarInfo.from(calendar, CalendarRole.OWNER, 3).inviteCode()).isEqualTo(code);
        assertThat(UserCalendarServiceDto.CalendarInfo.from(calendar, CalendarRole.EDIT, 3).inviteCode()).isNull();
        assertThat(UserCalendarServiceDto.CalendarInfo.from(calendar, CalendarRole.READ, 3).inviteCode()).isNull();
    }

    @Test
    @DisplayName("deleteCalendar — 기본 캘린더는 삭제 불가")
    void deleteRejectsDefaultCalendar() {
        UserCalendar calendar = mock(UserCalendar.class);
        given(calendar.getUser()).willReturn(user(USER_ID));
        given(calendar.getIsDefault()).willReturn(YNType.Y);
        given(userCalendarRepository.findById(CAL_ID)).willReturn(Optional.of(calendar));

        assertThatThrownBy(() -> sut.deleteCalendar(CAL_ID, USER_ID))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("deleteCalendar — 남의 캘린더는 삭제 불가")
    void deleteRejectsOthers() {
        UserCalendar calendar = mock(UserCalendar.class);
        given(calendar.getUser()).willReturn(user(999L));
        given(userCalendarRepository.findById(CAL_ID)).willReturn(Optional.of(calendar));

        assertThatThrownBy(() -> sut.deleteCalendar(CAL_ID, USER_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("removeMember — 소유자(OWNER)는 제거 불가")
    void removeMemberRejectsOwner() {
        UserCalendarMember member = mock(UserCalendarMember.class);
        UserCalendar calendar = mock(UserCalendar.class);
        given(calendar.getRowId()).willReturn(CAL_ID);
        given(member.getCalendar()).willReturn(calendar);
        given(member.getPermission()).willReturn(CalendarRole.OWNER);
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));

        assertThatThrownBy(() -> sut.removeMember(CAL_ID, MEMBER_ID, USER_ID))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("removeMember — 다른 캘린더 소속 memberId 는 조작 불가(교차 IDOR)")
    void removeMemberRejectsCrossCalendar() {
        UserCalendarMember member = mock(UserCalendarMember.class);
        UserCalendar otherCalendar = mock(UserCalendar.class);
        given(otherCalendar.getRowId()).willReturn(999L);
        given(member.getCalendar()).willReturn(otherCalendar);
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));

        assertThatThrownBy(() -> sut.removeMember(CAL_ID, MEMBER_ID, USER_ID))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("changeMemberRole — 다른 캘린더 소속 memberId 는 조작 불가(교차 IDOR)")
    void changeMemberRoleRejectsCrossCalendar() {
        UserCalendarMember member = mock(UserCalendarMember.class);
        UserCalendar otherCalendar = mock(UserCalendar.class);
        given(otherCalendar.getRowId()).willReturn(999L);
        given(member.getCalendar()).willReturn(otherCalendar);
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));

        assertThatThrownBy(() -> sut.changeMemberRole(CAL_ID, MEMBER_ID, CalendarRole.EDIT, USER_ID))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("changeMemberRole — OWNER 권한으로는 변경 불가")
    void changeRoleRejectsOwnerPermission() {
        assertThatThrownBy(() -> sut.changeMemberRole(CAL_ID, MEMBER_ID, CalendarRole.OWNER, USER_ID))
                .isInstanceOf(InvalidValueException.class);
    }

    private static final String CODE = "0123456789ABCDEF0123456789ABCDEF";
    private static final String IP = "203.0.113.7";

    @Test
    @DisplayName("joinByInviteCode — 이미 멤버면 중복 가입 불가")
    void joinRejectsAlreadyMember() {
        given(joinRateLimiter.isAllowed(USER_ID, IP)).willReturn(true);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));
        UserCalendar calendar = mock(UserCalendar.class);
        given(calendar.getRowId()).willReturn(50L);
        given(userCalendarRepository.findByInviteCode(CODE)).willReturn(Optional.of(calendar));
        UserCalendarMember existing = mock(UserCalendarMember.class);
        given(memberRepository.findByCalendarAndUser(50L, USER_ID)).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> sut.joinByInviteCode(USER_ID, CODE, IP))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("새 캘린더(개인 기본 포함)에는 초대 코드가 없다 — 공유를 켤 때만 발급(보안 점검 DAM-4)")
    void newCalendarHasNoInviteCode() {
        assertThat(calendar(true).getInviteCode()).isNull();
        assertThat(calendar(false).getInviteCode()).isNull();
    }

    @Test
    @DisplayName("공유를 켜면 128비트(대문자 16진수 32자) 코드가 나오고, 다시 만들면 바뀐다")
    void issuedCodeIs128Bit() {
        UserCalendar cal = calendar(false);
        given(userCalendarRepository.findById(CAL_ID)).willReturn(Optional.of(cal));

        String first = sut.regenerateInviteCode(CAL_ID, USER_ID);
        String second = sut.regenerateInviteCode(CAL_ID, USER_ID);

        assertThat(first).matches("[0-9A-F]{32}");
        assertThat(second).matches("[0-9A-F]{32}").isNotEqualTo(first);
        verify(membershipValidator, times(2)).validateOwner(CAL_ID, USER_ID);
    }

    @Test
    @DisplayName("공유 끄기 — 소유자만, 코드를 지운다")
    void stopSharingClearsCode() {
        UserCalendar cal = calendar(false);
        cal.issueInviteCode();
        given(userCalendarRepository.findById(CAL_ID)).willReturn(Optional.of(cal));

        sut.stopSharing(CAL_ID, USER_ID);

        assertThat(cal.getInviteCode()).isNull();
        verify(membershipValidator).validateOwner(CAL_ID, USER_ID);
    }

    @Test
    @DisplayName("틀린 코드는 시도로 센다 — 형식이 틀리면 DB 를 보지 않는다")
    void wrongCodeIsCounted() {
        given(joinRateLimiter.isAllowed(USER_ID, IP)).willReturn(true);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));

        assertThatThrownBy(() -> sut.joinByInviteCode(USER_ID, "3F9A1C2B", IP)) // 옛 8자 형식
                .isInstanceOf(EntityNotFoundException.class)
                .extracting("errorCode").isEqualTo(DeskErrorCode.USER_CALENDAR_INVITE_INVALID);
        verify(userCalendarRepository, never()).findByInviteCode(any());
        verify(joinRateLimiter).recordFailure(USER_ID, IP);
    }

    @Test
    @DisplayName("한도를 넘으면 맞는 코드라도 받지 않는다 — 429")
    void rateLimitedEvenWithCorrectCode() {
        given(joinRateLimiter.isAllowed(USER_ID, IP)).willReturn(false);

        assertThatThrownBy(() -> sut.joinByInviteCode(USER_ID, CODE, IP))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("errorCode").isEqualTo(DeskErrorCode.USER_CALENDAR_JOIN_RATE_LIMITED);
        verify(userCalendarRepository, never()).findByInviteCode(any());
        assertThat(DeskErrorCode.USER_CALENDAR_JOIN_RATE_LIMITED.getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("붙여 넣은 값의 공백·하이픈·소문자는 정규화한다")
    void normalizesPastedCode() {
        assertThat(UserCalendarServiceImpl.normalizeInviteCode(" 01234567-89abcdef 0123456789ABCDEF\n")).isEqualTo(CODE);
        assertThat(UserCalendarServiceImpl.normalizeInviteCode("XYZ")).isNull();
        assertThat(UserCalendarServiceImpl.normalizeInviteCode(null)).isNull();
    }
}
