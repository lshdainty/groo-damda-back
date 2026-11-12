package io.groo.damda.user.service;

import java.util.Set;

import java.util.Locale;

import io.groo.core.controller.ApiResponse;
import io.groo.core.exception.ExternalServiceException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.security.client.SsoOAuth2Client;
import io.groo.damda.user.controller.dto.OAuthLinkDto.ProviderInfoResp;
import io.groo.damda.user.controller.dto.OAuthLinkDto.StartUrlResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 소셜 계정 연동(link) 프록시 서비스.
 *
 * <p>비밀번호 변경 프록시({@code UserServiceImpl})와 동일한 BFF 패턴 —
 * client_credentials 서비스 토큰을 Bearer 로, 대상 userId 를 SSO 계약(body/쿼리)으로 relay 한다.
 * 서비스 토큰에는 사용자 식별자가 없으므로 desk 쿠키에서 얻은 userId 를 SSO 에 대신 전달한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthLinkServiceImpl implements OAuthLinkService {

    private static final String SSO_LINK_PATH = "/api/v1/oauth/link/";
    private static final String SSO_PROVIDERS_PATH = "/api/v1/oauth/providers";

    private final SsoOAuth2Client ssoOAuth2Client;

    @Qualifier("ssoRestTemplate")
    private final RestTemplate ssoRestTemplate;

    /**
     * SSO 가 아는 소셜 제공자 — SSO {@code ProviderType}(GOOGLE · KAKAO · NAVER)와 짝이다.
     *
     * <p>경로 변수 {@code provider} 를 그대로 SSO 주소에 이어 붙이면 {@code ..%2F} · {@code ?x=} 같은 값으로
     * 서비스 토큰을 든 요청이 SSO 의 다른 경로로 갈 수 있었다(보안 점검 S-2 잔여). 목록에 있는 값만 받는다.
     * 지금 연결할 수 있는 것은 Google 뿐이지만, 해제는 예전에 연결한 다른 제공자에도 쓰이므로 SSO 목록 전체를 둔다.
     */
    static final Set<String> ALLOWED_PROVIDERS = Set.of("google", "kakao", "naver");

    /** 허용 목록에 있으면 SSO 경로 조각(소문자), 없으면 거절. */
    static String pathSegmentOf(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_PROVIDERS.contains(normalized)) {
            throw new InvalidValueException(DeskErrorCode.OAUTH_PROVIDER_INVALID);
        }
        return normalized;
    }

    @Override
    public String startLink(String userId, String provider, String returnUrl, String reauthToken) {
        String segment = pathSegmentOf(provider); // 서비스 토큰을 받기 전에 거른다
        // SSO 호출용 서비스 토큰(RS256)을 client_credentials 그랜트로 발급
        String serviceToken = ssoOAuth2Client.issueServiceToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(serviceToken);

        // 서비스 토큰에는 사용자 식별자가 없으므로 대상 userId 를 body 에 담아 전달
        // 재인증 티켓은 SSO 가 검증·소모한다(보안 점검 S-2). 없으면 빈 값으로 보내 SSO 가 거절하게 둔다.
        Map<String, String> requestBody = Map.of(
                "userId", userId,
                "returnUrl", returnUrl == null ? "" : returnUrl,
                "reauthToken", reauthToken == null ? "" : reauthToken
        );
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<ApiResponse<StartUrlResp>> response = ssoRestTemplate.exchange(
                    SSO_LINK_PATH + segment,
                    HttpMethod.POST,
                    entity,
                    new ParameterizedTypeReference<ApiResponse<StartUrlResp>>() {}
            );

            ApiResponse<StartUrlResp> body = response.getBody();
            if (body != null && !body.isSuccess()) {
                throw new InvalidValueException(DeskErrorCode.OAUTH_LINK_FAILED, body.getMessage());
            }

            log.info("OAuth link started for user {} provider {}", userId, provider);
            return body != null && body.getData() != null ? body.getData().getStartUrl() : null;

        } catch (HttpClientErrorException e) {
            log.warn("SSO oauth link client error for user {} provider {}: {}", userId, provider, e.getMessage());
            String errorMessage = extractSsoErrorMessage(e);
            throw new InvalidValueException(DeskErrorCode.OAUTH_LINK_FAILED, errorMessage);
        } catch (RestClientException e) {
            log.error("SSO oauth link request failed for user {} provider {}: {}", userId, provider, e.getMessage(), e);
            throw new ExternalServiceException(DeskErrorCode.SSO_SERVICE_ERROR, e);
        }
    }

    @Override
    public List<ProviderInfoResp> getProviders(String userId) {
        String serviceToken = ssoOAuth2Client.issueServiceToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(serviceToken);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<ApiResponse<List<ProviderInfoResp>>> response = ssoRestTemplate.exchange(
                    SSO_PROVIDERS_PATH + "?userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8),
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<ApiResponse<List<ProviderInfoResp>>>() {}
            );

            ApiResponse<List<ProviderInfoResp>> body = response.getBody();
            if (body != null && !body.isSuccess()) {
                throw new InvalidValueException(DeskErrorCode.OAUTH_LINK_FAILED, body.getMessage());
            }

            return body != null ? body.getData() : null;

        } catch (HttpClientErrorException e) {
            log.warn("SSO oauth providers client error for user {}: {}", userId, e.getMessage());
            String errorMessage = extractSsoErrorMessage(e);
            throw new InvalidValueException(DeskErrorCode.OAUTH_LINK_FAILED, errorMessage);
        } catch (RestClientException e) {
            log.error("SSO oauth providers request failed for user {}: {}", userId, e.getMessage(), e);
            throw new ExternalServiceException(DeskErrorCode.SSO_SERVICE_ERROR, e);
        }
    }

    @Override
    public void unlink(String userId, String provider, String reauthToken) {
        String segment = pathSegmentOf(provider); // 서비스 토큰을 받기 전에 거른다
        String serviceToken = ssoOAuth2Client.issueServiceToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(serviceToken);
        // 해제도 본인 확인 티켓을 요구한다(보안 점검 S-2 잔여). DELETE 라 본문 대신 헤더로 넘긴다.
        // 티켓이 없으면 싣지 않는다 — SSO 가 거절하게 둔다(옛 SSO 는 무시한다: 담다를 먼저 내보낼 수 있다).
        if (reauthToken != null && !reauthToken.isBlank()) {
            headers.set("X-Reauth-Token", reauthToken);
        }

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<ApiResponse<Void>> response = ssoRestTemplate.exchange(
                    SSO_LINK_PATH + segment + "?userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8),
                    HttpMethod.DELETE,
                    entity,
                    new ParameterizedTypeReference<ApiResponse<Void>>() {}
            );

            ApiResponse<Void> body = response.getBody();
            if (body != null && !body.isSuccess()) {
                throw new InvalidValueException(DeskErrorCode.OAUTH_LINK_FAILED, body.getMessage());
            }

            log.info("OAuth unlink done for user {} provider {}", userId, provider);

        } catch (HttpClientErrorException e) {
            log.warn("SSO oauth unlink client error for user {} provider {}: {}", userId, provider, e.getMessage());
            String errorMessage = extractSsoErrorMessage(e);
            throw new InvalidValueException(DeskErrorCode.OAUTH_LINK_FAILED, errorMessage);
        } catch (RestClientException e) {
            log.error("SSO oauth unlink request failed for user {} provider {}: {}", userId, provider, e.getMessage(), e);
            throw new ExternalServiceException(DeskErrorCode.SSO_SERVICE_ERROR, e);
        }
    }

    private String extractSsoErrorMessage(HttpClientErrorException e) {
        try {
            String responseBody = e.getResponseBodyAsString();
            // JSON에서 message 필드 추출 (간단한 파싱)
            if (responseBody.contains("\"message\"")) {
                int start = responseBody.indexOf("\"message\"") + 11;
                int end = responseBody.indexOf("\"", start);
                if (end > start) {
                    return responseBody.substring(start, end);
                }
            }
        } catch (Exception ex) {
            log.debug("Failed to extract SSO error message", ex);
        }
        return "계정을 연동하지 못했어요";
    }
}
