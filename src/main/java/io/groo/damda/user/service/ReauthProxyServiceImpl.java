package io.groo.damda.user.service;

import io.groo.damda.user.type.ReauthPurpose;

import io.groo.core.controller.ApiResponse;
import io.groo.core.exception.ExternalServiceException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.security.client.SsoOAuth2Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReauthProxyServiceImpl implements ReauthProxyService {

    private static final String SSO_EMAIL_CODE_PATH = "/api/v1/auth/reauth/email-code";
    private static final String SSO_EMAIL_CODE_VERIFY_PATH = "/api/v1/auth/reauth/email-code/verify";
    private static final String SSO_PASSWORD_PATH = "/api/v1/auth/reauth/password";

    /*
     * 티켓 용도는 닫힌 목록({@link ReauthPurpose})에서만 고른다 — 열린 문자열이면 아무 용도의 티켓이나
     * 발급받아 {@link ReauthTicketVerifier} 의 용도 대조가 의미를 잃는다. 해지 하나뿐이던 것을
     * 소셜 연동 시작(보안 점검 S-2)이 두 번째 용도로 열었다.
     */

    private final SsoOAuth2Client ssoOAuth2Client;

    @Qualifier("ssoRestTemplate")
    private final RestTemplate ssoRestTemplate;

    @Override
    public void sendEmailCode(String userId) {
        call(SSO_EMAIL_CODE_PATH, Map.of("userId", userId), userId, "코드 발송");
    }

    @Override
    public String verifyEmailCode(String userId, String code, ReauthPurpose purpose) {
        VerifyResp resp = call(SSO_EMAIL_CODE_VERIFY_PATH,
                Map.of("userId", userId, "code", code, "purpose", purpose.wire()),
                userId, "코드 확인");
        return ticketOf(resp, userId);
    }

    @Override
    public String verifyPassword(String userId, String password, ReauthPurpose purpose) {
        VerifyResp resp = call(SSO_PASSWORD_PATH,
                Map.of("userId", userId, "password", password, "purpose", purpose.wire()),
                userId, "비밀번호 확인");
        return ticketOf(resp, userId);
    }

    /**
     * SSO 재인증 API 한 번 호출.
     *
     * <p>서비스 토큰에는 사용자 식별자가 없으므로 대상 {@code userId} 를 body 에 담아
     * 전달한다(SSO {@code ReauthApiController.resolveTarget} 계약).
     *
     * <p>4xx 는 사용자가 고칠 수 있는 것(코드·비밀번호가 틀렸다)이라 400 으로 돌려준다.
     * 그 밖의 통신 실패는 사용자가 어쩌지 못하므로 502 다 — 여기를 400 으로 뭉뚱그리면
     * SSO 가 죽었을 때 화면이 "비밀번호가 틀렸어요" 라고 거짓말을 한다.
     *
     * <p><b>429 만은 4xx 안에서 또 가른다.</b> 잠금(시도 초과)·재발송 쿨다운은 "다시 넣어
     * 보면 되는 것" 이 아니라 <b>기다려야 풀리는 것</b>이다. 400 으로 뭉개면 화면이
     * 오답과 구별할 수 없어 "틀렸어요, 다시 넣어 주세요" 로 안내한다(QA 22차 #8).
     */
    private VerifyResp call(String path, Map<String, String> body, String userId, String what) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(ssoOAuth2Client.issueServiceToken());

        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<ApiResponse<VerifyResp>> response = ssoRestTemplate.exchange(
                    path, HttpMethod.POST, entity,
                    new ParameterizedTypeReference<ApiResponse<VerifyResp>>() {}
            );

            ApiResponse<VerifyResp> resp = response.getBody();
            if (resp != null && !resp.isSuccess()) {
                throw new InvalidValueException(DeskErrorCode.REAUTH_FAILED, resp.getMessage());
            }
            return resp != null ? resp.getData() : null;

        } catch (HttpClientErrorException e) {
            // 본문은 찍지 않는다 — 비밀번호·코드가 들어 있던 요청의 응답이다.
            log.warn("SSO 재인증 {} 실패(4xx). userId={} status={}", what, userId, e.getStatusCode());
            DeskErrorCode code = e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS
                    ? DeskErrorCode.REAUTH_LOCKED
                    : DeskErrorCode.REAUTH_FAILED;
            throw new InvalidValueException(code, extractSsoErrorMessage(e));
        } catch (RestClientException e) {
            log.error("SSO 재인증 {} 호출 실패. userId={}", what, userId, e);
            throw new ExternalServiceException(DeskErrorCode.SSO_SERVICE_ERROR, e);
        }
    }

    /**
     * 티켓이 비어 오면 실패로 본다.
     *
     * <p>{@code null} 을 그대로 돌려주면 화면은 "확인됐다" 로 읽고 다음 단계로 넘어갔다가
     * 해지 호출에서 {@code AUTH_020} 을 맞는다 — 사용자는 방금 맞게 넣은 비밀번호를
     * 의심하게 된다. 확인이 끝난 자리에서 끊는 편이 낫다.
     */
    private String ticketOf(VerifyResp resp, String userId) {
        if (resp == null || resp.reauthToken() == null || resp.reauthToken().isBlank()) {
            log.error("SSO 가 재인증 티켓 없이 성공을 돌려줬다. userId={}", userId);
            throw new ExternalServiceException(DeskErrorCode.SSO_SERVICE_ERROR);
        }
        return resp.reauthToken();
    }

    /** SSO 가 준 문장을 그대로 화면에 올린다 — 코드·비밀번호 오류 문구는 SSO 소유다. */
    private String extractSsoErrorMessage(HttpClientErrorException e) {
        try {
            String responseBody = e.getResponseBodyAsString();
            if (responseBody.contains("\"message\"")) {
                int start = responseBody.indexOf("\"message\"") + 11;
                int end = responseBody.indexOf("\"", start);
                if (end > start) {
                    return responseBody.substring(start, end);
                }
            }
        } catch (Exception ex) {
            log.debug("SSO 오류 메시지를 읽지 못했다", ex);
        }
        return "본인 확인에 실패했어요";
    }

    /** SSO {@code ReauthApiController.VerifyResp} 와 같은 모양. */
    public record VerifyResp(String reauthToken) {}
}
