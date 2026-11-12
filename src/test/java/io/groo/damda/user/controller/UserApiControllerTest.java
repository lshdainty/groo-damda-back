package io.groo.damda.user.controller;

import io.groo.core.util.MessageResolver;
import io.groo.damda.common.config.web.WebConfig;
import io.groo.damda.security.filter.JwtAuthenticationFilter;
import io.groo.damda.security.resolver.LoginUserArgumentResolver;
import io.groo.damda.support.security.WithLoginUser;
import io.groo.damda.user.controller.dto.UserApiDto.PreferencesResponse;
import io.groo.damda.user.controller.dto.UserApiDto.UpdatePreferencesReq;
import io.groo.damda.user.service.dto.WithdrawalServiceDto.CheckResult;
import io.groo.damda.user.service.ReauthProxyService;
import io.groo.damda.user.type.ReauthPurpose;
import io.groo.damda.user.service.ReauthTicketVerifier;
import io.groo.damda.user.service.UserService;
import io.groo.damda.user.service.WithdrawalService;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UserApiController 슬라이스 테스트.
 *
 * <p>보안 필터는 끄고({@code addFilters=false}) {@link WithLoginUser} 로 SecurityContext 를 세팅 →
 * {@code @LoginUser} ArgumentResolver({@link WebConfig} 가 등록)가 UserPrincipal 을 주입한다.
 * 비밀번호 API 는 SSO userId 로, 환경설정 API 는 rowId 로 위임하므로 두 값 모두 검증한다.
 * 서비스는 mock — 매핑·바디 역직렬화·로그인 사용자 위임·@Valid 검증을 확인한다.
 */
