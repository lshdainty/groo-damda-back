package io.groo.damda.security.session.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메모리에 두는 "담다에서 로그아웃한 SSO 로그인" 목록 — 테스트 컨텍스트가 실제로 쓰는 구현이다
 * (Redis 를 띄우지 않는다).
 *
 * <p>여기가 틀리면 "로그아웃한 SSO 로그인으로 다시 들어오면 거절" 을 보는 테스트가 초록불로 샌다.
 * 시계를 직접 돌린다 — 90일을 재울 수는 없다.
 */
class InMemorySsoLoginLogoutStoreTest {

    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private final InMemorySsoLoginLogoutStore sut = new InMemorySsoLoginLogoutStore(clock::get);

    @Test
    @DisplayName("기억해 둔 SSO 로그인은 로그아웃한 것으로 읽힌다")
    void loggedOutLoginIsSeen() {
        sut.markLoggedOut("sso-login-1");

        assertThat(sut.isLoggedOut("sso-login-1")).isTrue();
    }

    @Test
    @DisplayName("다른 SSO 로그인은 통과한다 — 비밀번호로 다시 로그인하면 새 번호가 나온다")
    void otherLoginsPass() {
        sut.markLoggedOut("sso-login-1");

        assertThat(sut.isLoggedOut("sso-login-2")).isFalse();
    }

    @Test
    @DisplayName("90일이 지나면 기록이 사라진다")
    void expiresAfter90Days() {
        sut.markLoggedOut("sso-login-1");

        clock.addAndGet(Duration.ofDays(90).toMillis() - 1);
        assertThat(sut.isLoggedOut("sso-login-1")).isTrue();

        clock.addAndGet(1L);
        assertThat(sut.isLoggedOut("sso-login-1")).isFalse();
    }

    @Test
    @DisplayName("SSO 로그인 번호가 없으면 기억하지도 조회하지도 않는다 — sid 없는 옛 토큰이 여기로 온다")
    void blankSsoSid() {
        sut.markLoggedOut(null);
        sut.markLoggedOut("");

        assertThat(sut.isLoggedOut(null)).isFalse();
        assertThat(sut.isLoggedOut("")).isFalse();
    }

    @Test
    @DisplayName("기본 생성자(스프링이 쓰는 쪽)도 시계가 물려 있다")
    void defaultConstructorWorks() {
        InMemorySsoLoginLogoutStore springBean = new InMemorySsoLoginLogoutStore();

        springBean.markLoggedOut("sso-login-1");

        assertThat(springBean.isLoggedOut("sso-login-1")).isTrue();
    }
}
