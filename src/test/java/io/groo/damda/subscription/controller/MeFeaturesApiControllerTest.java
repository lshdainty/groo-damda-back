package io.groo.damda.subscription.controller;

import io.groo.core.util.MessageResolver;
import io.groo.damda.common.config.web.WebConfig;
import io.groo.damda.security.filter.JwtAuthenticationFilter;
import io.groo.damda.security.resolver.LoginUserArgumentResolver;
import io.groo.damda.subscription.service.SubscriptionEntitlementService;
import io.groo.damda.subscription.service.SubscriptionService;
import io.groo.damda.support.security.WithLoginUser;
import io.groo.damda.securities.service.SecuritiesCredentialService;
import io.groo.damda.securities.service.dto.SecuritiesCredentialServiceDto.BrokerConnection;
import io.groo.damda.securities.type.SecuritiesBroker;
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

import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MeFeatures API 슬라이스 테스트.
 *
 * <p>보안 필터는 끄고({@code addFilters=false}) {@link WithLoginUser} 로 SecurityContext 를 세팅 →
 * {@code @LoginUser} ArgumentResolver({@link WebConfig} 가 등록)가 UserPrincipal 을 주입한다.
 * 로그인 사용자의 기능권한 + 증권사 연결상태를 합쳐 응답하는지 검증한다.
 * tossConnected 는 구버전 클라이언트 호환용 파생값이라 함께 지킨다 — 사라지면 옛 앱이 미연결로 읽는다.
 */
@WebMvcTest(controllers = MeFeaturesApiController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, LoginUserArgumentResolver.class})
@ActiveProfiles("test")
@WithLoginUser(rowId = 1L)
class MeFeaturesApiControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private SubscriptionEntitlementService entitlementService;
    @MockitoBean private SecuritiesCredentialService credentialService;
    @MockitoBean private SubscriptionService subscriptionService;
    // groo-core GlobalExceptionHandler(@ControllerAdvice) 의존 — 슬라이스 로드용 mock.
    @MockitoBean private MessageResolver messageResolver;

    private static BrokerConnection connected(SecuritiesBroker broker, boolean primary) {
        return new BrokerConnection(broker, broker.getDisplayName(), broker.getIssueUrl(),
                broker.getKeyLabel(), broker.getSecretLabel(), true, true, null, primary);
    }

    @Test
    @DisplayName("GET /users/me/features — 기능권한 + 연결된 증권사 목록 + 기본 소스")
    void getMyFeatures_connected() throws Exception {
        given(entitlementService.getActiveFeatures(1L)).willReturn(List.of("SECURITIES"));
        given(credentialService.getConnections(1L)).willReturn(List.of(
                connected(SecuritiesBroker.TOSS, false),
                connected(SecuritiesBroker.NAMU, true)));
        given(credentialService.getPrimaryBroker(1L)).willReturn(Optional.of(SecuritiesBroker.NAMU));

        mockMvc.perform(get("/api/v1/users/me/features"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.features[0]").value("SECURITIES"))
                .andExpect(jsonPath("$.data.connectedBrokers[0]").value("TOSS"))
                .andExpect(jsonPath("$.data.connectedBrokers[1]").value("NAMU"))
                .andExpect(jsonPath("$.data.primaryBroker").value("NAMU"))
                // 구버전 앱은 이 필드만 본다. 지우면 증권 화면이 연결 유도로 되돌아간다.
                .andExpect(jsonPath("$.data.tossConnected").value(true));

        verify(entitlementService).getActiveFeatures(1L);
    }

    @Test
    @DisplayName("GET /users/me/features — 나무만 연결하면 tossConnected 는 false 다")
    void getMyFeatures_namuOnly() throws Exception {
        given(entitlementService.getActiveFeatures(1L)).willReturn(List.of("SECURITIES"));
        given(credentialService.getConnections(1L))
                .willReturn(List.of(connected(SecuritiesBroker.NAMU, true)));
        given(credentialService.getPrimaryBroker(1L)).willReturn(Optional.of(SecuritiesBroker.NAMU));

        mockMvc.perform(get("/api/v1/users/me/features"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectedBrokers[0]").value("NAMU"))
                .andExpect(jsonPath("$.data.tossConnected").value(false));
    }

    @Test
    @DisplayName("GET /users/me/features — 비구독·미연결이면 전부 비어 있다")
    void getMyFeatures_notConnected() throws Exception {
        given(entitlementService.getActiveFeatures(1L)).willReturn(List.of());
        given(credentialService.getConnections(1L))
                .willReturn(List.of(BrokerConnection.notConnected(SecuritiesBroker.TOSS)));
        given(credentialService.getPrimaryBroker(1L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/users/me/features"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.features").isEmpty())
                .andExpect(jsonPath("$.data.connectedBrokers").isEmpty())
                .andExpect(jsonPath("$.data.primaryBroker").doesNotExist())
                .andExpect(jsonPath("$.data.tossConnected").value(false));

        verify(entitlementService).getActiveFeatures(1L);
    }

    @Test
    @DisplayName("GET /users/me/features — 자가 가입 여부를 서버 설정대로 내려 준다(닫힘 false · 열림 true)")
    void getMyFeatures_selfSignupFlag() throws Exception {
        given(entitlementService.getActiveFeatures(1L)).willReturn(List.of());
        given(credentialService.getConnections(1L)).willReturn(List.of());
        given(credentialService.getPrimaryBroker(1L)).willReturn(Optional.empty());

        given(subscriptionService.isSelfSignupEnabled()).willReturn(false);
        mockMvc.perform(get("/api/v1/users/me/features"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscriptionSelfSignupEnabled").value(false));

        given(subscriptionService.isSelfSignupEnabled()).willReturn(true);
        mockMvc.perform(get("/api/v1/users/me/features"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscriptionSelfSignupEnabled").value(true));
    }
}
