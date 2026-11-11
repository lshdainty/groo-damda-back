package io.groo.damda.toss.client;

import io.groo.core.exception.ExternalServiceException;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.core.logging.UpstreamErrorLog;
import io.groo.damda.toss.client.dto.TossEnvelope;
import io.groo.damda.toss.client.dto.TossErrorBody;
import io.groo.damda.securities.client.BrokerTokenManager;
import io.groo.damda.securities.client.BrokerTokenManagers;
import io.groo.damda.securities.type.SecuritiesBroker;
import io.groo.damda.toss.config.TossProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 토스증권 Open API 저수준 호출 클라이언트.<br>
 * 모든 조회 GET 요청에 대해 (1) 액세스 토큰 자동 주입, (2) 성공 envelope({@code result}) 언래핑,
 * (3) 토큰 만료(401) 1회 재발급 재시도, (4) 에러 응답을 {@link ExternalServiceException} 으로 변환을 담당한다.
 *
 * <p>계좌/자산 관련 엔드포인트는 {@code X-Tossinvest-Account} 헤더로 계좌 식별자(accountSeq)를 함께 전달한다.</p>
 *
 * <p>경로/쿼리의 동적 값은 모두 URI 변수({@code {placeholder}})로 바인딩해 RestTemplate 이 정확히 1회만
 * 인코딩하도록 한다(동적 path 세그먼트를 문자열로 직접 결합하면 인코딩 누락·template 인젝션 위험).</p>
 */
@Slf4j
@Component
public class TossApiClient {

    private static final String ACCOUNT_HEADER = "X-Tossinvest-Account";

    private final RestTemplate tossRestTemplate;
    private final BrokerTokenManager tokenManager;
    private final TossProperties tossProperties;

    public TossApiClient(@Qualifier("tossRestTemplate") RestTemplate tossRestTemplate,
                         BrokerTokenManagers tokenManagers,
                         TossProperties tossProperties) {
        this.tossRestTemplate = tossRestTemplate;
        this.tokenManager = tokenManagers.of(SecuritiesBroker.TOSS);
        this.tossProperties = tossProperties;
    }

    /**
     * 시장 데이터 조회(사용자 개인 토큰). 시세·종목·시장정보 등.
     * 토스 API는 시세도 발급된 access token 으로만 호출하며 권한 scope 구분이 없으므로,
     * porest-desk 는 사용자가 등록한 본인 키로 대리 조회한다(본인 키 미등록 시 {@code SECURITIES_CREDENTIAL_REQUIRED}).
     */
    public <T> T get(Long userRowId, String path, MultiValueMap<String, String> query,
                     ParameterizedTypeReference<TossEnvelope<T>> typeRef) {
        ensureConfigured();
        return execute(path, null, query, null, typeRef, true,
            () -> tokenManager.authHeaders(userRowId),
            () -> tokenManager.invalidate(userRowId));
    }

    /**
     * 경로에 동적 세그먼트가 있는 시장 데이터 조회(예: {@code /api/v1/stocks/{symbol}/warnings}).
     * pathVars 의 값은 URI 변수로 안전하게 인코딩된다. 사용자 개인 토큰 사용.
     */
    public <T> T getPath(Long userRowId, String pathTemplate, Map<String, ?> pathVars,
                         ParameterizedTypeReference<TossEnvelope<T>> typeRef) {
        return getPath(userRowId, pathTemplate, pathVars, null, typeRef);
    }

    /**
     * 경로 동적 세그먼트와 쿼리 파라미터가 함께 있는 시장 데이터 조회
     * (예: {@code /api/v1/market-indicators/{symbol}/candles?interval=1d}). 사용자 개인 토큰 사용.
     */
    public <T> T getPath(Long userRowId, String pathTemplate, Map<String, ?> pathVars,
                         MultiValueMap<String, String> query,
                         ParameterizedTypeReference<TossEnvelope<T>> typeRef) {
        ensureConfigured();
        return execute(pathTemplate, pathVars, query, null, typeRef, true,
            () -> tokenManager.authHeaders(userRowId),
            () -> tokenManager.invalidate(userRowId));
    }

    /**
     * 계좌 데이터 조회(사용자 개인 토큰). 계좌목록·보유주식 등 본인 계좌 데이터.
     * accountSeq 가 있으면 {@code X-Tossinvest-Account} 헤더를 추가한다.
     * 본인 크리덴셜 미등록 시 {@code SECURITIES_CREDENTIAL_REQUIRED}.
     */
    public <T> T getForUser(Long userRowId, String path, MultiValueMap<String, String> query, Long accountSeq,
                            ParameterizedTypeReference<TossEnvelope<T>> typeRef) {
        ensureConfigured();
        return execute(path, null, query, accountSeq, typeRef, true,
            () -> tokenManager.authHeaders(userRowId),
            () -> tokenManager.invalidate(userRowId));
    }

    private void ensureConfigured() {
        if (!tossProperties.isConfigured()) {
            log.warn("토스증권 연동 설정(app.toss.base-url)이 비어 있습니다");
            throw new ExternalServiceException(DeskErrorCode.SECURITIES_NOT_CONFIGURED);
        }
    }

