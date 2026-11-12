package io.groo.damda.common.config.security;

import io.groo.core.logging.AbstractRequestResponseLoggingFilter;
import io.groo.core.logging.SensitiveDataMasker;
import io.groo.core.security.web.RequestPaths;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 담다의 요청·응답 로그 필터 — 동작은 core {@link AbstractRequestResponseLoggingFilter} 가 갖고,
 * 여기는 담다만의 설정만 둔다(core 3.1.0 에서 세 서비스의 복사본을 하나로 합쳤다).
 *
 * <ul>
 *   <li><b>알림 SSE</b>({@code /api/v1/notifications/stream}) — 끝나지 않는 연결이라 뺀다.</li>
 *   <li><b>결제 문자 수입</b>({@code /api/v1/import/sms}) — 요청 본문이 문자 원문(개인정보)이고 응답에
 *       가맹점·금액·카드 뒤 4자리가 실린다. 마스커는 이름 붙은 키만 보므로 문자 원문은 못 가린다 —
 *       경로째 뺀다. 앞부분 일치라 {@code /parse}·{@code /commit} 하위까지 빠지고, 접근 줄은 엣지 nginx 가 남긴다.</li>
 *   <li><b>데이터 내보내기</b>({@code /api/v1/export}) — 스트리밍 다운로드라 래핑하면 빈 파일이 나간다
 *       (dev 내보내기가 0바이트 zip 이던 원인). 하위 {@code /export/counts}·{@code /export/preview} 는 일반 JSON 이라
 *       계속 로깅해야 해서 정확 일치로 뺀다.</li>
 *   <li><b>{@code clientId}/{@code client_id} 마스킹</b> — core 기본 목록은 SSO 에서 공개 식별자({@code "desk"})라 뺐지만,
 *       담다의 옛 토스 크리덴셜 요청 본문에서는 시크릿과 같은 급이라 여기서만 켠다.</li>
 *   <li><b>{@code inviteCode} 마스킹</b> — 캘린더 초대 코드는 그 자체로 참여 열쇠다(128비트, 보안 점검 DAM-4).
 *       참여 요청 본문 · 코드 발급 응답 · 캘린더 목록 응답에 실리므로 모든 경로에서 가린다.</li>
 *   <li><b>재인증 이메일 코드</b>({@value #REAUTH_EMAIL_CODE_VERIFY_PATH}) — 본문의 {@code code} 는
 *       6자리 숫자다. core 는 {@code code} 를 43자 이상일 때만 가린다(대부분이 봉투 상태코드
 *       {@code COMMON_200} 이라서) — 그래서 평문으로 남았다(보안 점검 DAM-8). 이 경로에서만
 *       {@code code} 를 무조건 가리는 마스커로 바꿔 쓴다. 다른 경로의 봉투 코드·종목 코드는 그대로 남는다.</li>
 * </ul>
 */
@Component
public class RequestResponseLoggingFilter extends AbstractRequestResponseLoggingFilter {

    private static final SensitiveDataMasker MASKER =
            SensitiveDataMasker.withExtraKeys("clientId", "client_id", "inviteCode");

    /**
     * 재인증 이메일 코드 확인 — {@code UserApiController} 의 {@code /api/v1/users} + {@code /me/reauth/email-code/verify}.
     * 종전엔 {@code /api/v1/me/...} 로 잘못 적어 실제 요청에 걸리지 않았다(보안 점검 DAM-8 잔여).
     * 테스트가 컨트롤러 매핑과 같은지 고정한다.
     */
    static final String REAUTH_EMAIL_CODE_VERIFY_PATH = "/api/v1/users/me/reauth/email-code/verify";

    /** 본문에 짧은 확인 코드가 실리는 경로 — {@code code} 를 값 모양과 무관하게 가린다. */
    private static final Set<String> SHORT_CODE_PATHS = Set.of(REAUTH_EMAIL_CODE_VERIFY_PATH);

    private static final SensitiveDataMasker SHORT_CODE_MASKER =
            SensitiveDataMasker.withExtraKeys("clientId", "client_id", "inviteCode", "code");

    /**
     * 지금 로그를 만드는 요청이 {@link #SHORT_CODE_PATHS} 인가. 마스킹 함수는 요청을 모르므로
     * 로그 한 줄을 만드는 동안만 같은 스레드에 표시해 둔다.
     */
    private static final ThreadLocal<Boolean> SHORT_CODE_REQUEST = new ThreadLocal<>();

    private static final List<String> EXCLUDED_PATH_PREFIXES = Stream.concat(
            DEFAULT_EXCLUDED_PATH_PREFIXES.stream(),
            Stream.of("/api/v1/notifications/stream", "/api/v1/import/sms")
    ).toList();

    private static final List<String> EXCLUDED_STREAMING_PATHS = List.of("/api/v1/export");

    public RequestResponseLoggingFilter() {
        super(MASKER);
    }

    @Override
    protected Collection<String> excludedPathPrefixes() {
        return EXCLUDED_PATH_PREFIXES;
    }

    @Override
    protected Collection<String> excludedExactPaths() {
        return EXCLUDED_STREAMING_PATHS;
    }

    @Override
    protected void logRequestResponse(ContentCachingRequestWrapper request,
                                      ContentCachingResponseWrapper response,
                                      String traceId,
                                      long executionTime) {
        // 원문(getRequestURI) 대신 디코딩·정규화한 경로로 본다 — %2D · ;x · // 로 바꾼 요청도 같은 컨트롤러에 닿는다.
        if (!SHORT_CODE_PATHS.contains(RequestPaths.normalized(request))) {
            super.logRequestResponse(request, response, traceId, executionTime);
            return;
        }
        SHORT_CODE_REQUEST.set(Boolean.TRUE);
        try {
            super.logRequestResponse(request, response, traceId, executionTime);
        } finally {
            SHORT_CODE_REQUEST.remove();
        }
    }

    @Override
    public String maskSensitiveData(String text) {
        if (Boolean.TRUE.equals(SHORT_CODE_REQUEST.get())) {
            return SHORT_CODE_MASKER.apply(text);
        }
        return super.maskSensitiveData(text);
    }
}
