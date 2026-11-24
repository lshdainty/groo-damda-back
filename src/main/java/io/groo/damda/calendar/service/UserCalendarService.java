package io.groo.damda.calendar.service;

import io.groo.damda.calendar.service.dto.UserCalendarServiceDto;
import io.groo.damda.calendar.type.CalendarRole;

import java.util.List;

public interface UserCalendarService {
    UserCalendarServiceDto.CalendarInfo createCalendar(UserCalendarServiceDto.CreateCommand command);
    List<UserCalendarServiceDto.CalendarInfo> getCalendars(Long userRowId);
    UserCalendarServiceDto.CalendarInfo updateCalendar(Long calendarId, Long userRowId, UserCalendarServiceDto.UpdateCommand command);
    UserCalendarServiceDto.CalendarInfo toggleVisibility(Long calendarId, Long userRowId);
    void deleteCalendar(Long calendarId, Long userRowId);
    UserCalendarServiceDto.CalendarInfo getOrCreateDefault(Long userRowId);

    // ── 공유 ──
    List<UserCalendarServiceDto.MemberInfo> getMembers(Long calendarId, Long userRowId);
    String regenerateInviteCode(Long calendarId, Long userRowId);
    void stopSharing(Long calendarId, Long userRowId);
    UserCalendarServiceDto.CalendarInfo joinByInviteCode(Long userRowId, String inviteCode, String clientIp);
    void removeMember(Long calendarId, Long memberId, Long requestUserRowId);

    /**
     * <b>내가 스스로</b> 이 캘린더에서 나간다.
     *
     * <p>{@link #removeMember} 와 가르는 이유는 <b>누가 부르느냐가 다르기</b> 때문이다 —
     * 그쪽은 소유자가 남을 내보내는 길이라 {@code validateOwner} 를 지난다. 멤버가 자기
     * 발로 나가는 데까지 소유자 권한을 요구하면 나갈 수가 없다(desk 이용 해지가 남의
     * 캘린더 멤버인 사람에게 403 으로 통째로 롤백됐다, 2026-09-17 QA).
     *
     * <p>소유자는 이 길로 못 나간다 — 나가면 그 캘린더에 주인이 없어진다. 소유자는
     * {@link #transferOwnership} 로 넘기고 나가거나, 혼자 남았으면 {@link #deleteCalendar} 로 지운다.
     * 멤버가 아니면 조용히 아무 일도 안 한다(멱등).
     */
    void leaveCalendar(Long calendarId, Long userRowId);

    /**
     * 소유자를 넘기고 나는 나간다 — 넘기면 바로 넘어간다(받는 사람의 수락을 기다리지 않는다).
     *
     * <p>받을 수 있는 사람은 편집 가능한 멤버다. 편집 가능한 멤버가 하나도 없을 때만 읽기 전용 멤버에게
     * 넘길 수 있다(2026-11-24 사용자 결정). 내가 쓴 일정은 캘린더에 남는다 — 공유 캘린더의 일정은
     * 함께 쓰던 것이라 나간 사람 것도 남은 멤버에게 보여야 한다.
     */
    void transferOwnership(Long calendarId, Long newOwnerMemberId, Long requestUserRowId);
    void changeMemberRole(Long calendarId, Long memberId, CalendarRole permission, Long requestUserRowId);
}
