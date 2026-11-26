package io.groo.damda.notification.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.notification.controller.dto.NotificationApiDto;
import io.groo.damda.notification.service.NotificationService;
import io.groo.damda.notification.service.SseEmitterService;
import io.groo.damda.notification.service.dto.NotificationServiceDto;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class NotificationApiController {
    private final NotificationService notificationService;
    private final SseEmitterService sseEmitterService;

    private static final String ACCEL_BUFFERING_HEADER = "X-Accel-Buffering";

    @GetMapping(value = "/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@LoginUser UserPrincipal loginUser, HttpServletResponse response) {
        // 엣지 nginx 는 기본으로 뒤쪽 응답을 버퍼에 모은다. 스트림의 이벤트는 작아서 버퍼가 차지 않아
        // 브라우저에 닿지 않는다. 이 머리가 있으면 nginx 가 이 응답만 버퍼링을 끈다(머리는 바깥으로 나가지 않는다).
        response.setHeader(ACCEL_BUFFERING_HEADER, "no");
        return sseEmitterService.subscribe(loginUser.getRowId());
    }

    @GetMapping("/notifications")
    public ApiResponse<NotificationApiDto.ListResponse> getNotifications(
            @LoginUser UserPrincipal loginUser) {
        List<NotificationServiceDto.NotificationInfo> infos =
            notificationService.getNotifications(loginUser.getRowId());
        return ApiResponse.success(NotificationApiDto.ListResponse.from(infos));
    }

    @GetMapping("/notifications/unread-count")
    public ApiResponse<NotificationApiDto.UnreadCountResponse> getUnreadCount(
            @LoginUser UserPrincipal loginUser) {
        Long count = notificationService.getUnreadCount(loginUser.getRowId());
        return ApiResponse.success(new NotificationApiDto.UnreadCountResponse(count));
    }

    @PatchMapping("/notification/{id}/read")
    public ApiResponse<Void> markRead(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        notificationService.markRead(id, loginUser.getRowId());
        return ApiResponse.success();
    }

    @PatchMapping("/notifications/read-all")
    public ApiResponse<Void> markAllRead(
            @LoginUser UserPrincipal loginUser) {
        notificationService.markAllRead(loginUser.getRowId());
        return ApiResponse.success();
    }

    @DeleteMapping("/notification/{id}")
    public ApiResponse<Void> deleteNotification(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        notificationService.deleteNotification(id, loginUser.getRowId());
        return ApiResponse.success();
    }
}
