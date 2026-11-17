package io.groo.damda.security.session.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메모리 폐기 표식 — 테스트 컨텍스트가 실제로 쓰는 구현이다(Redis 를 띄우지 않는다).
 *
 * <p>여기가 틀리면 전 테스트가 "로그아웃해도 안 막힌다" 를 초록불로 통과시킨다.
 *
 * <p>시계를 직접 돌린다. 재우면 느리고, 시계가 뒤로 튀는 환경에서 간헐적으로 깨진다.
 */
class InMemorySessionRevocationStoreTest {

    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private final InMemorySessionRevocationStore sut = new InMemorySessionRevocationStore(clock::get);

    @Test
    @DisplayName("표식을 남긴 세션은 폐기로 읽힌다")
    void revokedSessionIsSeen() {
        sut.revoke("sid-1", 3600L);

        assertThat(sut.isRevoked("sid-1")).isTrue();
    }

    @Test
    @DisplayName("표식을 안 남긴 세션은 통과한다 — 다른 기기까지 끊으면 안 된다")
    void otherSessionsPass() {
        sut.revoke("sid-1", 3600L);

        assertThat(sut.isRevoked("sid-2")).isFalse();
    }

    @Test
    @DisplayName("TTL 이 지나면 표식이 사라진다 — 그 뒤 토큰은 어차피 자연 만료다")
    void expiresAfterTtl() {
        sut.revoke("sid-1", 3600L);

        clock.addAndGet(3_599_999L);
        assertThat(sut.isRevoked("sid-1")).isTrue();

        clock.addAndGet(1L);
        assertThat(sut.isRevoked("sid-1")).isFalse();
    }

    @Test
    @DisplayName("TTL 이 0 이하면 저장하지 않는다 — 이미 만료된 토큰의 표식이다")
    void zeroTtlIsNotStored() {
        sut.revoke("sid-1", 0L);

        assertThat(sut.isRevoked("sid-1")).isFalse();
    }

    @Test
    @DisplayName("세션 id 가 없으면 조회하지 않는다 — 임베드·옛 토큰이 여기로 온다")
    void blankSessionId() {
        sut.revoke(null, 3600L);
        sut.revoke("", 3600L);

        assertThat(sut.isRevoked(null)).isFalse();
        assertThat(sut.isRevoked("")).isFalse();
    }

    @Test
    @DisplayName("기본 생성자(스프링이 쓰는 쪽)도 시계가 물려 있다")
    void defaultConstructorWorks() {
        InMemorySessionRevocationStore springBean = new InMemorySessionRevocationStore();

        springBean.revoke("sid-1", 3600L);

        assertThat(springBean.isRevoked("sid-1")).isTrue();
    }

    @Test
    @DisplayName("CLI-6: 끊긴 SSO 로그인은 세션 표식과 따로 읽힌다 — 같은 글자여도 서로 영향을 주지 않는다")
    void ssoLoginIsSeparateFromSession() {
        sut.revokeSsoLogin("x", 60L);

        assertThat(sut.isSsoLoginRevoked("x")).isTrue();
        assertThat(sut.isRevoked("x")).isFalse();
        assertThat(sut.isSsoLoginRevoked("y")).isFalse();
        assertThat(sut.isSsoLoginRevoked(null)).isFalse();
        assertThat(sut.isSsoLoginRevoked(" ")).isFalse();

        sut.revoke("y", 60L);
        assertThat(sut.isSsoLoginRevoked("y")).isFalse();
    }

    @Test
    @DisplayName("CLI-6: 끊긴 SSO 로그인 표식도 수명이 지나면 사라진다")
    void ssoLoginExpires() {
        sut.revokeSsoLogin("x", 60L);
        clock.addAndGet(60_000L);

        assertThat(sut.isSsoLoginRevoked("x")).isFalse();
    }
}
