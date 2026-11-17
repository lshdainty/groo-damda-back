package io.groo.damda.security.controller;

import io.groo.core.util.MessageResolver;
import io.groo.damda.common.config.properties.JwtProperties;
import io.groo.damda.common.config.web.WebConfig;
import io.groo.damda.security.filter.JwtAuthenticationFilter;
import io.groo.damda.security.jwt.JwtTokenProvider;
import io.groo.damda.security.resolver.LoginUserArgumentResolver;
import io.groo.damda.security.service.TokenExchangeService;
import io.groo.damda.support.security.WithLoginUser;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.groo.damda.security.principal.JwtClaimsPrincipal;
import io.groo.damda.security.principal.JwtUserPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;
import java.util.Collections;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;

/**
 * TokenExchangeController — embed-token 엔드포인트 슬라이스 테스트.
 * 인증된 사용자(@WithLoginUser) → JwtTokenProvider.createEmbedToken 호출 + 응답 본문 검증.
 */
@WebMvcTest(controllers = TokenExchangeController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, LoginUserArgumentResolver.class})
@ActiveProfiles("test")
@WithLoginUser(rowId = 7L, userId = "user7", userName = "User7", userEmail = "u7@e.com")
class TokenExchangeControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private TokenExchangeService tokenExchangeService;
    @MockitoBean private JwtProperties jwtProperties;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private MessageResolver messageResolver;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private io.groo.damda.security.session.service.SsoSessionService ssoSessionService;
    @MockitoBean private io.groo.damda.security.session.store.SsoLoginLogoutStore ssoLoginLogoutStore;

    @Test
    @DisplayName("POST /api/v1/auth/exchange-code — 토큰은 HttpOnly 쿠키로만, 본문에는 사용자 정보만(N-4)")
    void exchangeCode_tokenOnlyInHttpOnlyCookie() throws Exception {
        given(jwtProperties.getSessionExpiration()).willReturn(604_800_000L);
        given(tokenExchangeService.exchangeCode(eq("CODE"), eq("VERIFIER"), eq("porestdesk://oauth/callback"), any()))
                .willReturn(new io.groo.damda.security.controller.dto.TokenExchangeDto.Response(
                        "DESK_JWT", "user7", "User7", "u7@e.com"));

        String body = mockMvc.perform(post("/api/v1/auth/exchange-code")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CODE\",\"codeVerifier\":\"VERIFIER\","
                                + "\"redirectUri\":\"porestdesk://oauth/callback\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(org.springframework.http.HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("desk_access_token=DESK_JWT"),
                                org.hamcrest.Matchers.containsString("HttpOnly"))))
                .andExpect(jsonPath("$.data.userId").value("user7"))
                .andExpect(jsonPath("$.data.userName").value("User7"))
                .andExpect(jsonPath("$.data.userEmail").value("u7@e.com"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // 본문 어디에도 토큰 문자열이 없어야 스크립트가 응답을 읽어도 토큰을 못 얻는다
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("DESK_JWT");
    }

    @Test
    @DisplayName("POST /api/v1/auth/exchange-code — 담다에서 로그아웃한 SSO 로그인이면 401 AUTH_030, 쿠키를 주지 않는다(CLI-6)")
    void exchangeCode_loggedOutSsoLogin_401WithCode() throws Exception {
        given(tokenExchangeService.exchangeCode(any(), any(), any(), any()))
                .willThrow(new io.groo.core.exception.UnauthorizedException(
                        io.groo.damda.common.exception.DeskErrorCode.SSO_LOGIN_LOGGED_OUT));

        mockMvc.perform(post("/api/v1/auth/exchange-code")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CODE\",\"codeVerifier\":\"VERIFIER\","
                                + "\"redirectUri\":\"porestdesk://oauth/callback\"}"))
                .andExpect(status().isUnauthorized())
                // 웹과 앱이 이 값으로 "비밀번호를 다시 묻는 로그인으로 보내라" 를 가린다
                .andExpect(jsonPath("$.code").value("AUTH_030"))
                .andExpect(header().doesNotExist(org.springframework.http.HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout — 이 기기의 담다 세션(저장해 둔 SSO refresh)을 끊는다")
    void logout_revokesSsoSession() throws Exception {
        given(jwtProperties.getSessionExpiration()).willReturn(604_800_000L);

        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk());

        // 세션이 남으면 로그아웃한 사용자의 refresh token 이 최대 7일 더 살아 재발급 수단이 된다
        verify(ssoSessionService).revoke("test-session");
    }

    /** SSO 로그인 번호가 실린 토큰으로 들어온 사용자 — @WithLoginUser 의 기본 주체에는 sso_sid 가 없다. */
    private void loginWithSsoSid(String ssoSid) {
        JwtUserPrincipal principal = new JwtUserPrincipal(new JwtClaimsPrincipal(
                "user7", "User7", "u7@e.com", 7L, "access", "test-session", ssoSid));
        TestSecurityContextHolder.setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList()));
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout — 이 토큰의 SSO 로그인을 '담다에서 로그아웃함' 으로 기억한다(CLI-6)")
    void logout_remembersSsoLogin() throws Exception {
        loginWithSsoSid("sso-login-1");

        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(header().string(org.springframework.http.HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Max-Age=0")));

        // 세션을 끊은 뒤에 기억한다 — 순서가 뒤집히면 끊다 실패했을 때 기록만 남는다
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(ssoSessionService, ssoLoginLogoutStore);
        order.verify(ssoSessionService).revoke("test-session");
        order.verify(ssoLoginLogoutStore).markLoggedOut("sso-login-1");
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout — sso_sid 가 없는 옛 토큰은 기억할 것이 없다. 로그아웃은 그대로 된다(CLI-6)")
    void logout_withoutSsoSid_recordsNothing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk());

        verify(ssoSessionService).revoke("test-session");
        org.mockito.Mockito.verifyNoInteractions(ssoLoginLogoutStore);
    }

    @Test
    @DisplayName("POST /api/v1/auth/embed-token — 로그인 사용자 정보 + 부모 세션(jti)으로 createEmbedToken 호출 + 60초 만료 응답")
    void issueEmbedToken_callsProviderWithLoginUser() throws Exception {
        given(jwtTokenProvider.createEmbedToken(eq("user7"), eq("User7"), eq("u7@e.com"), eq(7L), eq("test-session")))
                .willReturn("EMBED_JWT_TOKEN");

        mockMvc.perform(post("/api/v1/auth/embed-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("EMBED_JWT_TOKEN"))
                .andExpect(jsonPath("$.data.expiresIn").value(60));

        // 부모 세션을 실어야 로그아웃하면 임베드 토큰도 같이 막힌다(S-3)
        verify(jwtTokenProvider).createEmbedToken("user7", "User7", "u7@e.com", 7L, "test-session");
    }

    @Test
    @DisplayName("POST /api/v1/auth/embed-token — 임베드 토큰으로는 403, 새 토큰을 내주지 않는다(60초마다 스스로 연장되던 구멍)")
    void issueEmbedToken_rejectsEmbedPrincipal() throws Exception {
        JwtUserPrincipal embed = new JwtUserPrincipal(new JwtClaimsPrincipal(
                "user7", "User7", "u7@e.com", 7L, "embed", "test-session", null));
        TestSecurityContextHolder.setAuthentication(
                new UsernamePasswordAuthenticationToken(embed, null, Collections.emptyList()));

        mockMvc.perform(post("/api/v1/auth/embed-token"))
                .andExpect(status().isForbidden());

        verify(jwtTokenProvider, never()).createEmbedToken(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("GET /api/v1/auth/check — 로그인 사용자 정보 + 가입일시(joinedAt=User.createAt) 응답")
    void checkLogin_returnsUserInfoWithJoinedAt() throws Exception {
        User user = mock(User.class);
        given(user.getCreateAt()).willReturn(LocalDateTime.of(2024, 11, 5, 9, 30));
        given(userRepository.findById(7L)).willReturn(Optional.of(user));

        mockMvc.perform(get("/api/v1/auth/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowId").value(7))
                .andExpect(jsonPath("$.data.userId").value("user7"))
                .andExpect(jsonPath("$.data.userName").value("User7"))
                .andExpect(jsonPath("$.data.userEmail").value("u7@e.com"))
                .andExpect(jsonPath("$.data.joinedAt").exists());

        verify(userRepository).findById(7L);
    }

    @Test
    @DisplayName("GET /api/v1/auth/check — 유저 미조회 시 joinedAt=null (그 외 필드 정상 응답)")
    void checkLogin_userNotFound_joinedAtNull() throws Exception {
        given(userRepository.findById(7L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/auth/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("user7"))
                .andExpect(jsonPath("$.data.joinedAt").value(nullValue()));
    }
}
