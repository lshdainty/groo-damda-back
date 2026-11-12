package io.groo.damda.calendar.controller.dto;

import io.groo.damda.calendar.service.dto.UserCalendarServiceDto;
import io.groo.damda.calendar.type.CalendarRole;
import io.groo.damda.common.validation.ColorFormat;
import io.groo.damda.common.validation.FieldLimits;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public class UserCalendarApiDto {

    /**
     * {@code user_calendar.calendar_name} 은 NOT NULL 이다 — 생성에서 빠지면 종전엔 409
     * "다른 곳에서 먼저 수정됐어요" 로 튕겼다(QA #81). 색은 엔티티가 기본값을 씌우므로
     * 필수가 아니다.
     */
    @Schema(name = "UserCalendarCreateRequest")
    public record CreateRequest(
        @NotBlank(message = "캘린더 이름을 입력해주세요")
        @Size(max = FieldLimits.NAME_MAX, message = "캘린더 이름은 50자까지 입력할 수 있어요")
        String calendarName,
        @Pattern(regexp = ColorFormat.HEX_RGB, message = ColorFormat.MESSAGE)
        String color
    ) {}

    /**
     * 수정은 <b>보낸 칸만 바꾼다</b>({@code UserCalendar.updateCalendar} 가 null 을 무시한다).
     * 그래서 이름을 필수로 걸지 않는다 — 색만 보내는 부분 수정이 이미 성립하는 계약이고,
     * 여기에 {@code @NotBlank} 를 붙이면 그 요청이 새로 400 을 맞는다.
     */
    @Schema(name = "UserCalendarUpdateRequest")
    public record UpdateRequest(
        @Size(max = FieldLimits.NAME_MAX, message = "캘린더 이름은 50자까지 입력할 수 있어요")
        String calendarName,
        @Pattern(regexp = ColorFormat.HEX_RGB, message = ColorFormat.MESSAGE)
        String color
    ) {}

    public record JoinRequest(
        @NotBlank(message = "초대 코드를 입력해주세요")
        // 코드는 32자다. 붙여 넣으며 섞인 공백·하이픈까지 받고, 그보다 긴 입력은 볼 것도 없다.
        @Size(max = 64, message = "초대 코드가 올바르지 않아요")
        String inviteCode
    ) {}

    /** {@code user_calendar_member.permission} 은 NOT NULL — 빠지면 저장이 아니라 요청이 잘못이다. */
    public record ChangeRoleRequest(
        @NotNull(message = "권한을 골라주세요")
        CalendarRole permission
    ) {}

    @Schema(name = "UserCalendarResponse")
    public record Response(
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
        int memberCount
    ) {
        public static Response from(UserCalendarServiceDto.CalendarInfo info) {
            return new Response(
                info.rowId(),
                info.ownerRowId(),
                info.ownerName(),
                info.calendarName(),
                info.color(),
                info.sortOrder(),
                info.isDefault(),
                info.isVisible(),
                info.inviteCode(),
                info.isShared(),
                info.isOwner(),
                info.myRole(),
                info.memberCount()
            );
        }
    }

    @Schema(name = "UserCalendarListResponse")
    public record ListResponse(
        List<Response> calendars
    ) {
        public static ListResponse from(List<UserCalendarServiceDto.CalendarInfo> infos) {
            return new ListResponse(infos.stream().map(Response::from).toList());
        }
    }

    public record MemberResponse(
        Long rowId,
        Long userRowId,
        String userName,
        String userEmail,
        CalendarRole permission,
        LocalDateTime joinedAt
    ) {
        public static MemberResponse from(UserCalendarServiceDto.MemberInfo info) {
            return new MemberResponse(
                info.rowId(),
                info.userRowId(),
                info.userName(),
                info.userEmail(),
                info.permission(),
                info.joinedAt()
            );
        }
    }

    public record MemberListResponse(
        List<MemberResponse> members
    ) {
        public static MemberListResponse from(List<UserCalendarServiceDto.MemberInfo> infos) {
            return new MemberListResponse(infos.stream().map(MemberResponse::from).toList());
        }
    }

    public record InviteCodeResponse(
        String inviteCode
    ) {}
}
