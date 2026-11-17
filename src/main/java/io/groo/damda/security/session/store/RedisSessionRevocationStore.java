package io.groo.damda.security.session.store;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 폐기 표식 저장소 — 운영 기본값.
 *
 * <p>인스턴스끼리 공유돼야 한다. 프로세스 메모리에 두면 로그아웃을 받은 인스턴스만 알고,
 * 다른 인스턴스는 그 토큰을 계속 통과시킨다 — 로그아웃이 "가끔 먹는" 기능이 된다.
 *
 * <p>TTL 은 Redis 가 관리한다. 만료 시각을 값에 담아 직접 비교하면 인스턴스 시계가 어긋났을 때
 * 한쪽만 만료로 본다.
 *
 * <p><b>값에 비밀이 없다.</b> 담는 것은 세션 id 와 상수 {@code "1"} 뿐이라
 * {@code RedisBrokerTokenStore} 처럼 암호화하지 않는다 — 세션 id 를 알아도 서명된 토큰을
 * 만들 수는 없다.
 *
 * <h2>읽기는 fail-open, 쓰기는 소리내어 실패</h2>
 * {@link #isRevoked} 는 Redis 장애를 삼키고 {@code false}(통과)를 돌려준다. 반대로 하면
 * (fail-closed) <b>Redis 재시작 한 번에 전 사용자가 로그아웃된다</b> — 로그아웃 안 한 사람까지
 * 포함해서다. 잃는 것과 얻는 것을 견줘 보면, 닫았을 때 잃는 것은 "전원 로그인 불가"(가용성 전면
 * 손실)이고 열었을 때 잃는 것은 "Redis 가 죽어 있는 동안만 옛 토큰이 통한다"(창이 좁고,
 * refresh 토큰은 DB 에서 이미 끊겨 있어 만료 후에는 되살아나지 못한다). 그래서 연다.
 * {@code SsoSessionService} 가 SSO 장애에 대해 이미 같은 판단을 해 뒀다.
 *
 * <p>반면 {@link #revoke} 실패는 <b>로그아웃이 안 먹은 것</b>이므로 error 로 남긴다. 여기서
 * 예외를 그대로 던지면 로그아웃 API 자체가 500 이 되어 쿠키도 안 지워진다 — 사용자 눈에는
 * "로그아웃 버튼이 고장" 이라 더 나쁘다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.session-revocation.store", havingValue = "redis", matchIfMissing = true)
@RequiredArgsConstructor
public class RedisSessionRevocationStore implements SessionRevocationStore {

    private static final String KEY_PREFIX = "session:revoked:";
    /** SSO 가 끊은 로그인 번호 — 세션 단위 키와 섞이지 않게 접두사를 나눈다 */
    private static final String SSO_LOGIN_KEY_PREFIX = "damda:revoked-sso-sid:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void revoke(String sessionId, long ttlSeconds) {
        if (sessionId == null || sessionId.isBlank() || ttlSeconds <= 0) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(sessionId), "1", Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException e) {
            log.error("세션 폐기 표식을 남기지 못했다 — 이 세션의 access token 이 만료까지 살아 있다."
                    + " sessionId={}, 원인={}", sessionId, e.toString());
        }
    }

    @Override
    public boolean isRevoked(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(sessionId)));
        } catch (RuntimeException e) {
            // fail-open. 클래스 주석의 판단을 여기서 실행한다 — 닫으면 Redis 장애가 곧
            // 전 사용자 로그아웃이다.
            log.warn("세션 폐기 표식을 조회하지 못해 통과시킨다. sessionId={}, 원인={}",
                    sessionId, e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public void revokeSsoLogin(String ssoSid, long ttlSeconds) {
        if (ssoSid == null || ssoSid.isBlank() || ttlSeconds <= 0) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(SSO_LOGIN_KEY_PREFIX + ssoSid, "1", Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException e) {
            log.error("끊긴 SSO 로그인 표식을 남기지 못했다 — 그 로그인의 access token 이 만료까지 살아 있다."
                    + " 원인={}", e.toString());
        }
    }

    @Override
    public boolean isSsoLoginRevoked(String ssoSid) {
        if (ssoSid == null || ssoSid.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(SSO_LOGIN_KEY_PREFIX + ssoSid));
        } catch (RuntimeException e) {
            // fail-open — isRevoked 와 같은 판단이다
            log.warn("끊긴 SSO 로그인 표식을 조회하지 못해 통과시킨다. 원인={}", e.getClass().getSimpleName());
            return false;
        }
    }

    private static String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
