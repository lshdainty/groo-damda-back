package io.groo.damda.notification.controller;

import io.groo.core.util.MessageResolver;
import io.groo.damda.common.config.web.WebConfig;
import io.groo.damda.notification.service.NotificationService;
import io.groo.damda.notification.service.SseEmitterService;
import io.groo.damda.notification.service.dto.NotificationServiceDto;
import io.groo.damda.notification.type.NotificationType;
import io.groo.damda.notification.type.ReferenceType;
import io.groo.damda.security.filter.JwtAuthenticationFilter;
import io.groo.damda.security.resolver.LoginUserArgumentResolver;
import io.groo.damda.support.security.WithLoginUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Notification API 슬라이스 테스트 — SSE 구독 + 알림 조회/읽음/삭제.
 *
 * <p>보안 필터는 끄고({@code addFilters=false}) {@link WithLoginUser} 로 SecurityContext 를 세팅 →
 * {@code @LoginUser} ArgumentResolver({@link WebConfig} 가 등록)가 UserPrincipal 을 주입한다.
 */
@WebMvcTest(controllers = NotificationApiController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, LoginUserArgumentResolver.class})
@ActiveProfiles("test")
@WithLoginUser(rowId = 1L)
class NotificationApiControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private SseEmitterService sseEmitterService;
    // groo-core GlobalExceptionHandler(@ControllerAdvice) 의존 — 슬라이스 로드용 mock.
    @MockitoBean private MessageResolver messageResolver;

    private NotificationServiceDto.NotificationInfo sampleInfo() {
        return new NotificationServiceDto.NotificationInfo(
                100L, 1L, NotificationType.EVENT_REMINDER, "제목", "메시지",
                ReferenceType.CALENDAR_EVENT, 55L, false, null,
                LocalDateTime.of(2026, 7, 1, 9, 0));
    }

    @Test
    @DisplayName("GET /notifications/stream — 로그인 사용자로 SSE 구독(async 시작)")
    void subscribe() throws Exception {
        given(sseEmitterService.subscribe(1L)).willReturn(new SseEmitter());

        mockMvc.perform(get("/api/v1/notifications/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());

        verify(sseEmitterService).subscribe(1L);
    }

    @Test
    @DisplayName("GET /notifications/stream — 엣지 nginx 버퍼링을 끄는 머리(X-Accel-Buffering: no)를 붙인다")
    void subscribe_disablesProxyBuffering() throws Exception {
        given(sseEmitterService.subscribe(1L)).willReturn(new SseEmitter());

        mockMvc.perform(get("/api/v1/notifications/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"));
    }

    @Test
    @DisplayName("GET /notifications — 로그인 사용자로 목록 조회")
    void getNotifications() throws Exception {
        given(notificationService.getNotifications(1L)).willReturn(List.of(sampleInfo()));

        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications.length()").value(1))
                .andExpect(jsonPath("$.data.notifications[0].rowId").value(100));

        verify(notificationService).getNotifications(1L);
    }

    @Test
    @DisplayName("GET /notifications/unread-count — 로그인 사용자로 미읽음 개수 조회")
    void getUnreadCount() throws Exception {
        given(notificationService.getUnreadCount(1L)).willReturn(5L);

        mockMvc.perform(get("/api/v1/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(5));

        verify(notificationService).getUnreadCount(1L);
    }

    @Test
    @DisplayName("PATCH /notification/{id}/read — id·로그인 사용자로 읽음 처리")
    void markRead() throws Exception {
        mockMvc.perform(patch("/api/v1/notification/{id}/read", 9L))
                .andExpect(status().isOk());

        verify(notificationService).markRead(9L, 1L);
    }

    @Test
    @DisplayName("PATCH /notifications/read-all — 로그인 사용자로 전체 읽음 처리")
    void markAllRead() throws Exception {
        mockMvc.perform(patch("/api/v1/notifications/read-all"))
                .andExpect(status().isOk());

        verify(notificationService).markAllRead(1L);
    }

    @Test
    @DisplayName("DELETE /notification/{id} — id·로그인 사용자로 삭제 위임")
    void deleteNotification() throws Exception {
        mockMvc.perform(delete("/api/v1/notification/{id}", 9L))
                .andExpect(status().isOk());

        verify(notificationService).deleteNotification(9L, 1L);
    }
}
