package io.groo.damda.calendar.controller;

import io.groo.core.util.MessageResolver;
import io.groo.damda.calendar.service.UserCalendarService;
import io.groo.damda.calendar.service.dto.UserCalendarServiceDto;
import io.groo.damda.calendar.type.CalendarRole;
import io.groo.damda.common.config.web.WebConfig;
import io.groo.damda.security.filter.JwtAuthenticationFilter;
import io.groo.damda.security.resolver.LoginUserArgumentResolver;
import io.groo.damda.support.security.WithLoginUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UserCalendar API 슬라이스 테스트 — 보안 필터 제외 + {@link WithLoginUser} 로 로그인 사용자 위임 검증.
 * CRUD·가시성 토글에 더해 공유(멤버 조회·초대코드·참여·퇴출·권한변경) 엔드포인트까지 커버한다.
 */
@WebMvcTest(controllers = UserCalendarApiController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, LoginUserArgumentResolver.class})
@ActiveProfiles("test")
@WithLoginUser(rowId = 1L)
class UserCalendarApiControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private UserCalendarService userCalendarService;
    // groo-core GlobalExceptionHandler(@ControllerAdvice) 의존 — 슬라이스 로드용 mock.
    @MockitoBean private MessageResolver messageResolver;

    private UserCalendarServiceDto.CalendarInfo sampleInfo() {
        return new UserCalendarServiceDto.CalendarInfo(
                40L, 1L, "오너", "내캘린더", "#00ff00", 0,
                true, true, "INVITE123", false, true, CalendarRole.OWNER, 1, null, null);
    }

    private UserCalendarServiceDto.MemberInfo sampleMember() {
        return new UserCalendarServiceDto.MemberInfo(
                50L, 2L, "멤버", "m@e.com", CalendarRole.EDIT, null);
    }

    @Test
    @DisplayName("POST /calendar/calendars — 로그인 사용자·바디로 createCalendar 위임")
    void createCalendar() throws Exception {
        given(userCalendarService.createCalendar(any())).willReturn(sampleInfo());

        String body = """
                {"calendarName":"내캘린더","color":"#00ff00"}
                """;

        mockMvc.perform(post("/api/v1/calendar/calendars")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowId").value(40))
                .andExpect(jsonPath("$.data.calendarName").value("내캘린더"));

        var captor = ArgumentCaptor.forClass(UserCalendarServiceDto.CreateCommand.class);
        verify(userCalendarService).createCalendar(captor.capture());
        assertThat(captor.getValue().userRowId()).isEqualTo(1L);
        assertThat(captor.getValue().calendarName()).isEqualTo("내캘린더");
        assertThat(captor.getValue().color()).isEqualTo("#00ff00");
    }

    @Test
    @DisplayName("GET /calendar/calendars — 로그인 사용자로 목록 조회")
    void getCalendars() throws Exception {
        given(userCalendarService.getCalendars(1L)).willReturn(List.of(sampleInfo()));

        mockMvc.perform(get("/api/v1/calendar/calendars"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.calendars.length()").value(1))
                .andExpect(jsonPath("$.data.calendars[0].rowId").value(40));

        verify(userCalendarService).getCalendars(1L);
    }

    @Test
    @DisplayName("PUT /calendar/calendars/{id} — id·로그인 사용자·바디로 updateCalendar 위임")
    void updateCalendar() throws Exception {
        given(userCalendarService.updateCalendar(eq(40L), eq(1L), any())).willReturn(sampleInfo());

        String body = """
                {"calendarName":"수정캘린더","color":"#0000ff"}
                """;

        mockMvc.perform(put("/api/v1/calendar/calendars/{id}", 40L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowId").value(40));

        var captor = ArgumentCaptor.forClass(UserCalendarServiceDto.UpdateCommand.class);
        verify(userCalendarService).updateCalendar(eq(40L), eq(1L), captor.capture());
        assertThat(captor.getValue().calendarName()).isEqualTo("수정캘린더");
        assertThat(captor.getValue().color()).isEqualTo("#0000ff");
    }

    @Test
    @DisplayName("PATCH /calendar/calendars/{id}/visibility — id·로그인 사용자로 가시성 토글")
    void toggleVisibility() throws Exception {
        given(userCalendarService.toggleVisibility(40L, 1L)).willReturn(sampleInfo());

        mockMvc.perform(patch("/api/v1/calendar/calendars/{id}/visibility", 40L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowId").value(40));

        verify(userCalendarService).toggleVisibility(40L, 1L);
    }

    @Test
    @DisplayName("DELETE /calendar/calendars/{id} — id·로그인 사용자로 삭제 위임")
    void deleteCalendar() throws Exception {
        mockMvc.perform(delete("/api/v1/calendar/calendars/{id}", 40L))
                .andExpect(status().isOk());

        verify(userCalendarService).deleteCalendar(eq(40L), eq(1L));
    }

    @Test
    @DisplayName("GET /calendar/calendars/{id}/members — id·로그인 사용자로 멤버 목록 조회")
    void getMembers() throws Exception {
        given(userCalendarService.getMembers(40L, 1L)).willReturn(List.of(sampleMember()));

        mockMvc.perform(get("/api/v1/calendar/calendars/{id}/members", 40L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.members.length()").value(1))
                .andExpect(jsonPath("$.data.members[0].rowId").value(50))
                .andExpect(jsonPath("$.data.members[0].userName").value("멤버"));

        verify(userCalendarService).getMembers(40L, 1L);
    }

    @Test
    @DisplayName("PATCH /calendar/calendars/{id}/regenerate-invite-code — 새 초대코드 응답")
    void regenerateInviteCode() throws Exception {
        given(userCalendarService.regenerateInviteCode(40L, 1L)).willReturn("NEWCODE99");

        mockMvc.perform(patch("/api/v1/calendar/calendars/{id}/regenerate-invite-code", 40L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inviteCode").value("NEWCODE99"));

        verify(userCalendarService).regenerateInviteCode(40L, 1L);
    }

    @Test
    @DisplayName("DELETE /calendar/calendars/{id}/invite-code — 공유 끄기(코드 삭제)")
    void stopSharing() throws Exception {
        mockMvc.perform(delete("/api/v1/calendar/calendars/{id}/invite-code", 40L))
                .andExpect(status().isOk());

        verify(userCalendarService).stopSharing(40L, 1L);
    }

    @Test
    @DisplayName("POST /calendar/calendars/join — 64자를 넘는 코드는 볼 것 없이 400")
    void joinRejectsOversizedCode() throws Exception {
        mockMvc.perform(post("/api/v1/calendar/calendars/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + "A".repeat(65) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /calendar/calendars/join — 로그인 사용자·초대코드로 참여")
    void joinByInviteCode() throws Exception {
        given(userCalendarService.joinByInviteCode(eq(1L), eq("INVITE123"), any())).willReturn(sampleInfo());

        String body = """
                {"inviteCode":"INVITE123"}
                """;

        mockMvc.perform(post("/api/v1/calendar/calendars/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowId").value(40));

        verify(userCalendarService).joinByInviteCode(eq(1L), eq("INVITE123"), any());
    }

    @Test
    @DisplayName("DELETE /calendar/calendars/{id}/member/{memberId} — 캘린더·멤버·요청자로 퇴출 위임")
    void removeMember() throws Exception {
        mockMvc.perform(delete("/api/v1/calendar/calendars/{id}/member/{memberId}", 40L, 50L))
                .andExpect(status().isOk());

        verify(userCalendarService).removeMember(eq(40L), eq(50L), eq(1L));
    }

    @Test
    @DisplayName("PATCH /calendar/calendars/{id}/member/{memberId}/role — 권한·요청자로 역할 변경 위임")
    void changeMemberRole() throws Exception {
        String body = """
                {"permission":"EDIT"}
                """;

        mockMvc.perform(patch("/api/v1/calendar/calendars/{id}/member/{memberId}/role", 40L, 50L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(userCalendarService).changeMemberRole(eq(40L), eq(50L), eq(CalendarRole.EDIT), eq(1L));
    }

    /**
     * QA #81 — 이 컨트롤러에도 {@code @Valid} 가 없었다. {@code user_calendar.calendar_name} 은
     * NOT NULL 이라 이름을 빼고 보내면 저장이 DB 앞에서 터져 409 "다른 곳에서 먼저 수정됐어요"
     * 가 나갔다.
     *
     * <p>되돌려 보는 법(네거티브 컨트롤): 컨트롤러의 {@code @Valid} 나 DTO 의
     * {@code @NotBlank(calendarName)} 을 지우면 아래가 200 으로 통과한다.
     */
    @Test
    @DisplayName("POST /calendar/calendars — 이름이 빠지면 400")
    void createCalendar_missingName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/calendar/calendars")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"color\":\"#00ff00\"}"))
                .andExpect(status().isBadRequest());

        verify(userCalendarService, never()).createCalendar(any());
    }

    /**
     * 수정은 <b>보낸 칸만 바꾼다</b> — 색만 보내는 부분 수정은 지금도 성립하는 계약이다.
     * 여기에 {@code @NotBlank} 를 걸면 멀쩡히 쓰던 요청이 새로 400 을 맞는다. 그 선을 못 박는다.
     */
    @Test
    @DisplayName("PUT /calendar/calendars/{id} — 색만 보내는 부분 수정은 계속 통과한다")
    void updateCalendar_colorOnly_stillPasses() throws Exception {
        given(userCalendarService.updateCalendar(eq(40L), eq(1L), any())).willReturn(sampleInfo());

        mockMvc.perform(put("/api/v1/calendar/calendars/{id}", 40L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"color\":\"#00ff00\"}"))
                .andExpect(status().isOk());

        verify(userCalendarService).updateCalendar(eq(40L), eq(1L), any());
    }

    @Test
    @DisplayName("PATCH .../role — 권한이 빠지면 400(저장이 아니라 요청이 잘못이다)")
    void changeMemberRole_missingPermission_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/calendar/calendars/{id}/member/{memberId}/role", 40L, 50L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(userCalendarService, never())
                .changeMemberRole(any(Long.class), any(Long.class), any(), any(Long.class));
    }
}
