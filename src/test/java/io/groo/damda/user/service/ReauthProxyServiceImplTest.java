package io.groo.damda.user.service;

import io.groo.damda.user.type.ReauthPurpose;
import io.groo.core.controller.ApiResponse;
import io.groo.core.exception.ExternalServiceException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.security.client.SsoOAuth2Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 재인증 프록시 — desk-back 이 서비스 토큰으로 SSO 를 대신 부르는 자리.
 *
 * <p>여기서 잠그는 것은 셋이다.
 * <ol>
 *   <li><b>대상과 용도를 서버가 박는다</b> — body 의 {@code userId} 는 로그인한 본인이고
 *       {@code purpose} 는 {@code withdraw} 다. 클라이언트가 고를 수 없다</li>
 *   <li><b>4xx 와 통신 실패를 가른다</b> — 전자는 400(사용자가 고칠 수 있다),
 *       후자는 502. 뭉뚱그리면 SSO 가 죽었을 때 "비밀번호가 틀렸어요" 라고 거짓말한다.
 *       4xx 안에서도 <b>429 는 따로</b> 넘긴다 — 잠금·쿨다운은 다시 넣어서 되는 게
 *       아니라 기다려야 풀리는 것이다(QA 22차 #8)</li>
 *   <li><b>티켓 없는 성공은 실패다</b> — 통과시키면 다음 호출에서 AUTH_020 을 맞고
 *       사용자는 방금 맞게 넣은 비밀번호를 의심한다</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("재인증 프록시")
class ReauthProxyServiceImplTest {

    @Mock private SsoOAuth2Client ssoOAuth2Client;
    @Mock private RestTemplate ssoRestTemplate;

    @InjectMocks private ReauthProxyServiceImpl sut;

    private static final String USER_ID = "u1";
    private static final String EMAIL_CODE_PATH = "/api/v1/auth/reauth/email-code";
    private static final String VERIFY_PATH = "/api/v1/auth/reauth/email-code/verify";
    private static final String PASSWORD_PATH = "/api/v1/auth/reauth/password";

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<HttpEntity<Map<String, String>>> entityCaptor() {
        return ArgumentCaptor.forClass(HttpEntity.class);
    }

    @SuppressWarnings("unchecked")
    private void givenSsoReturns(String path, ApiResponse<ReauthProxyServiceImpl.VerifyResp> body) {
        given(ssoOAuth2Client.issueServiceToken()).willReturn("svc-token");
        given(ssoRestTemplate.exchange(eq(path), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .willReturn(ResponseEntity.ok(body));
    }

    @SuppressWarnings("unchecked")
    private void givenSsoThrows(String path, RuntimeException e) {
        given(ssoOAuth2Client.issueServiceToken()).willReturn("svc-token");
        given(ssoRestTemplate.exchange(eq(path), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .willThrow(e);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> capturedBody(String path) {
        ArgumentCaptor<HttpEntity<Map<String, String>>> captor = entityCaptor();
        org.mockito.Mockito.verify(ssoRestTemplate).exchange(eq(path), eq(HttpMethod.POST),
                captor.capture(), any(ParameterizedTypeReference.class));
        return captor.getValue().getBody();
    }

    @SuppressWarnings("unchecked")
    private HttpHeaders capturedHeaders(String path) {
        ArgumentCaptor<HttpEntity<Map<String, String>>> captor = entityCaptor();
        org.mockito.Mockito.verify(ssoRestTemplate).exchange(eq(path), eq(HttpMethod.POST),
                captor.capture(), any(ParameterizedTypeReference.class));
        return captor.getValue().getHeaders();
    }

    private static HttpClientErrorException withStatus(HttpStatus status, String message) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(),
                HttpHeaders.EMPTY,
                ("{\"success\":false,\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    private static HttpClientErrorException badRequest(String message) {
        return HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                HttpHeaders.EMPTY,
                ("{\"success\":false,\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("대상·용도는 서버가 박는다")
    class TargetAndPurpose {

        @Test
        @DisplayName("코드 발송: 서비스 토큰 Bearer 와 대상 userId 를 실어 보낸다")
        void sendEmailCode() {
            givenSsoReturns(EMAIL_CODE_PATH, ApiResponse.success(null));

            sut.sendEmailCode(USER_ID);

            assertThat(capturedBody(EMAIL_CODE_PATH)).containsEntry("userId", USER_ID);
            assertThat(capturedHeaders(EMAIL_CODE_PATH).getFirst("Authorization"))
                    .isEqualTo("Bearer svc-token");
        }

        @Test
        @DisplayName("코드 확인: purpose 는 닫힌 목록의 wire 값 그대로 — 해지는 withdraw")
        void verifyEmailCode_fixesPurpose() {
            givenSsoReturns(VERIFY_PATH,
                    ApiResponse.success(new ReauthProxyServiceImpl.VerifyResp("ticket")));

            assertThat(sut.verifyEmailCode(USER_ID, "123456", ReauthPurpose.WITHDRAW)).isEqualTo("ticket");
            assertThat(capturedBody(VERIFY_PATH))
                    .containsEntry("userId", USER_ID)
                    .containsEntry("code", "123456")
                    .containsEntry("purpose", "withdraw");
        }

        @Test
        @DisplayName("소셜 연동용(보안 점검 S-2): purpose=oauth-link 로 받아 온다 — SSO 연동 시작이 이 값을 본다")
        void verifyPassword_oauthLink() {
            givenSsoReturns(PASSWORD_PATH,
                    ApiResponse.success(new ReauthProxyServiceImpl.VerifyResp("link-ticket")));

            assertThat(sut.verifyPassword(USER_ID, "pw", ReauthPurpose.OAUTH_LINK)).isEqualTo("link-ticket");
            assertThat(capturedBody(PASSWORD_PATH)).containsEntry("purpose", "oauth-link");
        }

        @Test
        @DisplayName("비밀번호 확인: 같은 규칙으로 티켓을 받아 온다")
        void verifyPassword() {
            givenSsoReturns(PASSWORD_PATH,
                    ApiResponse.success(new ReauthProxyServiceImpl.VerifyResp("ticket")));

            assertThat(sut.verifyPassword(USER_ID, "pw", ReauthPurpose.WITHDRAW)).isEqualTo("ticket");
            assertThat(capturedBody(PASSWORD_PATH))
                    .containsEntry("userId", USER_ID)
                    .containsEntry("password", "pw")
                    .containsEntry("purpose", "withdraw");
        }
    }

    @Nested
    @DisplayName("실패를 가른다")
    class Failures {

        @Test
        @DisplayName("4xx 는 400 이고 SSO 문장을 그대로 올린다 — 코드 오류 문구는 SSO 소유다")
        void clientError_becomes400() {
            givenSsoThrows(VERIFY_PATH, badRequest("인증 코드가 올바르지 않아요"));

            assertThatThrownBy(() -> sut.verifyEmailCode(USER_ID, "000000", ReauthPurpose.WITHDRAW))
                    .isInstanceOf(InvalidValueException.class)
                    .hasMessageContaining("인증 코드가 올바르지 않아요");
        }

        /**
         * 잠금은 <b>오답과 다른 상태</b>로 나간다.
         *
         * <p>둘 다 400 이면 화면은 구별할 수가 없어 "틀렸어요, 다시 넣어 주세요" 로
         * 안내한다. 그런데 잠긴 동안은 맞게 넣어도 안 된다 — 사용자는 맞는 비밀번호를
         * 계속 넣으며 자기를 의심한다(QA 22차 #8).
         */
        @Test
        @DisplayName("429 는 429 로 넘긴다 — 오답과 같은 400 이면 '다시 넣어 주세요' 가 된다")
        void tooManyRequests_staysTooManyRequests() {
            givenSsoThrows(PASSWORD_PATH, withStatus(HttpStatus.TOO_MANY_REQUESTS,
                    "본인 확인을 여러 번 틀렸어요. 10분 후 다시 시도해 주세요"));

            InvalidValueException thrown = catchThrowableOfType(
                    () -> sut.verifyPassword(USER_ID, "pw", ReauthPurpose.WITHDRAW), InvalidValueException.class);

            assertThat(thrown).hasMessageContaining("10분 후");
            assertThat(thrown.getErrorCode()).isEqualTo(DeskErrorCode.REAUTH_LOCKED);
            assertThat(DeskErrorCode.REAUTH_LOCKED.getHttpStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            // 오답과 코드가 겹치면 클라이언트가 가를 수 없다.
            assertThat(DeskErrorCode.REAUTH_LOCKED.getCode())
                    .isNotEqualTo(DeskErrorCode.REAUTH_FAILED.getCode());
        }

        /** 코드 재발송 쿨다운(SSO AUTH_034)도 같은 경로를 탄다. */
        @Test
        @DisplayName("재발송 쿨다운도 429 로 넘어간다")
        void cooldown_staysTooManyRequests() {
            givenSsoThrows(EMAIL_CODE_PATH, withStatus(HttpStatus.TOO_MANY_REQUESTS,
                    "코드를 방금 보냈어요. 잠시 뒤에 다시 받아 주세요"));

            InvalidValueException thrown = catchThrowableOfType(
                    () -> sut.sendEmailCode(USER_ID), InvalidValueException.class);

            assertThat(thrown.getErrorCode()).isEqualTo(DeskErrorCode.REAUTH_LOCKED);
        }

        /** 400 은 그대로 400 이다 — 429 를 가른 뒤에도 오답 경로가 살아 있어야 한다. */
        @Test
        @DisplayName("오답은 여전히 400 이다")
        void badRequest_staysBadRequest() {
            givenSsoThrows(PASSWORD_PATH, badRequest("비밀번호가 올바르지 않아요"));

            InvalidValueException thrown = catchThrowableOfType(
                    () -> sut.verifyPassword(USER_ID, "pw", ReauthPurpose.WITHDRAW), InvalidValueException.class);

            assertThat(thrown.getErrorCode()).isEqualTo(DeskErrorCode.REAUTH_FAILED);
        }

        @Test
        @DisplayName("통신 실패는 502 — 400 으로 뭉뚱그리면 화면이 비밀번호 탓을 한다")
        void networkError_becomes502() {
            givenSsoThrows(PASSWORD_PATH, new ResourceAccessException("connect timed out"));

            assertThatThrownBy(() -> sut.verifyPassword(USER_ID, "pw", ReauthPurpose.WITHDRAW))
                    .isInstanceOf(ExternalServiceException.class);
        }

        @Test
        @DisplayName("success=false 본문도 400 이다")
        void unsuccessfulBody_becomes400() {
            givenSsoReturns(PASSWORD_PATH, ApiResponse.error("FAIL", "비밀번호가 올바르지 않아요"));

            assertThatThrownBy(() -> sut.verifyPassword(USER_ID, "pw", ReauthPurpose.WITHDRAW))
                    .isInstanceOf(InvalidValueException.class);
        }

        @Test
        @DisplayName("티켓 없는 성공은 502 — 화면을 다음 단계로 넘기면 안 된다")
        void successWithoutTicket_becomes502() {
            givenSsoReturns(PASSWORD_PATH,
                    ApiResponse.success(new ReauthProxyServiceImpl.VerifyResp("  ")));

            assertThatThrownBy(() -> sut.verifyPassword(USER_ID, "pw", ReauthPurpose.WITHDRAW))
                    .isInstanceOf(ExternalServiceException.class);
        }
    }
}
