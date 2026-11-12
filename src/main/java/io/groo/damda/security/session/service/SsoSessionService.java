package io.groo.damda.security.session.service;

import io.groo.core.type.YNType;
import io.groo.damda.common.config.properties.JwtProperties;
import io.groo.damda.common.crypto.AesGcmCipher;
import io.groo.damda.security.client.SsoOAuth2Client;
import io.groo.damda.security.session.domain.UserSsoSession;
import io.groo.damda.security.session.repository.UserSsoSessionRepository;
import io.groo.damda.security.session.store.SessionRevocationStore;
import io.groo.damda.security.session.support.UserAgentParser;
import io.groo.damda.security.session.controller.dto.SessionApiDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

/**
 * 기기별 SSO 세션 관리 — 로그인 때 refresh token 을 받아 두었다가, desk access token 이
 * 만료되면 사용자 개입 없이 SSO 에 재발급을 요청한다(OAuth BFF).
 *
 * <p>refresh token 은 AES-GCM 암호문으로만 둔다. 해시가 아닌 이유는 SSO 로 재발급을 요청할 때
 * 원본이 필요해서다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SsoSessionService {

    /**
     * 앞선 요청이 갱신한 직후로 보는 시간(초).
     *
     * <p>토큰이 만료된 순간 여러 요청이 한꺼번에 들어온다. 락으로 줄을 세워도, 뒤따라온 요청이
     * SSO 를 또 부르면 rotation 때문에 방금 받은 토큰이 폐기된다. 이 시간 안에 갱신된 세션은
     * 다시 부르지 않고 그대로 쓴다. 요청 하나가 도는 시간(SSO read timeout 10초)보다 넉넉해야
     * 뒤따라온 요청이 창을 놓치지 않는다.
     */
    private static final long REFRESH_GRACE_SECONDS = 30L;


    private final UserSsoSessionRepository sessionRepository;
    private final SsoOAuth2Client ssoOAuth2Client;
    private final AesGcmCipher cipher;
    private final JwtProperties jwtProperties;
    private final SessionRevocationStore revocationStore;

    /**
     * 재발급 결과.
     *
     * @param renewed        세션이 살아 있어 desk 토큰을 새로 내줘도 되는지
     * @param ssoAccessToken SSO 를 실제로 불러 받아온 새 access token. 앞선 요청이 방금 갱신해
     *                       둔 경우에는 {@code null} — 세션은 멀쩡하니 desk 토큰만 새로 만들면 된다.
     */
    public record RefreshResult(boolean renewed, String ssoAccessToken) {
        static RefreshResult failed() { return new RefreshResult(false, null); }
        static RefreshResult reusedExisting() { return new RefreshResult(true, null); }
        static RefreshResult fromSso(String ssoAccessToken) { return new RefreshResult(true, ssoAccessToken); }
    }

    /**
     * 로그인 직후 세션 생성.
     *
     * <p>세션을 못 만들 사정이면 만들지 않고 넘어간다 — 무음 재인증만 안 되고 로그인은 된다.
     * 여기서 예외를 던지면 로그인 트랜잭션이 통째로 깨져, 부가 기능 하나 때문에 아무도
     * 못 들어오게 된다.
     */
    @Transactional
    public void create(Long userRowId, String sessionId, String refreshToken, String userAgent) {
        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("SSO 가 refresh token 을 주지 않아 세션을 만들지 않는다. userRowId={}", userRowId);
            return;
        }
        if (!cipher.isConfigured()) {
            // 암호화 키 없이 평문으로 저장하는 선택지는 없다. 키를 채우면 그때부터 동작한다.
            log.error("app.security.encryption-key 미설정 — SSO 세션을 만들지 않는다."
                    + " 무음 재인증이 동작하지 않아 access token 만료마다 재로그인하게 된다. userRowId={}", userRowId);
            return;
        }
        sessionRepository.save(UserSsoSession.issue(
                userRowId, sessionId, cipher.encrypt(refreshToken), sessionExpiry(now()), deviceLabel(userAgent)));
    }

    /**
     * 만료된 세션 재발급.
     *
     * <p>실패를 두 갈래로 나눈다. 뭉뚱그리면 SSO 가 잠깐 죽었을 때 전체 사용자가 로그아웃된다.
     * <ul>
     *   <li>SSO 가 거부(4xx) → 세션을 끊는다. 다시 시도해도 결과가 같으니 사용자에게 로그인을 시킨다.</li>
     *   <li>SSO 가 응답 못 함(5xx·타임아웃) → 세션은 그대로 둔다. 이번 요청만 401 이고 다음에 다시 시도된다.</li>
     * </ul>
     */
    @Transactional
    public RefreshResult refresh(String sessionId) {
        UserSsoSession session = sessionRepository.findForRefresh(sessionId, YNType.N).orElse(null);
        if (session == null) {
            return RefreshResult.failed();
        }

        LocalDateTime now = now();
        if (session.isExpired(now)) {
            // 어차피 SSO 가 거부한다. 부르지 않고 여기서 끊는다.
            log.info("SSO 세션 만료 — 재로그인 필요. sessionId={}", sessionId);
            kill(session);
            return RefreshResult.failed();
        }

        // 절대 수명 — 로그인부터 30일. 미끄럼 수명과 달리 쓰는 동안에도 늘어나지 않는다(보안 점검 DAM-5).
        // SSO 를 부르기 전에 끊는다 — 앱의 무음 재인증(refresh grant)도 이 자리를 지나므로 우회되지 않는다.
        if (session.isPastAbsoluteLifetime(now, jwtProperties.getAbsoluteSessionLifetime() / 1000)) {
            log.info("세션 절대 수명 경과 — 재로그인 필요. sessionId={}", sessionId);
            kill(session);
            return RefreshResult.failed();
        }

        // 락을 잡고 보니 앞선 요청이 이미 갱신해 뒀다. 또 부르면 방금 받은 토큰이 폐기된다.
        if (session.refreshedWithin(now, REFRESH_GRACE_SECONDS)) {
            return RefreshResult.reusedExisting();
        }

        SsoOAuth2Client.TokenPair pair;
        try {
            pair = ssoOAuth2Client.refreshTokens(cipher.decrypt(session.getRefreshTokenEnc()));
        } catch (Exception e) {
            // 일시적 장애 — 세션을 끊지 않는다. 여기서 끊으면 SSO 재시작 한 번에 전원이 로그아웃된다.
            log.error("SSO 재발급 호출 실패(일시적으로 본다). sessionId={}, err={}", sessionId, e.getMessage());
            return RefreshResult.failed();
        }

        if (pair == null) {
            // SSO 가 명시적으로 거부했다 — 만료·폐기·권한 회수. 확정이므로 세션을 끊는다.
            log.info("SSO 가 재발급을 거부 — 세션 폐기. sessionId={}", sessionId);
            kill(session);
            return RefreshResult.failed();
        }

        if (!pair.hasRefreshToken()) {
            // rotation 이 오지 않았다. 옛 토큰은 이미 폐기됐을 수 있어 다음 재발급을 보장할 수 없다.
            log.error("SSO 재발급 응답에 refresh token 이 없다 — 세션 폐기. sessionId={}", sessionId);
            kill(session);
            return RefreshResult.failed();
        }

        session.rotate(cipher.encrypt(pair.refreshToken()), sessionExpiry(now), now);
        return RefreshResult.fromSso(pair.accessToken());
    }

    /**
     * 로그아웃 — 이 기기 세션만 끊는다. 다른 기기는 살아 있어야 한다. 이 세션을 만든 SSO 로그인
     * (앱이면 시스템 브라우저에 남은 SSO 쿠키 포함)도 SSO 에서 함께 끊는다({@link #revokeSsoLogin}).
     *
     * <p>세션 행을 못 찾아도 폐기 표식은 남긴다. 로그아웃은 사용자가 명시적으로 누른 것이고,
     * 지금 손에 든 access token 은 어떤 경우에도 죽어야 한다. 행이 없을 수 있는 이유는
     * 실제로 있다 — {@code APP_ENCRYPTION_KEY} 가 비면 {@link #create} 가 세션을 저장하지
     * 않고 조용히 넘어간다. 거기서 표식까지 건너뛰면 그 환경에서는 로그아웃이 통째로
     * 무력해진다.
     */
    @Transactional
    public void revoke(String sessionId) {
        sessionRepository.findBySessionIdAndIsDeleted(sessionId, YNType.N)
                .ifPresent(session -> {
                    session.revoke();
                    revokeSsoLogin(session);
                });
        markRevoked(sessionId);
    }

    /**
     * 이 세션을 만든 SSO 로그인의 토큰 계열을 SSO 에서 끊는다(보안 점검 CLI-2).
     *
     * <p>앱은 시스템 브라우저로 로그인해서, 앱에서 로그아웃해도 브라우저에 SSO refresh 쿠키(7일)가 남았다.
     * 잠금이 풀린 기기를 쥔 사람이 브라우저로 담다 웹을 열면 비밀번호 없이 들어간다. 손에 든 refresh 로
     * SSO 에 폐기를 요청하면 같은 로그인의 브라우저 쿠키도 함께 끊긴다. 다른 기기 · 다른 로그인은 그대로다.
     *
     * <p>실패해도 로그아웃은 진행한다 — 담다 세션은 이미 끊었고, SSO 가 잠깐 죽었다고 로그아웃이
     * 에러가 되면 안 된다. 로그만 남긴다.
     */
    private void revokeSsoLogin(UserSsoSession session) {
        try {
            if (!ssoOAuth2Client.revokeRefreshToken(cipher.decrypt(session.getRefreshTokenEnc()))) {
                log.warn("SSO 로그인 계열 폐기 실패 — 담다 세션만 끊었다. sessionId={}", session.getSessionId());
            }
        } catch (Exception e) {
            log.error("SSO 로그인 계열 폐기 중 오류 — 담다 세션만 끊었다. sessionId={}, err={}",
                    session.getSessionId(), e.getMessage());
        }
    }

    /**
     * "로그인된 기기" 목록.
     *
     * <p>만료된 세션은 뺀다 — 이미 못 쓰는 기기를 목록에 두면 사용자가 "왜 로그아웃이 안 되지"
     * 하고 헤맨다. 최근에 쓴 순으로 준다. 한 번도 재발급 안 한 세션은 lastUsedAt 이 null 이라
     * 로그인 시각으로 대신 정렬한다.
     */
    @Transactional(readOnly = true)
    public List<SessionApiDto.DeviceRes> listDevices(Long userRowId, String currentSessionId) {
        LocalDateTime now = now();
        return sessionRepository.findAllByUserRowIdAndIsDeleted(userRowId, YNType.N).stream()
                .filter(s -> !s.isExpired(now))
                .sorted(Comparator.comparing(
                        (UserSsoSession s) -> s.getLastUsedAt() != null ? s.getLastUsedAt() : s.getCreateAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(s -> new SessionApiDto.DeviceRes(
                        s.getSessionId(),
                        s.getDeviceLabel(),
                        UserAgentParser.kindOf(s.getDeviceLabel()),
                        s.getLastUsedAt(),
                        s.getCreateAt(),
                        s.getSessionId().equals(currentSessionId)))
                .toList();
    }

    /**
     * 기기 하나 로그아웃 — <b>본인 세션인지 확인하고</b> 끊는다.
     *
     * <p>세션 id 만 받고 끊으면 남의 기기도 끊을 수 있다. id 를 알아내기 어렵다는 건 방어가
     * 아니다. 소유자를 대조한다.
     *
     * @return 실제로 끊었으면 true. 없거나 남의 것이면 false — 어느 쪽인지 구분해 주지 않는다
     *         (있는데 남의 것이라는 사실 자체가 정보다)
     */
    @Transactional
    public boolean revokeOwned(Long userRowId, String sessionId) {
        return sessionRepository.findBySessionIdAndIsDeleted(sessionId, YNType.N)
                .filter(s -> s.getUserRowId().equals(userRowId))
                .map(s -> {
                    // 소유자 확인을 통과한 뒤에만 끊는다. 확인 전에 표식을 남기면 세션 id 만
                    // 아는 사람이 남의 기기를 끊을 수 있다.
                    kill(s);
                    // 그 기기의 브라우저에 남은 SSO 로그인도 함께 끊는다 — 잃어버린 폰의 앱을 다른 기기에서
                    // 로그아웃시켜도 그 폰의 브라우저로 다시 들어오지 못하게(보안 점검 CLI-2)
                    revokeSsoLogin(s);
                    return true;
                })
                .orElse(false);
    }

    /**
     * "모든 기기에서 로그아웃" — 이 계정의 살아 있는 세션을 전부 끊는다. 지금 요청을 보낸
     * 기기도 예외 없이 끊는다. "계정이 이상하다" 싶을 때 쓰는 비상 버튼이라 현재 기기를
     * 예외로 두지 않는 편이 사용자 기대에 맞는다.
     *
     * <p>지금은 desk 세션만 끊는다. SSO·hr 은 아직 안 끊긴다 — SSO 가 로그아웃 이벤트를
     * 내려 전 서비스가 함께 끊기는 건 다음 단계에서 잇는다.
     */
    @Transactional
    public void revokeAll(Long userRowId) {
        sessionRepository.findAllByUserRowIdAndIsDeleted(userRowId, YNType.N).forEach(this::kill);
    }

    /**
     * 세션을 끊는다 — 행을 지우고 폐기 표식을 남긴다. <b>둘은 항상 같이 간다.</b>
     *
     * <p>행만 지우면 이미 발급된 access token 에는 아무 영향이 없다. 무상태 JWT 라 서명과 exp
     * 만으로 계속 통과하고, {@code JwtAuthenticationFilter} 의 갱신 경로가 세션을 보지 않고
     * 재서명하므로 만료 전에 한 번만 쓰면 수명이 무기한 늘어난다. 표식이 그걸 막는 유일한
     * 수단이라 폐기 경로를 이 한 자리로 모았다 — 경로가 늘어날 때 빠뜨리기 어렵게.
     *
     * <p>{@link #refresh} 안의 사망 경로들도 여기를 지난다. 그쪽은 access token 이 이미 만료된
     * 상태에서만 도달하지만, <b>같은 세션의 다른 사본</b>은 갱신을 받아 아직 살아 있을 수 있다
     * (갱신은 같은 jti 로 재서명한다). SSO 가 권한을 회수했는데 그 사본만 한 시간 더 도는 일을
     * 막는다.
     */
    private void kill(UserSsoSession session) {
        session.revoke();
        markRevoked(session.getSessionId());
    }

    /**
     * 폐기 표식을 남긴다.
     *
     * <p>수명은 access token 수명과 같다. 그 시각 뒤의 토큰은 서명 검증에서 만료로 떨어지므로
     * 표식을 더 들고 있을 이유가 없다.
     */
    private void markRevoked(String sessionId) {
        revocationStore.revoke(sessionId, jwtProperties.getAccessTokenExpiration() / 1000);
    }

    /**
     * 해지 전용 — 세션 <b>행만</b> 끊고 표식 대상을 돌려준다.
     *
     * <p>{@link #revokeAll} 과 가르는 이유는 <b>두 부분이 서로 다른 시점에 일어나야</b>
     * 하기 때문이다. 해지는 한 트랜잭션이고, 되돌릴 수 없는 바깥 호출(Redis 표식)은 커밋
     * 뒤에 해야 한다 — 그런데 커밋 뒤에는 영속성 컨텍스트가 닫혀 있어 {@code revoke()}
     * 의 더티 체킹이 <b>DB 에 닿지 않는다.</b> 실제로 Redis 표식만 남고 행은 활성으로
     * 남아 있었다(2026-09-17 QA 실측: user_sso_session 4행 활성 유지).
     *
     * <p>그래서 나눈다 — <b>행은 여기서(트랜잭션 안), 표식은 돌려받은 목록으로 커밋 뒤에.</b>
     * 부르는 쪽이 {@link #markRevokedAll} 을 반드시 이어 불러야 한다. 표식 없이 행만 끊으면
     * 이미 발급된 access token 은 그대로 통과한다({@link #kill} 설명 참고).
     *
     * @return 표식을 남겨야 할 세션 ID 들
     */
    @Transactional
    public List<String> revokeAllRows(Long userRowId) {
        List<UserSsoSession> alive =
                sessionRepository.findAllByUserRowIdAndIsDeleted(userRowId, YNType.N);
        alive.forEach(UserSsoSession::revoke);
        return alive.stream().map(UserSsoSession::getSessionId).toList();
    }

    /** {@link #revokeAllRows} 가 돌려준 목록에 폐기 표식을 남긴다 — 커밋 뒤에 부른다. */
    public void markRevokedAll(List<String> sessionIds) {
        sessionIds.forEach(this::markRevoked);
    }

    /**
     * 새 refresh 를 받을 때마다 만료를 미룬다(sliding).
     *
     * <p>SSO 가 rotation 으로 매번 수명을 새로 주므로, 계속 쓰는 사용자는 로그아웃되지 않는다.
     * 반대로 7일 넘게 안 들어오면 세션이 끝난다.
     */
    private LocalDateTime sessionExpiry(LocalDateTime now) {
        return now.plusSeconds(jwtProperties.getSessionExpiration() / 1000);
    }

    /** [UTC] 저장·비교는 UTC — 표시할 때만 사용자 타임존으로 바꾼다. */
    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * "로그인된 기기" 목록에 쓸 이름.
     *
     * <p>예전에는 UA 원문을 잘라 담았다. 사용자에게
     * {@code Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) ...} 를 보여주는 셈이라
     * 내 기기인지 알아볼 수가 없었다. 이제 {@code iPhone · Safari} 로 줄인다.
     *
     * <p>못 알아본 UA 는 {@code null} — 화면이 "알 수 없는 기기" 로 표시한다.
     */
    private String deviceLabel(String userAgent) {
        return UserAgentParser.parse(userAgent);
    }
}
