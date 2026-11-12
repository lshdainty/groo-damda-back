package io.groo.damda.security.client;

import io.groo.damda.common.config.properties.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SSO 로그인 계열 폐기 호출 — 보안 점검 CLI-2. 로그아웃 경로에서 부르므로 던지지 않아야 한다.
 */
class SsoOAuth2ClientRevokeTest {

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final SsoOAuth2Client client = new SsoOAuth2Client(restTemplate, new AppProperties());

    @Test
    @DisplayName("refresh 를 RFC 7009 모양으로 /api/v1/oauth2/revoke 에 보낸다")
    @SuppressWarnings("unchecked")
    void sendsRevokeRequest() {
        assertThat(client.revokeRefreshToken("app-refresh")).isTrue();

        ArgumentCaptor<HttpEntity<Map<String, String>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(eq("/api/v1/oauth2/revoke"), captor.capture(), any());
        assertThat(captor.getValue().getBody())
                .containsEntry("token", "app-refresh")
                .containsEntry("token_type_hint", "refresh_token")
                .containsEntry("client_id", "desk");
    }

    @Test
    @DisplayName("SSO 가 거부하거나 응답하지 못하면 false — 예외를 올리지 않는다")
    void failureReturnsFalse() {
        when(restTemplate.postForObject(eq("/api/v1/oauth2/revoke"), any(), any()))
                .thenThrow(HttpClientErrorException.create(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "bad", null, null, null))
                .thenThrow(new ResourceAccessException("timeout"));

        assertThat(client.revokeRefreshToken("app-refresh")).isFalse();
        assertThat(client.revokeRefreshToken("app-refresh")).isFalse();
    }
}
