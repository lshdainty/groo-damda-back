package io.groo.damda.security.session.store;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 담다에서 로그아웃한 SSO 로그인 목록 — 운영 기본값.
 *
 * <p>{@link RedisSessionRevocationStore} 와 같은 방식이다. 인스턴스끼리 공유돼야 하고 재시작해도 남아야
 * 해서 Redis 에 두고, 수명은 Redis TTL 에 맡긴다. 값에 비밀이 없다 — 담는 것은 SSO 로그인 번호와
 * 상수 {@code "1"} 뿐이다.
 *
 * <h2>읽기는 fail-open, 쓰기는 소리내어 실패</h2>
 * {@link #isLoggedOut} 은 Redis 장애를 삼키고 {@code false}(통과)를 돌려준다 — <b>Redis 를 못 읽는다고
 * 로그인을 막지 않는다.</b> 닫으면(fail-closed) Redis 가 죽어 있는 동안 <b>아무도 담다에 로그인하지
 * 못한다</b>. 열었을 때 잃는 것은 "Redis 가 죽어 있는 동안만, 담다에서 로그아웃한 SSO 로그인이 비밀번호
 * 없이 다시 들어올 수 있다" 인데, 그것도 그 SSO 로그인이 아직 살아 있는 브라우저를 쥔 사람에게만 열린다.
 * 전원 로그인 불가와 견주면 좁은 창이라 연다. 세션 폐기 표식 조회와 같은 판단이다.
 *
 * <p>반면 {@link #markLoggedOut} 실패는 <b>기억하지 못한 것</b>이라 error 로 남긴다. 예외를 던지지는
 * 않는다 — 로그아웃 API 가 500 이 되면 쿠키도 안 지워진다. 담다 세션은 이미 끊겼다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.session-revocation.store", havingValue = "redis", matchIfMissing = true)
@RequiredArgsConstructor
public class RedisSsoLoginLogoutStore implements SsoLoginLogoutStore {

    // 서비스 접두사(damda:)는 여기 쓰지 않는다 — 템플릿(RedisConfig)이 모든 키에 붙인다
    private static final String KEY_PREFIX = "logged-out-sid:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void markLoggedOut(String ssoSid) {
        if (ssoSid == null || ssoSid.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(ssoSid), "1", RETENTION);
        } catch (RuntimeException e) {
            log.error("담다 로그아웃을 기억하지 못했다 — 이 SSO 로그인이 살아 있는 동안 비밀번호 없이 다시"
                    + " 들어올 수 있다. ssoSid={}, 원인={}", ssoSid, e.toString());
        }
    }

    @Override
    public boolean isLoggedOut(String ssoSid) {
        if (ssoSid == null || ssoSid.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(ssoSid)));
        } catch (RuntimeException e) {
            // fail-open. 클래스 주석의 판단을 여기서 실행한다 — 닫으면 Redis 장애가 곧 전원 로그인 불가다.
            log.warn("담다 로그아웃 기록을 조회하지 못해 로그인을 통과시킨다. ssoSid={}, 원인={}",
                    ssoSid, e.getClass().getSimpleName());
            return false;
        }
    }

    private static String key(String ssoSid) {
        return KEY_PREFIX + ssoSid;
    }
}
