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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 기기별 SSO 세션 관리 — 로그인 때 refresh token 을 받아 두었다가, desk access token 이
 * 만료되면 사용자 개입 없이 SSO 에 재발급을 요청한다(OAuth BFF).
 *
 * <p>refresh token 은 AES-GCM 암호문으로만 둔다. 해시가 아닌 이유는 SSO 로 재발급을 요청할 때
 * 원본이 필요해서다.
 */
@Slf4j
@Service
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

    /**
     * 같은 세션의 앞선 재발급을 기다리는 최대 시간(초). 앞선 요청의 SSO 호출 한 번
     * (연결 5초 + 읽기 10초)보다 길어야 뒤따라온 요청이 그 결과를 받아 간다. 넘기면 이번 요청만
     * 재발급 없이 흘려보낸다(종전 행 잠금 대기가 DB 잠금 타임아웃으로 끝나던 것과 같은 결말).
     */
    private static final long REFRESH_QUEUE_TIMEOUT_SECONDS = 20L;

    private final UserSsoSessionRepository sessionRepository;
    private final SsoOAuth2Client ssoOAuth2Client;
    private final AesGcmCipher cipher;
    private final JwtProperties jwtProperties;
    private final SessionRevocationStore revocationStore;

    /** 재발급의 판정·반영 구간만 짧게 감싼다. SSO 호출은 이 밖에서 한다. */
    private final TransactionTemplate refreshTx;

    /**
     * 같은 세션의 재발급을 한 줄로 세운다 — SSO 호출 동안 DB 행 잠금 대신 이걸 쥔다.
     *
     * <p>종전엔 행 잠금(PESSIMISTIC_WRITE)을 쥔 채 SSO 를 불러, SSO 가 느리면 요청마다 커넥션과
     * 행 잠금이 최대 10초씩 묶였다. 같은 세션의 동시 요청들도 잠금을 기다리며 커넥션을 하나씩
     * 쥐었다(풀 기본 10). 이제 줄은 프로세스 안에서 서고, 기다리는 동안 커넥션을 쥐지 않는다.
     *
     * <p>프로세스 안 잠금이라 <b>인스턴스가 하나</b>라는 전제다(Jenkins 가 환경마다 컨테이너 하나를
     * 띄운다). 여럿으로 늘리면 인스턴스 사이의 줄은 반영 단계의 행 잠금 재확인만 남는다 —
     * 그때는 공유 잠금(Redis 등)으로 바꿔야 한다.
     */
    private final KeyedMutex refreshMutex = new KeyedMutex();

    public SsoSessionService(UserSsoSessionRepository sessionRepository,
                             SsoOAuth2Client ssoOAuth2Client,
                             AesGcmCipher cipher,
                             JwtProperties jwtProperties,
                             SessionRevocationStore revocationStore,
                             PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.ssoOAuth2Client = ssoOAuth2Client;
        this.cipher = cipher;
        this.jwtProperties = jwtProperties;
        this.revocationStore = revocationStore;
        this.refreshTx = new TransactionTemplate(transactionManager);
    }

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
    public RefreshResult refresh(String sessionId) {
        return refresh(sessionId, ssoAccessToken -> { });
    }

    /**
     * 만료된 세션 재발급 — SSO 를 부르는 동안에는 <b>트랜잭션도 커넥션도 쥐지 않는다</b>(보안 점검 DAM-5 잔여).
     *
     * <p>세 단계로 나눈다.
     * <ol>
     *   <li><b>판정</b>(짧은 트랜잭션) — 세션이 살아 있는지, 미끄럼·절대 수명, 방금 갱신됐는지 보고
     *       부를 refresh token 을 읽어 둔다.</li>
     *   <li><b>SSO 호출</b>(트랜잭션 밖) — 느려도 DB 는 아무것도 묶이지 않는다.</li>
     *   <li><b>반영</b>(짧은 트랜잭션, 행 잠금) — 행을 다시 읽어 그 사이 끊겼거나(로그아웃) 다른 곳에서
     *       이미 갈아끼웠으면 손대지 않는다. 아니면 회전하고 {@code beforeCommit} 을 부른다.</li>
     * </ol>
     * 같은 세션의 동시 요청은 세 단계 전체를 {@link #refreshMutex} 로 한 줄에 세운다. 줄의 둘째부터는
     * 판정 단계에서 "방금 갱신됨" 을 보고 SSO 를 부르지 않는다 — rotation 으로 앞선 요청이 방금 받은
     * 토큰이 폐기되는 경합이 그대로 막힌다.
     *
     * <p><b>트랜잭션 안에서 부르지 마라.</b> 바깥 트랜잭션이 있으면 단계들이 거기 합류해 SSO 호출 동안
     * 커넥션을 다시 쥐게 된다. {@code SsoSessionRefreshConnectionTest} 가 필터 경로로 이 자리를 지킨다.
     *
     * @param beforeCommit SSO 가 새 토큰을 줬을 때, 회전을 커밋하기 직전 <b>같은 트랜잭션 안에서</b> 새
     *                     SSO access token 으로 부른다. 던지면 회전이 함께 롤백된다 — desk 토큰 발급
     *                     검증(권한·사용자)이 실패했는데 세션만 갱신된 채 남지 않게.
     */
    public RefreshResult refresh(String sessionId, Consumer<String> beforeCommit) {
        boolean locked;
        try {
            locked = refreshMutex.tryLock(sessionId, REFRESH_QUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RefreshResult.failed();
        }
        if (!locked) {
            log.warn("재발급 대기 시간 초과 — 앞선 요청의 SSO 호출이 끝나지 않았다. sessionId={}", sessionId);
            return RefreshResult.failed();
        }
        try {
            return refreshInLine(sessionId, beforeCommit);
        } finally {
            refreshMutex.unlock(sessionId);
        }
    }

    /** 이 세션의 재발급 줄에 걸린(진행 중이거나 기다리는) 요청 수 — 테스트용. */
    int refreshQueueLength(String sessionId) {
        return refreshMutex.queueLength(sessionId);
    }

    /** 판정 단계의 결과 — 결론이 났거나({@code decided}), SSO 를 부를 refresh token 암호문. */
    private record Inspection(RefreshResult decided, String refreshTokenEnc) {
        static Inspection decided(RefreshResult result) { return new Inspection(result, null); }
        static Inspection callSso(String refreshTokenEnc) { return new Inspection(null, refreshTokenEnc); }
    }

    private RefreshResult refreshInLine(String sessionId, Consumer<String> beforeCommit) {
        Inspection inspection = refreshTx.execute(status -> inspect(sessionId));
        if (inspection.decided() != null) {
            return inspection.decided();
        }

        // 트랜잭션 밖 — SSO 가 느려도 커넥션·행 잠금을 쥐고 있지 않다.
        SsoOAuth2Client.TokenPair pair;
        try {
            pair = ssoOAuth2Client.refreshTokens(cipher.decrypt(inspection.refreshTokenEnc()));
        } catch (Exception e) {
            // 일시적 장애 — 세션을 끊지 않는다. 여기서 끊으면 SSO 재시작 한 번에 전원이 로그아웃된다.
            log.error("SSO 재발급 호출 실패(일시적으로 본다). sessionId={}, err={}", sessionId, e.getMessage());
            return RefreshResult.failed();
        }

        return refreshTx.execute(status -> apply(sessionId, inspection.refreshTokenEnc(), pair, beforeCommit));
    }

    /** 판정 — SSO 를 부를지, 부르지 않고 끝낼지. */
    private Inspection inspect(String sessionId) {
        UserSsoSession session = sessionRepository.findForRefresh(sessionId, YNType.N).orElse(null);
        if (session == null) {
            return Inspection.decided(RefreshResult.failed());
        }

        LocalDateTime now = now();
        if (session.isExpired(now)) {
            // 어차피 SSO 가 거부한다. 부르지 않고 여기서 끊는다.
            log.info("SSO 세션 만료 — 재로그인 필요. sessionId={}", sessionId);
            kill(session);
            return Inspection.decided(RefreshResult.failed());
        }

        // 절대 수명 — 로그인부터 30일. 미끄럼 수명과 달리 쓰는 동안에도 늘어나지 않는다(보안 점검 DAM-5).
        // SSO 를 부르기 전에 끊는다 — 앱의 무음 재인증(refresh grant)도 이 자리를 지나므로 우회되지 않는다.
        if (session.isPastAbsoluteLifetime(now, jwtProperties.getAbsoluteSessionLifetime() / 1000)) {
            log.info("세션 절대 수명 경과 — 재로그인 필요. sessionId={}", sessionId);
            kill(session);
            return Inspection.decided(RefreshResult.failed());
        }

        // 줄을 서고 보니 앞선 요청이 이미 갱신해 뒀다. 또 부르면 방금 받은 토큰이 폐기된다.
        if (session.refreshedWithin(now, REFRESH_GRACE_SECONDS)) {
            return Inspection.decided(RefreshResult.reusedExisting());
        }

        return Inspection.callSso(session.getRefreshTokenEnc());
    }

    /** 반영 — SSO 결과를 세션에 적는다. 그 사이 바뀐 행은 덮지 않는다. */
    private RefreshResult apply(String sessionId, String usedRefreshTokenEnc,
                                SsoOAuth2Client.TokenPair pair, Consumer<String> beforeCommit) {
        UserSsoSession session = sessionRepository.findForRefresh(sessionId, YNType.N).orElse(null);
        if (session == null) {
            // SSO 를 부르는 사이 끊겼다(로그아웃·기기 로그아웃·해지). 되살리지 않는다.
            log.info("재발급 중 세션이 끊겼다 — 반영하지 않는다. sessionId={}", sessionId);
            return RefreshResult.failed();
        }
        if (!usedRefreshTokenEnc.equals(session.getRefreshTokenEnc())) {
            // 다른 곳이 먼저 갈아끼웠다(이 프로세스의 줄 밖 — 다른 인스턴스). 방금 SSO 확인을 거친
            // 세션이니 유예 창과 같게 본다. 우리 쪽 결과로 덮거나, 경합에 진 거부로 끊지 않는다.
            log.info("재발급 중 다른 곳에서 세션이 갱신됐다 — 그 결과를 쓴다. sessionId={}", sessionId);
            return RefreshResult.reusedExisting();
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

        LocalDateTime now = now();
        session.rotate(cipher.encrypt(pair.refreshToken()), sessionExpiry(now), now);
        beforeCommit.accept(pair.accessToken());
        return RefreshResult.fromSso(pair.accessToken());
    }

    /**
     * 로그아웃 — 이 기기 세션만 끊는다. 다른 기기는 살아 있어야 한다.
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
                .ifPresent(UserSsoSession::revoke);
        markRevoked(sessionId);
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
