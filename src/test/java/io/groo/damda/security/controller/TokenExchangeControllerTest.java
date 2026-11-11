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

    @Test
    @DisplayName("POST /api/v1/auth/logout — 이 기기의 SSO 세션도 같이 끊는다")
    void logout_revokesSsoSession() throws Exception {
        given(jwtProperties.getSessionExpiration()).willReturn(604_800_000L);

        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk());

        // 세션이 남으면 로그아웃한 사용자의 refresh token 이 최대 7일 더 살아 재발급 수단이 된다
        verify(ssoSessionService).revoke("test-session");
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
                "user7", "User7", "u7@e.com", 7L, "embed", "test-session"));
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
