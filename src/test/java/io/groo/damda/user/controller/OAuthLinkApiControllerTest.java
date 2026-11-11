package io.groo.damda.user.controller;

import io.groo.core.util.MessageResolver;
import io.groo.damda.common.config.web.WebConfig;
import io.groo.damda.security.filter.JwtAuthenticationFilter;
import io.groo.damda.security.resolver.LoginUserArgumentResolver;
import io.groo.damda.support.security.WithLoginUser;
import io.groo.damda.user.controller.dto.OAuthLinkDto;
import io.groo.damda.user.service.OAuthLinkService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OAuthLinkApiController(BFF 프록시) 슬라이스 테스트.
 *
 * <p>보안 필터는 끄고({@code addFilters=false}) {@link WithLoginUser} 로 SecurityContext 를 세팅 →
 * {@code @LoginUser} 로 주입된 userId 를 서비스에 relay 하는지, path/body 매핑을 검증한다.
 * SSO relay 서비스는 mock.
 */
@WebMvcTest(controllers = OAuthLinkApiController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, LoginUserArgumentResolver.class})
@ActiveProfiles("test")
@WithLoginUser(rowId = 1L, userId = "user1")
class OAuthLinkApiControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private OAuthLinkService oAuthLinkService;
    // groo-core GlobalExceptionHandler(@ControllerAdvice) 의존 — 슬라이스 로드용 mock.
    @MockitoBean private MessageResolver messageResolver;

    @Test
    @DisplayName("POST /oauth/link/{provider} — provider·returnUrl 로 startLink, 시작 URL 반환")
    void startLink_withReturnUrl() throws Exception {
        given(oAuthLinkService.startLink("user1", "google", "/settings/account", "REAUTH-T"))
                .willReturn("https://sso.example.com/link/start?token=abc");

        String body = """
                {"returnUrl":"/settings/account"}
                """;

        mockMvc.perform(post("/api/v1/oauth/link/{provider}", "google")
                        .header("X-Reauth-Token", "REAUTH-T")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startUrl").value("https://sso.example.com/link/start?token=abc"));

        // 재인증 티켓 헤더를 서비스로 그대로 넘긴다(검증은 SSO, 보안 점검 S-2)
        verify(oAuthLinkService).startLink("user1", "google", "/settings/account", "REAUTH-T");
    }

    @Test
    @DisplayName("POST /oauth/link/{provider} — 바디 없으면 returnUrl 은 null 로 relay")
    void startLink_withoutBody() throws Exception {
        given(oAuthLinkService.startLink(eq("user1"), eq("kakao"), isNull(), isNull()))
                .willReturn("https://sso.example.com/link/start");

        mockMvc.perform(post("/api/v1/oauth/link/{provider}", "kakao"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startUrl").value("https://sso.example.com/link/start"));

        verify(oAuthLinkService).startLink(eq("user1"), eq("kakao"), isNull(), isNull());
    }

    @Test
    @DisplayName("GET /oauth/providers — 로그인 userId 로 제공자 목록·연동 상태 조회")
    void getProviders() throws Exception {
        given(oAuthLinkService.getProviders("user1")).willReturn(List.of(
                OAuthLinkDto.ProviderInfoResp.builder()
                        .type("google").name("Google").authUrl("https://sso/google").linked(true).build(),
                OAuthLinkDto.ProviderInfoResp.builder()
                        .type("kakao").name("Kakao").authUrl("https://sso/kakao").linked(false).build()));

        mockMvc.perform(get("/api/v1/oauth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].type").value("google"))
                .andExpect(jsonPath("$.data[0].linked").value(true))
                .andExpect(jsonPath("$.data[1].linked").value(false));

        verify(oAuthLinkService).getProviders("user1");
    }

    @Test
    @DisplayName("DELETE /oauth/link/{provider} — provider·로그인 userId 로 연동 해제 위임")
    void unlink() throws Exception {
        mockMvc.perform(delete("/api/v1/oauth/link/{provider}", "google"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(oAuthLinkService).unlink("user1", "google");
    }
}
