package io.groo.damda.security.session.store;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 프로세스 메모리에 두는 "담다에서 로그아웃한 SSO 로그인" 목록. 테스트·단일 인스턴스용.
 *
 * <p>운영 기본값은 Redis 다({@link RedisSsoLoginLogoutStore}) — 여기 남긴 기록은 재시작하면 사라져,
 * 배포 한 번에 로그아웃한 SSO 로그인이 전부 다시 통과한다. {@link InMemorySessionRevocationStore} 와
 * 같은 구조이고, 만료된 항목은 조회할 때 지운다.
 */
@Component
@ConditionalOnProperty(name = "app.session-revocation.store", havingValue = "memory")
public class InMemorySsoLoginLogoutStore implements SsoLoginLogoutStore {

    private final Map<String, Long> loggedOutUntilMillis = new ConcurrentHashMap<>();

    /** 시계를 밖에서 받는다 — 90일 경과를 테스트가 재우지 않고 확인하기 위해서다. */
    private final LongSupplier clock;

    /** 스프링이 쓰는 생성자. 인자 있는 쪽은 테스트 전용이라 여기 기본 시계를 박아 둔다. */
    public InMemorySsoLoginLogoutStore() {
        this(System::currentTimeMillis);
    }

    InMemorySsoLoginLogoutStore(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public void markLoggedOut(String ssoSid) {
        if (ssoSid == null || ssoSid.isBlank()) {
            return;
        }
        loggedOutUntilMillis.put(ssoSid, clock.getAsLong() + RETENTION.toMillis());
    }

    @Override
    public boolean isLoggedOut(String ssoSid) {
        if (ssoSid == null || ssoSid.isBlank()) {
            return false;
        }
        Long until = loggedOutUntilMillis.get(ssoSid);
        if (until == null) {
            return false;
        }
        if (clock.getAsLong() >= until) {
            loggedOutUntilMillis.remove(ssoSid, until);
            return false;
        }
        return true;
    }
}