@WebMvcTest(controllers = UserApiController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, LoginUserArgumentResolver.class})
@ActiveProfiles("test")
@WithLoginUser(rowId = 1L, userId = "user1")
class UserApiControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private UserService userService;
    // 해지는 별도 테스트(WithdrawalServiceImplTest·아래 해지 매핑 케이스)에서 본다 — 여기서는 컨트롤러가
    // 받는 협력자라 슬라이스를 띄우려면 자리가 있어야 한다.
    @MockitoBean private WithdrawalService withdrawalService;
    @MockitoBean private ReauthProxyService reauthProxyService;
    @MockitoBean private ReauthTicketVerifier reauthTicketVerifier;
    // groo-core GlobalExceptionHandler(@ControllerAdvice) 의존 — 슬라이스 로드용 mock.
    @MockitoBean private MessageResolver messageResolver;

    private PreferencesResponse samplePreferences() {
        return new PreferencesResponse(
                true, false, false, false, false, false, false, false,
                80, false, null, null, "DEFAULT", true, false, "WEEKLY", "Asia/Seoul", "KRW");
    }

    @Test
    @DisplayName("PATCH /users/me/password — 로그인 userId·바디로 changePassword 위임")
    void changePassword() throws Exception {
        String body = """
                {"currentPassword":"oldpw123","newPassword":"newpw1234","confirmPassword":"newpw1234"}
                """;

        mockMvc.perform(patch("/api/v1/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(userService).changePassword("user1", "oldpw123", "newpw1234", "newpw1234");
    }

    @Test
    @DisplayName("PATCH /users/me/password — 비밀번호 정책은 SSO 소유: 짧은 값도 판단하지 않고 위임")
    void changePassword_policyIsOwnedBySso() throws Exception {
        // desk-back 은 비밀번호를 소유하지 않는 프록시다. 여기서 길이·문자 규칙을 함께 들고 있으면
        // SSO 가 정책을 바꿨을 때 조용히 어긋난다(= 여기서만 거부되는 값이 생긴다).
        // 채워졌는지(@NotBlank)만 보고 판단은 SSO 에 맡긴다.
        String body = """
                {"currentPassword":"oldpw123","newPassword":"short","confirmPassword":"short"}
                """;

        mockMvc.perform(patch("/api/v1/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(userService).changePassword("user1", "oldpw123", "short", "short");
    }

    @Test
    @DisplayName("PATCH /users/me/password — newPassword 가 비면 400")
    void changePassword_blank_returns400() throws Exception {
        String body = """
                {"currentPassword":"oldpw123","newPassword":"","confirmPassword":""}
                """;

        mockMvc.perform(patch("/api/v1/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /users/me/verify-password — 로그인 userId·비밀번호로 verifyPassword 위임")
    void verifyPassword() throws Exception {
        String body = """
                {"password":"mypw12345"}
                """;

        mockMvc.perform(post("/api/v1/users/me/verify-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(userService).verifyPassword("user1", "mypw12345");
    }

    @Test
    @DisplayName("POST /users/me/verify-password — 비밀번호 공백이면 400")
    void verifyPassword_blank_returns400() throws Exception {
        String body = """
                {"password":""}
                """;

        mockMvc.perform(post("/api/v1/users/me/verify-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /users/me/preferences — 로그인 rowId 로 환경설정 조회")
    void getPreferences() throws Exception {
        given(userService.getPreferences(1L)).willReturn(samplePreferences());

        mockMvc.perform(get("/api/v1/users/me/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pushEnabled").value(true))
                .andExpect(jsonPath("$.data.budgetAlertThreshold").value(80))
                .andExpect(jsonPath("$.data.emailFrequency").value("WEEKLY"))
                .andExpect(jsonPath("$.data.defaultCurrency").value("KRW"));

        verify(userService).getPreferences(1L);
    }

    @Test
    @DisplayName("PATCH /users/me/preferences — 로그인 rowId·바디로 부분 수정 위임")
    void updatePreferences() throws Exception {
        given(userService.updatePreferences(eq(1L), any(UpdatePreferencesReq.class)))
                .willReturn(samplePreferences());

        String body = """
                {"pushEnabled":false,"budgetAlertThreshold":90,"emailFrequency":"DAILY"}
                """;

        mockMvc.perform(patch("/api/v1/users/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pushEnabled").value(true));

        ArgumentCaptor<UpdatePreferencesReq> captor = ArgumentCaptor.forClass(UpdatePreferencesReq.class);
        verify(userService).updatePreferences(eq(1L), captor.capture());
        assertThat(captor.getValue().getPushEnabled()).isFalse();
        assertThat(captor.getValue().getBudgetAlertThreshold()).isEqualTo(90);
        assertThat(captor.getValue().getEmailFrequency()).isEqualTo("DAILY");
    }

    /**
     * QA #124 — 기본 통화가 이 엔드포인트에 얹힌다. 새 API 를 만들지 않은 이유는 부르는
     * 시점이 알림·지역 설정과 같기 때문이다(설정 화면에서만 읽고 쓴다).
     */
    @Test
    @DisplayName("PATCH /users/me/preferences — 기본 통화도 같은 본문으로 전달된다")
    void updatePreferencesCarriesDefaultCurrency() throws Exception {
        given(userService.updatePreferences(eq(1L), any(UpdatePreferencesReq.class)))
                .willReturn(samplePreferences());

        mockMvc.perform(patch("/api/v1/users/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"defaultCurrency":"USD"}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdatePreferencesReq> captor = ArgumentCaptor.forClass(UpdatePreferencesReq.class);
        verify(userService).updatePreferences(eq(1L), captor.capture());
        assertThat(captor.getValue().getDefaultCurrency()).isEqualTo("USD");
    }

    /**
     * 목록 밖의 값은 서비스에 닿기 전에 끊는다 — 알림음·발송 주기와 같은 모양이다.
     *
     * <p>되돌려 보는 법(네거티브 컨트롤): {@code UpdatePreferencesReq.defaultCurrency} 의
     * {@code @Pattern} 을 지우면 아래가 200 이 되며 깨진다(서비스는 mock 이라 검사도 안 돈다).
     */
    @Test
    @DisplayName("PATCH /users/me/preferences — 고를 수 없는 통화면 400 이고 서비스까지 안 간다")
    void updatePreferences_unsupportedCurrency_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"defaultCurrency":"XBT"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(userService, org.mockito.Mockito.never())
                .updatePreferences(org.mockito.ArgumentMatchers.anyLong(), any(UpdatePreferencesReq.class));
    }

    @Test
    @DisplayName("PATCH /users/me/preferences — budgetAlertThreshold 범위 초과면 400")
    void updatePreferences_thresholdOutOfRange_returns400() throws Exception {
        String body = """
                {"budgetAlertThreshold":200}
                """;

        mockMvc.perform(patch("/api/v1/users/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /users/me/withdrawal-check — 로그인 rowId 로 해지 가능 여부를 묻는다")
    void withdrawalCheck() throws Exception {
        // 서버가 실제로 넣는 문자열이다(`WithdrawalServiceImpl.check`). 다른 값을 쓰면
        // 웹·앱이 맞대는 상수와 어긋나도 이 테스트는 초록불이 난다.
        given(withdrawalService.check(1L)).willReturn(new CheckResult(
                List.of("SUBSCRIPTION_ACTIVE"), LocalDateTime.of(2026, 10, 1, 0, 0), 2, 3, 1, 4));

        mockMvc.perform(get("/api/v1/users/me/withdrawal-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.blocked[0]").value("SUBSCRIPTION_ACTIVE"))
                .andExpect(jsonPath("$.data.sharedCalendarsOwned").value(2))
                .andExpect(jsonPath("$.data.dutchPayParticipations").value(4));

        verify(withdrawalService).check(1L);
    }

    @Test
    @DisplayName("DELETE /users/me — 재인증 티켓을 먼저 소모하고 rowId·사유로 해지 위임")
    void withdraw() throws Exception {
        String body = """
                {"reason":"안 쓰게 됐어요"}
                """;

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("X-Reauth-Token", "ticket-abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(reauthTicketVerifier).consumeFor("ticket-abc", "withdraw", "user1");
        verify(withdrawalService).withdraw(1L, "안 쓰게 됐어요");
    }

    @Test
    @DisplayName("DELETE /users/me — 본문이 없어도 사유 없이 해지한다")
    void withdraw_withoutBody() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me")
                        .header("X-Reauth-Token", "ticket-abc"))
                .andExpect(status().isOk());

        verify(withdrawalService).withdraw(1L, null);
    }

    @Test
    @DisplayName("DELETE /users/me — 티켓 검증이 막으면 해지 루틴까지 가지 않는다")
    void withdraw_reauthRejected_doesNotTouchService() throws Exception {
        org.mockito.BDDMockito.willThrow(
                        new io.groo.core.exception.UnauthorizedException(
                                io.groo.damda.common.exception.DeskErrorCode.REAUTH_REQUIRED))
                .given(reauthTicketVerifier).consumeFor(any(), eq("withdraw"), any());

        mockMvc.perform(delete("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());

        verify(withdrawalService, org.mockito.Mockito.never())
                .withdraw(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    @DisplayName("POST /users/me/reauth/email-code — 로그인 userId 로 코드 발송을 위임한다")
    void sendReauthEmailCode() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/reauth/email-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(reauthProxyService).sendEmailCode("user1");
    }

    @Test
    @DisplayName("POST /users/me/reauth/email-code/verify — 코드를 넘기고 티켓을 돌려준다")
    void verifyReauthEmailCode() throws Exception {
        given(reauthProxyService.verifyEmailCode("user1", "123456", ReauthPurpose.WITHDRAW)).willReturn("ticket-abc");

        mockMvc.perform(post("/api/v1/users/me/reauth/email-code/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reauthToken").value("ticket-abc"));
    }

    @Test
    @DisplayName("POST /users/me/reauth/email-code/verify — 6자리가 아니면 400 이고 SSO 까지 안 간다")
    void verifyReauthEmailCode_badCode_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/reauth/email-code/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"12345"}
                                """))
                .andExpect(status().isBadRequest());

        verify(reauthProxyService, org.mockito.Mockito.never()).verifyEmailCode(any(), any(), any());
    }

    @Test
    @DisplayName("POST /users/me/reauth/password — 비밀번호를 넘기고 티켓을 돌려준다")
    void verifyReauthPassword() throws Exception {
        given(reauthProxyService.verifyPassword("user1", "pw", ReauthPurpose.WITHDRAW)).willReturn("ticket-abc");

        mockMvc.perform(post("/api/v1/users/me/reauth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"pw"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reauthToken").value("ticket-abc"));
    }

    @Test
    @DisplayName("POST /users/me/reauth/password — purpose=oauth-link 면 연동용 티켓을 받는다(보안 점검 S-2)")
    void verifyReauthPassword_oauthLinkPurpose() throws Exception {
        given(reauthProxyService.verifyPassword("user1", "pw", ReauthPurpose.OAUTH_LINK)).willReturn("link-ticket");

        mockMvc.perform(post("/api/v1/users/me/reauth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"pw","purpose":"oauth-link"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reauthToken").value("link-ticket"));
    }

    @Test
    @DisplayName("POST /users/me/reauth/password — purpose=oauth-unlink 면 해제용 티켓을 받는다(보안 점검 S-2 잔여)")
    void verifyReauthPassword_oauthUnlinkPurpose() throws Exception {
        given(reauthProxyService.verifyPassword("user1", "pw", ReauthPurpose.OAUTH_UNLINK)).willReturn("unlink-ticket");

        mockMvc.perform(post("/api/v1/users/me/reauth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"pw","purpose":"oauth-unlink"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reauthToken").value("unlink-ticket"));
    }

    @Test
    @DisplayName("POST /users/me/reauth/email-code/verify — 목록에 없는 용도는 400 이고 SSO 까지 안 간다")
    void verifyReauthEmailCode_unknownPurpose_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/reauth/email-code/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"123456","purpose":"anything"}
                                """))
                .andExpect(status().isBadRequest());

        verify(reauthProxyService, org.mockito.Mockito.never()).verifyEmailCode(any(), any(), any());
    }

    @Test
    @DisplayName("POST /users/me/reauth/password — 비밀번호가 비면 400")
    void verifyReauthPassword_blank_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/reauth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"  "}
                                """))
                .andExpect(status().isBadRequest());

        verify(reauthProxyService, org.mockito.Mockito.never()).verifyPassword(any(), any(), any());
    }
}
