package io.groo.damda.subscription.controller;

import io.groo.core.util.MessageResolver;
import io.groo.damda.common.config.web.WebConfig;
import io.groo.damda.security.filter.JwtAuthenticationFilter;
import io.groo.damda.security.resolver.LoginUserArgumentResolver;
import io.groo.damda.subscription.service.SubscriptionService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
 * SubscriptionApiController 슬라이스 테스트.
 *
 * <p>보안 필터는 끄고({@code addFilters=false}) {@link WithLoginUser} 로 SecurityContext 세팅 →
 * {@code @LoginUser} rowId 위임·바디 역직렬화·응답 매핑을 검증한다. 구독 서비스는 mock.
 * plans 엔드포인트는 로그인 사용자 없이 인증만 필요.
 */
@WebMvcTest(controllers = SubscriptionApiController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, LoginUserArgumentResolver.class})
@ActiveProfiles("test")
@WithLoginUser(rowId = 1L)
class SubscriptionApiControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private SubscriptionService subscriptionService;
    // groo-core GlobalExceptionHandler(@ControllerAdvice) 의존 — 슬라이스 로드용 mock.
    @MockitoBean private MessageResolver messageResolver;

    private SubscriptionService.SubscriptionInfo sampleInfo() {
        return new SubscriptionService.SubscriptionInfo(
                "SECURITIES", "증권 플랜", "ACTIVE",
                LocalDateTime.of(2026, 7, 1, 0, 0),
                LocalDateTime.of(2026, 8, 1, 0, 0),
                true, 9900, 1);
    }

    @Test
    @DisplayName("GET /subscriptions/plans — 활성 플랜 목록 조회")
    void getPlans() throws Exception {
        given(subscriptionService.getActivePlans()).willReturn(List.of(
                new SubscriptionService.PlanInfo("SECURITIES", "증권 플랜", 1, 9900)));

        mockMvc.perform(get("/api/v1/subscriptions/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].planCode").value("SECURITIES"))
                .andExpect(jsonPath("$.data[0].durationMonths").value(1));

        verify(subscriptionService).getActivePlans();
    }

    @Test
    @DisplayName("POST /subscriptions — 로그인 rowId·planCode 로 구독 부여, 응답 매핑")
    void subscribe() throws Exception {
        given(subscriptionService.subscribe(1L, "SECURITIES")).willReturn(sampleInfo());

        String body = """
                {"planCode":"SECURITIES"}
                """;

        mockMvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planCode").value("SECURITIES"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.autoRenew").value(true));

        verify(subscriptionService).subscribe(1L, "SECURITIES");
    }

    @Test
    @DisplayName("DELETE /subscriptions/me — 로그인 rowId·사유로 해지 위임")
    void cancel_withReason() throws Exception {
        String body = """
                {"reason":"더 이상 사용 안 함"}
                """;

        mockMvc.perform(delete("/api/v1/subscriptions/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(subscriptionService).cancel(1L, "더 이상 사용 안 함");
    }

    @Test
    @DisplayName("DELETE /subscriptions/me — 바디 없으면 사유 null 로 해지 위임")
    void cancel_withoutBody() throws Exception {
        mockMvc.perform(delete("/api/v1/subscriptions/me"))
                .andExpect(status().isOk());

        verify(subscriptionService).cancel(eq(1L), isNull());
    }

    @Test
    @DisplayName("GET /subscriptions/me — 구독 있으면 응답 매핑")
    void getMySubscription_present() throws Exception {
        given(subscriptionService.getMySubscription(1L)).willReturn(Optional.of(sampleInfo()));

        mockMvc.perform(get("/api/v1/subscriptions/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planCode").value("SECURITIES"))
                .andExpect(jsonPath("$.data.planName").value("증권 플랜"));

        verify(subscriptionService).getMySubscription(1L);
    }

    @Test
    @DisplayName("GET /subscriptions/me — 구독 없으면 data null")
    void getMySubscription_absent() throws Exception {
        given(subscriptionService.getMySubscription(1L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/subscriptions/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));

        verify(subscriptionService).getMySubscription(1L);
    }
}
