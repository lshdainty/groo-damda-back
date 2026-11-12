package io.groo.damda.calendar.controller;

import io.groo.core.security.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.calendar.controller.dto.UserCalendarApiDto;
import io.groo.damda.calendar.service.UserCalendarService;
import io.groo.damda.calendar.service.dto.UserCalendarServiceDto;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UserCalendarApiController {
    private final UserCalendarService userCalendarService;

    @PostMapping("/calendar/calendars")
    public ApiResponse<UserCalendarApiDto.Response> createCalendar(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody UserCalendarApiDto.CreateRequest request) {
        UserCalendarServiceDto.CalendarInfo info = userCalendarService.createCalendar(new UserCalendarServiceDto.CreateCommand(
            loginUser.getRowId(),
            request.calendarName(),
            request.color()
        ));
        return ApiResponse.success(UserCalendarApiDto.Response.from(info));
    }

    @GetMapping("/calendar/calendars")
    public ApiResponse<UserCalendarApiDto.ListResponse> getCalendars(
            @LoginUser UserPrincipal loginUser) {
        List<UserCalendarServiceDto.CalendarInfo> infos = userCalendarService.getCalendars(loginUser.getRowId());
        return ApiResponse.success(UserCalendarApiDto.ListResponse.from(infos));
    }

    @PutMapping("/calendar/calendars/{id}")
    public ApiResponse<UserCalendarApiDto.Response> updateCalendar(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @Valid @RequestBody UserCalendarApiDto.UpdateRequest request) {
        UserCalendarServiceDto.CalendarInfo info = userCalendarService.updateCalendar(id, loginUser.getRowId(), new UserCalendarServiceDto.UpdateCommand(
            request.calendarName(),
            request.color()
        ));
        return ApiResponse.success(UserCalendarApiDto.Response.from(info));
    }

    @PatchMapping("/calendar/calendars/{id}/visibility")
    public ApiResponse<UserCalendarApiDto.Response> toggleVisibility(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        UserCalendarServiceDto.CalendarInfo info = userCalendarService.toggleVisibility(id, loginUser.getRowId());
        return ApiResponse.success(UserCalendarApiDto.Response.from(info));
    }

    @DeleteMapping("/calendar/calendars/{id}")
    public ApiResponse<Void> deleteCalendar(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        userCalendarService.deleteCalendar(id, loginUser.getRowId());
        return ApiResponse.success();
    }

    // ── 공유 ──

    @GetMapping("/calendar/calendars/{id}/members")
    public ApiResponse<UserCalendarApiDto.MemberListResponse> getMembers(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        List<UserCalendarServiceDto.MemberInfo> infos = userCalendarService.getMembers(id, loginUser.getRowId());
        return ApiResponse.success(UserCalendarApiDto.MemberListResponse.from(infos));
    }

    @PatchMapping("/calendar/calendars/{id}/regenerate-invite-code")
    public ApiResponse<UserCalendarApiDto.InviteCodeResponse> regenerateInviteCode(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        String newCode = userCalendarService.regenerateInviteCode(id, loginUser.getRowId());
        return ApiResponse.success(new UserCalendarApiDto.InviteCodeResponse(newCode));
    }

    /** 공유 끄기 — 초대 코드를 지운다. 이미 참여한 멤버는 그대로다. 다시 켜려면 코드를 새로 만든다. */
    @DeleteMapping("/calendar/calendars/{id}/invite-code")
    public ApiResponse<Void> stopSharing(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        userCalendarService.stopSharing(id, loginUser.getRowId());
        return ApiResponse.success();
    }

    @PostMapping("/calendar/calendars/join")
    public ApiResponse<UserCalendarApiDto.Response> joinByInviteCode(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody UserCalendarApiDto.JoinRequest request,
            HttpServletRequest httpRequest) {
        UserCalendarServiceDto.CalendarInfo info = userCalendarService.joinByInviteCode(
            loginUser.getRowId(), request.inviteCode(), ClientIpResolver.resolve(httpRequest));
        return ApiResponse.success(UserCalendarApiDto.Response.from(info));
    }

    @DeleteMapping("/calendar/calendars/{id}/member/{memberId}")
    public ApiResponse<Void> removeMember(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @PathVariable Long memberId) {
        userCalendarService.removeMember(id, memberId, loginUser.getRowId());
        return ApiResponse.success();
    }

    @PatchMapping("/calendar/calendars/{id}/member/{memberId}/role")
    public ApiResponse<Void> changeMemberRole(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @PathVariable Long memberId,
            @Valid @RequestBody UserCalendarApiDto.ChangeRoleRequest request) {
        userCalendarService.changeMemberRole(id, memberId, request.permission(), loginUser.getRowId());
        return ApiResponse.success();
    }
}
