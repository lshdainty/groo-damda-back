package io.groo.damda.security.session.store;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 프로세스 메모리 폐기 표식. 테스트·단일 인스턴스용.
 *
 * <p>운영 기본값은 Redis 다({@link RedisSessionRevocationStore}) — 여기 남긴 표식은 재시작하면
 * 사라지고 인스턴스끼리 공유되지 않아, 로그아웃을 받지 않은 인스턴스가 그 토큰을 그대로
 * 통과시킨다.
 *
 * <p>Redis 구현과 달리 여기서는 예외가 날 자리가 없어 fail-open 을 따로 쓰지 않는다.
 *
 * <p>만료된 항목은 조회할 때 지운다. 별도 청소 스레드를 두지 않는 이유는 표식 수명이
 * access token 수명(1시간)이라 아무리 쌓여도 한 시간치 로그아웃 세션 수를 넘지 않아서다.
 */
@Component
@ConditionalOnProperty(name = "app.session-revocation.store", havingValue = "memory")
public class InMemorySessionRevocationStore implements SessionRevocationStore {

    private final Map<String, Long> revokedUntilMillis = new ConcurrentHashMap<>();

    /**
     * 시계를 밖에서 받는다 — TTL 경과를 테스트가 {@code Thread.sleep} 없이 확인하기 위해서다.
     *
     * <p>재우는 테스트는 느린 데다 벽시계에 의존한다. 이 워크스페이스(WSL)처럼 시계가 뒤로
     * 튀는 환경에서는 1.1초를 자고도 "아직 안 지났다" 가 나와 간헐적으로 깨진다 — 실제로 한 번
     * 깨졌다. 인증 필터가 매 요청 부르는 코드라 간헐 실패를 안고 갈 자리가 아니다.
     */
    private final LongSupplier clock;

    /** 스프링이 쓰는 생성자. 인자 있는 쪽은 테스트 전용이라 여기 기본 시계를 박아 둔다. */
    public InMemorySessionRevocationStore() {
        this(System::currentTimeMillis);
    }

    InMemorySessionRevocationStore(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public void revoke(String sessionId, long ttlSeconds) {
        if (sessionId == null || sessionId.isBlank() || ttlSeconds <= 0) {
            return;
        }
        revokedUntilMillis.put(sessionId, expiresAt(ttlSeconds));
    }

    @Override
    public boolean isRevoked(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Long until = revokedUntilMillis.get(sessionId);
        if (until == null) {
            return false;
        }
        if (clock.getAsLong() >= until) {
            revokedUntilMillis.remove(sessionId, until);
            return false;
        }
        return true;
    }

    // SSO 로그인 번호는 세션 id 와 다른 값 공간이다 — 같은 맵에 접두사를 붙여 둔다(수명 처리는 같다)
    private static final String SSO_LOGIN_PREFIX = "sso-login:";

    @Override
    public void revokeSsoLogin(String ssoSid, long ttlSeconds) {
        if (ssoSid == null || ssoSid.isBlank()) {
            return;
        }
        revoke(SSO_LOGIN_PREFIX + ssoSid, ttlSeconds);
    }

    @Override
    public boolean isSsoLoginRevoked(String ssoSid) {
        return ssoSid != null && !ssoSid.isBlank() && isRevoked(SSO_LOGIN_PREFIX + ssoSid);
    }

    /** ttl 이 아무리 커도 오버플로로 과거가 되지 않게 막는다. */
    private long expiresAt(long ttlSeconds) {
        long ttlMillis = ttlSeconds > (Long.MAX_VALUE / 1000L) ? Long.MAX_VALUE : ttlSeconds * 1000L;
        try {
            return Math.addExact(clock.getAsLong(), ttlMillis);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
