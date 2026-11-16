package io.groo.damda.calendar.service.dto;

import io.groo.core.type.YNType;
import io.groo.damda.calendar.domain.UserCalendar;
import io.groo.damda.calendar.domain.UserCalendarMember;
import io.groo.damda.calendar.type.CalendarRole;

import java.time.LocalDateTime;

public class UserCalendarServiceDto {

    public record CreateCommand(
        Long userRowId,
        String calendarName,
        String color
    ) {}

    public record UpdateCommand(
        String calendarName,
        String color
    ) {}

    public record CalendarInfo(
        Long rowId,
        Long ownerRowId,
        String ownerName,
        String calendarName,
        String color,
        Integer sortOrder,
        boolean isDefault,
        boolean isVisible,
        String inviteCode,
        boolean isShared,
        boolean isOwner,
        CalendarRole myRole,
        int memberCount,
        LocalDateTime createAt,
        LocalDateTime modifyAt
    ) {
        /**
         * @param me 요청한 사용자의 멤버 행 — 권한과 <b>내 화면 표시 여부</b>를 여기서 읽는다(표시는 멤버마다 따로다).
         */
        public static CalendarInfo from(UserCalendar calendar, UserCalendarMember me, int memberCount) {
            CalendarRole myRole = me != null ? me.getPermission() : CalendarRole.READ;
            return new CalendarInfo(
                calendar.getRowId(),
                calendar.getUser().getRowId(),
                calendar.getUser().displayName(),
                calendar.getCalendarName(),
                calendar.getColor(),
                calendar.getSortOrder(),
                calendar.getIsDefault() == YNType.Y,
                me == null || me.getIsVisible() != YNType.N,
                // 초대 코드는 소유자(공유 관리 권한 — CalendarRole.canManageMembers)에게만 내려 준다.
                // 종전엔 읽기 전용 멤버의 목록 응답에도 실려, 코드를 받은 아무 멤버가 남을 들일 수 있었다(보안 점검 DAM-4 잔여).
                myRole.canManageMembers() ? calendar.getInviteCode() : null,
                memberCount > 1,
                myRole == CalendarRole.OWNER,
                myRole,
                memberCount,
                calendar.getCreateAt(),
                calendar.getModifyAt()
            );
        }

    }

    public record MemberInfo(
        Long rowId,
        Long userRowId,
        String userName,
        String userEmail,
        CalendarRole permission,
        LocalDateTime joinedAt
    ) {
        public static MemberInfo from(UserCalendarMember member) {
            return new MemberInfo(
                member.getRowId(),
                member.getUser().getRowId(),
                member.getUser().displayName(),
                member.getUser().getUserEmail(),
                member.getPermission(),
                member.getJoinedAt()
            );
        }
    }
}
