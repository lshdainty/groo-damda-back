package io.groo.damda.security.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.groo.core.exception.ExternalServiceException;
import io.groo.core.exception.UnauthorizedException;
import io.groo.damda.common.config.properties.AppProperties;
import io.groo.damda.common.exception.DeskErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSO OAuth2 토큰 엔드포인트 클라이언트.
 *
 * <p>표준 Authorization Code 흐름에서 클라이언트 백엔드(BFF)가 SSO 의 {@code /oauth2/token} 에
 * 인가코드(code) + PKCE code_verifier 를 교환해 SSO access_token(JWT)을 받아온다.
 * 받아온 SSO 토큰은 기존 {@code exchangeToken} 경로로 검증·자체 desk JWT 발급에 재사용된다.
 *
 * <p>또한 표준 client_credentials 그랜트로 desk→SSO 서비스 토큰(RS256)을 발급한다 —
 * 비밀번호 변경/검증 프록시에서 SSO 를 호출할 때 Bearer 로 사용한다(레거시 HMAC 서비스 토큰 대체).
 */
@Slf4j
@Component
public class SsoOAuth2Client {

    private static final String TOKEN_ENDPOINT = "/api/v1/oauth2/token";
    private static final String REVOKE_ENDPOINT = "/api/v1/oauth2/revoke";
    private static final String DESK_CLIENT_ID = "desk";
    private static final String GRANT_TYPE = "authorization_code";
    private static final String GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials";
    private static final String GRANT_TYPE_REFRESH_TOKEN = "refresh_token";

    private final RestTemplate ssoRestTemplate;
    private final AppProperties appProperties;

    public SsoOAuth2Client(@Qualifier("ssoRestTemplate") RestTemplate ssoRestTemplate,
                           AppProperties appProperties) {
        this.ssoRestTemplate = ssoRestTemplate;
        this.appProperties = appProperties;
    }

