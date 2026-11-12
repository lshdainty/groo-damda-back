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
        public static CalendarInfo from(UserCalendar calendar, CalendarRole myRole, int memberCount) {
            return new CalendarInfo(
                calendar.getRowId(),
                calendar.getUser().getRowId(),
                calendar.getUser().displayName(),
                calendar.getCalendarName(),
                calendar.getColor(),
                calendar.getSortOrder(),
                calendar.getIsDefault() == YNType.Y,
                calendar.getIsVisible() == YNType.Y,
                // 초대 코드는 소유자(공유 관리 권한 — CalendarRole.canManageMembers)에게만 내려 준다.
                // 종전엔 읽기 전용 멤버의 목록 응답에도 실려, 코드를 받은 아무 멤버가 남을 들일 수 있었다(보안 점검 DAM-4 잔여).
                myRole != null && myRole.canManageMembers() ? calendar.getInviteCode() : null,
                memberCount > 1,
                myRole == CalendarRole.OWNER,
                myRole,
                memberCount,
                calendar.getCreateAt(),
                calendar.getModifyAt()
            );
        }

        /** 생성/수정 등 단일 캘린더 반환 컨텍스트 — 소유자 기준. */
        public static CalendarInfo from(UserCalendar calendar) {
            return from(calendar, CalendarRole.OWNER, 1);
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
