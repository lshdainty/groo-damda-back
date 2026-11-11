package io.groo.damda.common.config.security;

import io.groo.core.logging.AbstractRequestResponseLoggingFilter;
import io.groo.core.logging.SensitiveDataMasker;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
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
 * </ul>
 */
@Component
public class RequestResponseLoggingFilter extends AbstractRequestResponseLoggingFilter {

    private static final SensitiveDataMasker MASKER = SensitiveDataMasker.withExtraKeys("clientId", "client_id");

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
}