    /**
     * SSO /oauth2/token 에 인가코드를 교환해 access + refresh 를 반환. 실패 시 AUTH_EXCHANGE_FAILED.
     *
     * <p>refresh 는 세션에 보관했다가 access 가 만료되면 조용히 재발급하는 데 쓴다. 예전에는
     * 받아 놓고 버렸고, 그래서 SSO refresh 수명(7일)이 있는데도 1시간마다 로그인 화면을 봤다.
     */
    public TokenPair exchangeCodeForToken(String code, String codeVerifier, String redirectUri) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", GRANT_TYPE);
        body.put("code", code);
        body.put("code_verifier", codeVerifier);
        body.put("client_id", DESK_CLIENT_ID);
        body.put("redirect_uri", redirectUri);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            TokenEnvelope envelope = ssoRestTemplate.postForObject(
                    TOKEN_ENDPOINT, new HttpEntity<>(body, headers), TokenEnvelope.class);
            if (envelope == null || envelope.data() == null
                    || envelope.data().accessToken() == null || envelope.data().accessToken().isBlank()) {
                log.error("SSO token exchange returned empty access_token");
                throw new UnauthorizedException(DeskErrorCode.AUTH_EXCHANGE_FAILED);
            }
            return new TokenPair(envelope.data().accessToken(), envelope.data().refreshToken());
        } catch (UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            log.error("SSO code exchange failed: {}", e.getMessage());
            throw new UnauthorizedException(DeskErrorCode.AUTH_EXCHANGE_FAILED);
        }
    }

    /**
     * SSO /oauth2/token 에 refresh_token 그랜트로 재발급 — 사용자 개입 없이 서버끼리 돈다.
     *
     * <p>실패를 두 갈래로 나눠 돌려준다. 이걸 뭉뚱그리면 SSO 가 잠깐 죽었을 때 전체 사용자가
     * 로그아웃된다.
     * <ul>
     *   <li>4xx (거부) → {@code null}. refresh 가 만료·폐기됐다는 확정 답이라 세션을 끊어야 한다.</li>
     *   <li>그 밖(5xx·타임아웃) → 예외. 일시적 장애이므로 세션은 그대로 두고 다음 요청에 다시 시도한다.</li>
     * </ul>
     */
    public TokenPair refreshTokens(String refreshToken) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", GRANT_TYPE_REFRESH_TOKEN);
        body.put("refresh_token", refreshToken);
        body.put("client_id", DESK_CLIENT_ID);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        TokenEnvelope envelope;
        try {
            envelope = ssoRestTemplate.postForObject(
                    TOKEN_ENDPOINT, new HttpEntity<>(body, headers), TokenEnvelope.class);
        } catch (HttpClientErrorException e) {
            // SSO 가 명시적으로 거부했다 — 만료·폐기·권한 회수. 다시 시도해도 결과는 같다.
            log.info("SSO refresh rejected: {}", e.getStatusCode());
            return null;
        }

        if (envelope == null || envelope.data() == null
                || envelope.data().accessToken() == null || envelope.data().accessToken().isBlank()) {
            log.error("SSO refresh returned empty access_token");
            return null;
        }
        return new TokenPair(envelope.data().accessToken(), envelope.data().refreshToken());
    }

    /**
     * SSO /oauth2/revoke 에 refresh token 을 넘겨 <b>그 로그인의 SSO 토큰 계열</b>을 끊는다(보안 점검 CLI-2).
     *
     * <p>앱은 시스템 브라우저로 로그인해서 브라우저에 SSO refresh 쿠키가 남는다. 앱에서 로그아웃해도 그 쿠키는
     * 앱이 지울 수 없어, 잠금 풀린 기기를 쥔 사람이 브라우저로 담다 웹을 열면 비밀번호 없이 들어갔다.
     * SSO 는 이 토큰과 같은 로그인에서 나온 토큰(브라우저 쿠키 포함)을 함께 끊는다. 다른 기기는 그대로다.
     *
     * <p>던지지 않는다 — 로그아웃은 SSO 가 죽어 있어도 끝나야 한다. 결과만 돌려준다.
     *
     * @return SSO 가 받아들였으면 true
     */
    public boolean revokeRefreshToken(String refreshToken) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("token", refreshToken);
        body.put("token_type_hint", GRANT_TYPE_REFRESH_TOKEN);
        body.put("client_id", DESK_CLIENT_ID);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ssoRestTemplate.postForObject(REVOKE_ENDPOINT, new HttpEntity<>(body, headers), TokenEnvelope.class);
            return true;
        } catch (Exception e) {
            log.warn("SSO 로그인 계열 폐기 실패: {}", e.getMessage());
            return false;
        }
    }

    /**
     * SSO /oauth2/token 에 client_credentials 그랜트로 desk 서비스 토큰(RS256)을 발급받아 access_token 을 반환.
     * <p>비밀번호 변경/검증 프록시에서 SSO 를 호출할 때 Bearer 로 사용한다. 실패 시 AUTH_EXCHANGE_FAILED.
     */
    /**
     * 이 사용자의 SSO 세션을 전부 끊는다 — "모든 기기에서 로그아웃".
     *
     * <p>desk 자기 세션은 호출부가 따로 끊는다. 이건 <b>SSO·hr 까지 함께 끊기게</b> 하려는
     * 것으로, SSO 가 전체 폐기와 함께 세션 폐기 이벤트를 내보내 다른 서비스에 전파한다.
     *
     * <p><b>desk 의 세션 폐기 구독자에서 부르면 안 된다.</b> SSO 이벤트 → desk 폐기 →
     * 다시 SSO 호출 → 또 이벤트로 무한히 돈다. 사용자가 버튼을 누른 경로에서만 부른다.
     *
     * @throws ExternalServiceException SSO 가 응답하지 못하면 — 호출부가 "일부만 끊겼다" 를
     *         사용자에게 알릴 수 있어야 한다. 조용히 삼키면 다른 기기가 살아 있는데
     *         끊었다고 믿게 된다
     */
    /**
     * SSO 에서 이 사용자의 <b>desk 접근만</b> 끊는다(계정은 그대로).
     *
     * <p>desk 가 자기 해지를 커밋한 <b>뒤</b> 부른다. 실패해도 해지를 되돌리지 않는다 —
     * desk 쪽은 이미 막혔고(토큰 교환이 USER_021 로 거절), 이건 이중 안전장치다.
     * 그래서 예외를 던지지 않고 로그만 남긴다.
     *
     * @param ssoUserRowId SSO 사용자 row ID
     * @return 성공 여부 — 호출부가 감사 로그에 남길 수 있게 돌려준다
     */
    public boolean deactivateDeskAccess(Long ssoUserRowId) {
        try {
            // 토큰 발급도 try 안이다 — SSO 가 죽으면 여기서 먼저 터지는데, 밖에 두면
            // 그 예외가 커밋 뒤 콜백을 뚫고 나가 **이미 끝난 해지가 실패 응답으로** 나갔다
            // (2026-09-17 QA). 이 메서드의 약속은 "던지지 않는다" 다.
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(issueServiceToken());

            ssoRestTemplate.exchange(
                    "/api/v1/clients/desk/users/" + ssoUserRowId + "/deactivate",
                    HttpMethod.POST,
                    new HttpEntity<>(headers),
                    Void.class);
            log.info("SSO desk 접근 비활성 완료. ssoUserRowId={}", ssoUserRowId);
            return true;
        } catch (Exception e) {
            // RestClientException 만 잡으면 토큰 발급 실패(인증 오류 등)가 새어 나간다.
            log.error("SSO desk 접근 비활성 실패 — desk 쪽은 이미 막혔다. ssoUserRowId={}",
                    ssoUserRowId, e);
            return false;
        }
    }

    public void revokeAllSessions(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(issueServiceToken());

        // 서비스 토큰에는 사용자 식별자가 없으므로 대상 userId 를 body 에 담는다.
        HttpEntity<Map<String, String>> entity =
                new HttpEntity<>(Map.of("userId", userId), headers);

        try {
            ssoRestTemplate.exchange(
                    "/api/v1/auth/sessions/revoke-all",
                    HttpMethod.POST,
                    entity,
                    Void.class);
            log.info("SSO 전체 세션 폐기 요청 완료. userId={}", userId);
        } catch (RestClientException e) {
            log.error("SSO 전체 세션 폐기 실패. userId={}", userId, e);
            throw new ExternalServiceException(DeskErrorCode.SSO_SERVICE_ERROR, e);
        }
    }

    public String issueServiceToken() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", GRANT_TYPE_CLIENT_CREDENTIALS);
        body.put("client_id", DESK_CLIENT_ID);
        body.put("client_secret", appProperties.getSso().getClientSecret());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            TokenEnvelope envelope = ssoRestTemplate.postForObject(
                    TOKEN_ENDPOINT, new HttpEntity<>(body, headers), TokenEnvelope.class);
            if (envelope == null || envelope.data() == null
                    || envelope.data().accessToken() == null || envelope.data().accessToken().isBlank()) {
                log.error("SSO client_credentials returned empty access_token");
                throw new UnauthorizedException(DeskErrorCode.AUTH_EXCHANGE_FAILED);
            }
            return envelope.data().accessToken();
        } catch (UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            log.error("SSO client_credentials failed: {}", e.getMessage());
            throw new UnauthorizedException(DeskErrorCode.AUTH_EXCHANGE_FAILED);
        }
    }

    /** SSO 가 내려준 토큰 한 쌍. refresh 는 client_credentials 응답처럼 없을 수도 있다. */
    public record TokenPair(String accessToken, String refreshToken) {
        public boolean hasRefreshToken() {
            return refreshToken != null && !refreshToken.isBlank();
        }
    }

    /** SSO ApiResponse 래퍼 (success/code/message/data). */
    record TokenEnvelope(boolean success, String code, String message, TokenData data) {}

    /** SSO 표준 토큰 응답 (snake_case). */
    record TokenData(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("refresh_token") String refreshToken) {}
}