    private <T> T execute(String pathTemplate, Map<String, ?> pathVars, MultiValueMap<String, String> query,
                          Long accountSeq, ParameterizedTypeReference<TossEnvelope<T>> typeRef,
                          boolean retryOnUnauthorized,
                          java.util.function.Supplier<HttpHeaders> authHeaders, Runnable tokenInvalidator) {
        // 인증 헤더 구성은 증권사 담당자가 맡는다 — Bearer 하나로 끝나지 않는 곳이 있다.
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(authHeaders.get());
        if (accountSeq != null) {
            headers.set(ACCOUNT_HEADER, String.valueOf(accountSeq));
        }

        Map<String, Object> uriVars = new HashMap<>();
        if (pathVars != null) {
            uriVars.putAll(pathVars);
        }
        String uriTemplate = buildUriTemplate(pathTemplate, query, uriVars);

        try {
            ResponseEntity<TossEnvelope<T>> response = tossRestTemplate.exchange(
                    uriTemplate, HttpMethod.GET, new HttpEntity<>(headers), typeRef, uriVars);
            TossEnvelope<T> body = response.getBody();
            return body == null ? null : body.result();
        } catch (HttpClientErrorException.Unauthorized e) {
            if (retryOnUnauthorized) {
                // 토큰이 무효화되었을 수 있으므로 1회 재발급 후 재시도한다.
                log.debug("토스증권 401 - 토큰 재발급 후 재시도");
                tokenInvalidator.run();
                return execute(pathTemplate, pathVars, query, accountSeq, typeRef, false, authHeaders, tokenInvalidator);
            }
            throw toExternalException(e, pathTemplate);
        } catch (HttpStatusCodeException e) {
            throw toExternalException(e, pathTemplate);
        } catch (RestClientException e) {
            RestClientException safe = UpstreamErrorLog.redact(e);
            log.error("토스증권 API 호출 실패: path={}, 원인={}", pathTemplate, safe.getMessage());
            throw new ExternalServiceException(DeskErrorCode.SECURITIES_API_ERROR, safe);
        }
    }

    /**
     * 에러 응답 본문({@code { "error": {...} }})에서 상세를 추출해 <b>로그에만</b> 남기고,
     * 클라이언트에는 i18n 메시지(error.toss.api.error)만 내려가는 예외로 변환한다.
     * 업스트림 토스 에러 본문(code/message)을 그대로 응답에 릴레이하지 않는다.
     *
     * <p>로그로 가는 것도 {@link UpstreamErrorLog} 를 지난다. 예전에는 예외 객체를 로그에도
     * cause 에도 그대로 넘겼는데, Spring 은 응답 본문을 예외 메시지에 통째로 싣고 core 의 예외
     * 핸들러는 {@code ExternalServiceException} 을 객체째 찍는다 — 파싱한 {@code detail} 만
     * 가려 봐야 {@code Caused by:} 줄에 본문 전문이 다시 나온다.
     */
    private ExternalServiceException toExternalException(HttpStatusCodeException e, String path) {
        String detail = null;
        try {
            TossErrorBody errorBody = e.getResponseBodyAs(TossErrorBody.class);
            if (errorBody != null && errorBody.error() != null) {
                TossErrorBody.ApiError err = errorBody.error();
                detail = "code=" + err.code() + ", message=" + err.message() + ", requestId=" + err.requestId();
            }
        } catch (Exception ignore) {
            // 에러 본문 파싱 실패는 무시하고 상태코드 기반으로만 로깅한다.
        }
        RestClientException safe = UpstreamErrorLog.redact(e);
        log.error("토스증권 API 오류: path={}, status={}, {}, 원인={}", path, e.getStatusCode().value(),
                detail != null ? UpstreamErrorLog.safe(detail) : "(본문 파싱 불가)", safe.getMessage());
        // 401(재시도 후에도)·403 은 토큰 만료 · 키 무효 · 권한 회수라 다시 연결해야 한다. 본문 코드와 무관하게 상태가 우선이다.
        int status = e.getStatusCode().value();
        boolean authFailure = status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value();
        return new ExternalServiceException(
                authFailure ? DeskErrorCode.SECURITIES_AUTH_EXPIRED : DeskErrorCode.SECURITIES_API_ERROR, safe);
    }

    /**
     * baseUrl 은 {@code tossRestTemplate} 의 UriTemplateHandler 가 prepend 한다.
     * 쿼리 값은 placeholder 로 바인딩해 RestTemplate 이 한 번만 인코딩하도록 한다.
     */
    private String buildUriTemplate(String pathTemplate, MultiValueMap<String, String> query, Map<String, Object> uriVars) {
        if (query == null || query.isEmpty()) {
            return pathTemplate;
        }
        StringBuilder sb = new StringBuilder(pathTemplate);
        boolean first = true;
        int idx = 0;
        for (Map.Entry<String, List<String>> entry : query.entrySet()) {
            for (String value : entry.getValue()) {
                if (value == null) {
                    continue;
                }
                sb.append(first ? '?' : '&');
                String placeholder = "q" + (idx++);
                sb.append(entry.getKey()).append('=').append('{').append(placeholder).append('}');
                uriVars.put(placeholder, value);
                first = false;
            }
        }
        return sb.toString();
    }
}
