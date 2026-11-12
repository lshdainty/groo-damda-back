package io.groo.damda.user.service;

import io.groo.core.controller.ApiResponse;
import io.groo.core.exception.ExternalServiceException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.security.client.SsoOAuth2Client;
import io.groo.damda.user.controller.dto.OAuthLinkDto.ProviderInfoResp;
import io.groo.damda.user.controller.dto.OAuthLinkDto.StartUrlResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 소셜 계정 연동(link) 프록시 서비스 단위 테스트 — client_credentials 서비스 토큰(Bearer)으로
 * SSO 에 userId 를 relay 하는 BFF 패턴(비밀번호 프록시와 동일)과 3분기 에러 변환 검증.
 */
@ExtendWith(MockitoExtension.class)
class OAuthLinkServiceImplTest {

    @Mock private SsoOAuth2Client ssoOAuth2Client;
    @Mock private RestTemplate ssoRestTemplate;

    @InjectMocks private OAuthLinkServiceImpl sut;

    private static final String SVC_TOKEN = "svc-token";
    private static final String USER_ID = "u1";
    private static final String LINK_GOOGLE_PATH = "/api/v1/oauth/link/google";
    private static final String PROVIDERS_PATH = "/api/v1/oauth/providers?userId=u1";
    private static final String UNLINK_GOOGLE_PATH = "/api/v1/oauth/link/google?userId=u1";

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<HttpEntity<Map<String, String>>> bodyEntityCaptor() {
        return ArgumentCaptor.forClass(HttpEntity.class);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<HttpEntity<Void>> headerEntityCaptor() {
        return ArgumentCaptor.forClass(HttpEntity.class);
    }

    // ==================== startLink ====================

    @Test
    @DisplayName("startLink — 서비스 토큰을 Bearer 로, body 에 userId/returnUrl 포함해 SSO 호출하고 startUrl 반환")
    void startLink_sendsServiceTokenAndBody_returnsStartUrl() {
        given(ssoOAuth2Client.issueServiceToken()).willReturn(SVC_TOKEN);
        given(ssoRestTemplate.exchange(eq(LINK_GOOGLE_PATH), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .willReturn(ResponseEntity.ok(ApiResponse.success(
                        StartUrlResp.builder().startUrl("https://sso.porest.com/oauth/link/start?token=abc").build())));

        // provider 대문자로 넘겨도 SSO 경로는 소문자로 변환
        String startUrl = sut.startLink(USER_ID, "Google", "https://desk.porest.com/settings", "REAUTH-T");

        ArgumentCaptor<HttpEntity<Map<String, String>>> captor = bodyEntityCaptor();
        org.mockito.Mockito.verify(ssoRestTemplate).exchange(eq(LINK_GOOGLE_PATH), eq(HttpMethod.POST),
                captor.capture(), any(ParameterizedTypeReference.class));

        HttpEntity<Map<String, String>> entity = captor.getValue();
        assertThat(entity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + SVC_TOKEN);
        assertThat(entity.getBody())
                .containsEntry("userId", USER_ID)
                .containsEntry("returnUrl", "https://desk.porest.com/settings")
                // 재인증 티켓은 SSO 가 검증·소모한다 — 그대로 넘긴다(보안 점검 S-2)
                .containsEntry("reauthToken", "REAUTH-T");
        assertThat(startUrl).isEqualTo("https://sso.porest.com/oauth/link/start?token=abc");
    }

    @Test
    @DisplayName("startLink — returnUrl 이 null 이면 빈 문자열로 relay")
    void startLink_nullReturnUrl_sendsEmptyString() {
        given(ssoOAuth2Client.issueServiceToken()).willReturn(SVC_TOKEN);
        given(ssoRestTemplate.exchange(eq(LINK_GOOGLE_PATH), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .willReturn(ResponseEntity.ok(ApiResponse.success(
                        StartUrlResp.builder().startUrl("https://sso/start").build())));

        sut.startLink(USER_ID, "google", null, null);

        ArgumentCaptor<HttpEntity<Map<String, String>>> captor = bodyEntityCaptor();
        org.mockito.Mockito.verify(ssoRestTemplate).exchange(eq(LINK_GOOGLE_PATH), eq(HttpMethod.POST),
                captor.capture(), any(ParameterizedTypeReference.class));
        assertThat(captor.getValue().getBody()).containsEntry("returnUrl", "")
                .containsEntry("reauthToken", "");
    }

    @Test
    @DisplayName("startLink — SSO 응답 success=false 면 InvalidValueException")
    void startLink_throwsWhenSsoNotSuccess() {
        given(ssoOAuth2Client.issueServiceToken()).willReturn(SVC_TOKEN);
        given(ssoRestTemplate.exchange(eq(LINK_GOOGLE_PATH), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .willReturn(ResponseEntity.ok(ApiResponse.error("FAIL", "이미 연동된 계정입니다")));

        assertThatThrownBy(() -> sut.startLink(USER_ID, "google", null, "REAUTH-T"))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("startLink — SSO 4xx(HttpClientErrorException) 면 InvalidValueException 으로 변환")
    void startLink_throwsOnClientError() {
        given(ssoOAuth2Client.issueServiceToken()).willReturn(SVC_TOKEN);
        given(ssoRestTemplate.exchange(eq(LINK_GOOGLE_PATH), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .willThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                        HttpHeaders.EMPTY, "{\"message\":\"잘못된 provider\"}".getBytes(), null));

        assertThatThrownBy(() -> sut.startLink(USER_ID, "google", null, "REAUTH-T"))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("startLink — SSO 연결 실패(RestClientException) 면 ExternalServiceException")
    void startLink_throwsOnTransportError() {
        given(ssoOAuth2Client.issueServiceToken()).willReturn(SVC_TOKEN);
        given(ssoRestTemplate.exchange(eq(LINK_GOOGLE_PATH), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .willThrow(new ResourceAccessException("connection refused"));

        assertThatThrownBy(() -> sut.startLink(USER_ID, "google", null, "REAUTH-T"))
                .isInstanceOf(ExternalServiceException.class);
    }

    // ==================== getProviders ====================

    @Test
    @DisplayName("getProviders — Bearer 서비스 토큰으로 userId 쿼리 붙여 GET 호출하고 data 반환")
    void getProviders_sendsServiceToken_returnsData() {
        given(ssoOAuth2Client.issueServiceToken()).willReturn(SVC_TOKEN);
        given(ssoRestTemplate.exchange(eq(PROVIDERS_PATH), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .willReturn(ResponseEntity.ok(ApiResponse.success(List.of(
                        ProviderInfoResp.builder().type("GOOGLE").name("Google")
                                .authUrl("https://sso/auth/google").linked(true).build()))));

        List<ProviderInfoResp> providers = sut.getProviders(USER_ID);

        ArgumentCaptor<HttpEntity<Void>> captor = headerEntityCaptor();
        org.mockito.Mockito.verify(ssoRestTemplate).exchange(eq(PROVIDERS_PATH), eq(HttpMethod.GET),
                captor.capture(), any(ParameterizedTypeReference.class));

        assertThat(captor.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer " + SVC_TOKEN);
        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).getType()).isEqualTo("GOOGLE");
        assertThat(providers.get(0).isLinked()).isTrue();
    }

    @Test
    @DisplayName("getProviders — SSO 응답 success=false 면 InvalidValueException")
    void getProviders_throwsWhenSsoNotSuccess() {
        given(ssoOAuth2Client.issueServiceToken()).willReturn(SVC_TOKEN);
        given(ssoRestTemplate.exchange(eq(PROVIDERS_PATH), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .willReturn(ResponseEntity.ok(ApiResponse.error("FAIL", "조회 실패")));

        assertThatThrownBy(() -> sut.getProviders(USER_ID))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("getProviders — SSO 4xx(HttpClientErrorException) 면 InvalidValueException 으로 변환")
    void getProviders_throwsOnClientError() {
        given(ssoOAuth2Client.issueServiceToken()).willReturn(SVC_TOKEN);
        given(ssoRestTemplate.exchange(eq(PROVIDERS_PATH), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .willThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                        HttpHeaders.EMPTY, "{\"message\":\"userId 누락\"}".getBytes(), null));

        assertThatThrownBy(() -> sut.getProviders(USER_ID))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("getProviders — SSO 연결 실패(RestClientException) 면 ExternalServiceException")
    void getProviders_throwsOnTransportError() {
        given(ssoOAuth2Client.issueServiceToken()).willReturn(SVC_TOKEN);
        given(ssoRestTemplate.exchange(eq(PROVIDERS_PATH), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .willThrow(new ResourceAccessException("connection refused"));

        assertThatThrownBy(() -> sut.getProviders(USER_ID))
                .isInstanceOf(ExternalServiceException.class);
    }

    // ==================== unlink ====================

    @Test
    @DisplayName("unlink — Bearer 서비스 토큰으로 provider·userId 쿼리 붙여 DELETE 호출")
    void unlink_sendsServiceToken_callsDelete() {
        given(ssoOAuth2Client.issueServiceToken()).willReturn(SVC_TOKEN);
        given(ssoRestTemplate.exchange(eq(UNLINK_GOOGLE_PATH), eq(HttpMethod.DELETE), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .willReturn(ResponseEntity.ok(ApiResponse.success()));

        // provider 대문자로 넘겨도 소문자로 변환
        sut.unlink(USER_ID, "GOOGLE", "ticket-u");

        ArgumentCaptor<HttpEntity<Void>> captor = headerEntityCaptor();
        org.mockito.Mockito.verify(ssoRestTemplate).exchange(eq(UNLINK_GOOGLE_PATH), eq(HttpMethod.DELETE),
                captor.capture(), any(ParameterizedTypeReference.class));

        assertThat(captor.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer " + SVC_TOKEN);
        // 재인증 티켓을 헤더로 SSO 에 넘긴다(S-2 잔여)
        assertThat(captor.getValue().getHeaders().getFirst("X-Reauth-Token")).isEqualTo("ticket-u");
    }

    @Test
    @DisplayName("unlink — 티켓이 없으면 헤더를 싣지 않는다(SSO 가 거절하게 둔다)")
    void unlink_withoutTicket_noHeader() {
        given(ssoOAuth2Client.issueServiceToken()).willReturn(SVC_TOKEN);
        given(ssoRestTemplate.exchange(eq(UNLINK_GOOGLE_PATH), eq(HttpMethod.DELETE), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .willReturn(ResponseEntity.ok(ApiResponse.success()));

        sut.unlink(USER_ID, "google", null);

        ArgumentCaptor<HttpEntity<Void>> captor = headerEntityCaptor();
        org.mockito.Mockito.verify(ssoRestTemplate).exchange(eq(UNLINK_GOOGLE_PATH), eq(HttpMethod.DELETE),
                captor.capture(), any(ParameterizedTypeReference.class));
        assertThat(captor.getValue().getHeaders().getFirst("X-Reauth-Token")).isNull();
    }

    @Test
    @DisplayName("unlink — SSO 응답 success=false 면 InvalidValueException")
    void unlink_throwsWhenSsoNotSuccess() {
        given(ssoOAuth2Client.issueServiceToken()).willReturn(SVC_TOKEN);
        given(ssoRestTemplate.exchange(eq(UNLINK_GOOGLE_PATH), eq(HttpMethod.DELETE), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .willReturn(ResponseEntity.ok(ApiResponse.error("FAIL", "연동되지 않은 계정입니다")));

        assertThatThrownBy(() -> sut.unlink(USER_ID, "google", "ticket-u"))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("unlink — SSO 4xx(HttpClientErrorException) 면 InvalidValueException 으로 변환")
    void unlink_throwsOnClientError() {
        given(ssoOAuth2Client.issueServiceToken()).willReturn(SVC_TOKEN);
        given(ssoRestTemplate.exchange(eq(UNLINK_GOOGLE_PATH), eq(HttpMethod.DELETE), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .willThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                        HttpHeaders.EMPTY, "{\"message\":\"연동 없음\"}".getBytes(), null));

        assertThatThrownBy(() -> sut.unlink(USER_ID, "google", "ticket-u"))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("unlink — SSO 연결 실패(RestClientException) 면 ExternalServiceException")
    void unlink_throwsOnTransportError() {
        given(ssoOAuth2Client.issueServiceToken()).willReturn(SVC_TOKEN);
        given(ssoRestTemplate.exchange(eq(UNLINK_GOOGLE_PATH), eq(HttpMethod.DELETE), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .willThrow(new ResourceAccessException("connection refused"));

        assertThatThrownBy(() -> sut.unlink(USER_ID, "google", "ticket-u"))
                .isInstanceOf(ExternalServiceException.class);
    }

    // ==================== provider 허용 목록 (보안 점검 S-2 잔여) ====================

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "..%2Fadmin", "../users", "google?userId=victim", "google#", "github", ""})
    @DisplayName("SSO 가 모르는 제공자 값은 연동·해제 모두 서비스 토큰을 받기 전에 거절한다 — 주소에 이어 붙이지 않는다")
    void rejectsUnknownProvider(String provider) {
        assertThatThrownBy(() -> sut.startLink(USER_ID, provider, null, "t"))
                .isInstanceOf(io.groo.core.exception.InvalidValueException.class)
                .extracting("errorCode").isEqualTo(io.groo.damda.common.exception.DeskErrorCode.OAUTH_PROVIDER_INVALID);
        assertThatThrownBy(() -> sut.unlink(USER_ID, provider, "t"))
                .isInstanceOf(io.groo.core.exception.InvalidValueException.class);
        org.mockito.Mockito.verifyNoInteractions(ssoOAuth2Client, ssoRestTemplate);
    }

    @Test
    @DisplayName("대소문자 · 앞뒤 공백은 정규화해 SSO 제공자(google · kakao · naver)로 받는다")
    void acceptsKnownProvidersCaseInsensitive() {
        assertThat(OAuthLinkServiceImpl.pathSegmentOf(" GOOGLE ")).isEqualTo("google");
        assertThat(OAuthLinkServiceImpl.pathSegmentOf("Kakao")).isEqualTo("kakao");
        assertThat(OAuthLinkServiceImpl.pathSegmentOf("naver")).isEqualTo("naver");
    }
}
