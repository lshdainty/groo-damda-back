package io.groo.damda.security.session.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * 운영 구현 — <b>Redis 에 실제로 오가는 키와 TTL</b>, 그리고 Redis 가 죽었을 때의 태도를 본다.
 *
 * <p>후자가 이 테스트의 본론이다. 조회를 fail-closed 로 두면 <b>Redis 재시작 한 번에 전
 * 사용자가 로그아웃된다</b> — 로그아웃하지 않은 사람까지 포함해서다. 코드를 읽어서는
 * try/catch 가 있다는 것만 보이고 어느 쪽으로 여는지는 안 보이므로 여기서 못 박는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisSessionRevocationStoreTest {

    private static final String SESSION_ID = "sid-1";
    private static final String KEY = "session:revoked:sid-1";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private RedisSessionRevocationStore sut;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        sut = new RedisSessionRevocationStore(redisTemplate);
    }

    @Test
    @DisplayName("세션 단위 키에 TTL 을 걸어 넣는다 — 사용자 단위면 '이 기기만 로그아웃'을 못 쓴다")
    void writesSessionScopedKeyWithTtl() {
        sut.revoke(SESSION_ID, 3600L);

        verify(valueOps).set(KEY, "1", Duration.ofSeconds(3600L));
    }

    @Test
    @DisplayName("TTL 이 0 이하면 저장하지 않는다")
    void zeroTtlIsNotStored() {
        sut.revoke(SESSION_ID, 0L);

        then(valueOps).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("키가 있으면 폐기, 없으면 통과")
    void readsByKey() {
        given(redisTemplate.hasKey(KEY)).willReturn(true);
        assertThat(sut.isRevoked(SESSION_ID)).isTrue();

        given(redisTemplate.hasKey(KEY)).willReturn(false);
        assertThat(sut.isRevoked(SESSION_ID)).isFalse();
    }

    @Test
    @DisplayName("hasKey 가 null 이어도 통과로 본다 — 파이프라인·트랜잭션 모드의 반환값이다")
    void nullMeansNotRevoked() {
        given(redisTemplate.hasKey(KEY)).willReturn(null);

        assertThat(sut.isRevoked(SESSION_ID)).isFalse();
    }

    @Test
    @DisplayName("Redis 가 죽으면 통과시킨다(fail-open) — 닫으면 재시작 한 번에 전원 로그아웃이다")
    void readFailsOpen() {
        willThrow(new RedisConnectionFailureException("down")).given(redisTemplate).hasKey(KEY);

        assertThat(sut.isRevoked(SESSION_ID)).isFalse();
    }

    @Test
    @DisplayName("조회가 타임아웃 나도 같은 태도 — 요청 하나를 500 으로 만들지 않는다")
    void readTimeoutFailsOpen() {
        willThrow(new QueryTimeoutException("slow")).given(redisTemplate).hasKey(KEY);

        assertThat(sut.isRevoked(SESSION_ID)).isFalse();
    }

    @Test
    @DisplayName("쓰기가 실패해도 예외를 던지지 않는다 — 로그아웃 API 가 500 이 되면 쿠키도 안 지워진다")
    void writeFailureDoesNotThrow() {
        willThrow(new RedisConnectionFailureException("down"))
                .given(valueOps).set(KEY, "1", Duration.ofSeconds(3600L));

        assertThatCode(() -> sut.revoke(SESSION_ID, 3600L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("세션 id 가 없으면 Redis 를 부르지 않는다 — 임베드·옛 토큰이 여기로 온다")
    void blankSessionIdSkipsRedis() {
        assertThat(sut.isRevoked(null)).isFalse();
        assertThat(sut.isRevoked("")).isFalse();
        sut.revoke(null, 3600L);

        then(redisTemplate).should(never()).hasKey(anyString());
        then(valueOps).shouldHaveNoInteractions();
    }

    // ── SSO 가 끊은 로그인(CLI-6) ─────────────────────────────────

    private static final String SSO_SID = "sso-login-1";
    private static final String SSO_KEY = "damda:revoked-sso-sid:sso-login-1";

    @Test
    @DisplayName("CLI-6: 끊긴 SSO 로그인은 세션 단위 키와 다른 접두사로 적는다 — 세션 id 와 값 공간이 섞이지 않는다")
    void writesSsoLoginKeyWithTtl() {
        sut.revokeSsoLogin(SSO_SID, 86_400L);

        verify(valueOps).set(SSO_KEY, "1", Duration.ofSeconds(86_400L));
    }

    @Test
    @DisplayName("CLI-6: 번호가 없거나 수명이 0 이하면 적지 않는다")
    void ssoLoginBlankOrNoTtl_notWritten() {
        sut.revokeSsoLogin(null, 60L);
        sut.revokeSsoLogin(" ", 60L);
        sut.revokeSsoLogin(SSO_SID, 0L);

        verify(valueOps, org.mockito.Mockito.never()).set(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    @DisplayName("CLI-6: 키가 있으면 끊긴 로그인, 없거나 번호가 없으면 통과")
    void readsSsoLoginKey() {
        given(redisTemplate.hasKey(SSO_KEY)).willReturn(true);

        assertThat(sut.isSsoLoginRevoked(SSO_SID)).isTrue();
        assertThat(sut.isSsoLoginRevoked("sso-login-2")).isFalse();
        assertThat(sut.isSsoLoginRevoked(null)).isFalse();
        assertThat(sut.isSsoLoginRevoked(" ")).isFalse();
        // 같은 글자의 세션 id 는 다른 키다
        assertThat(sut.isRevoked(SSO_SID)).isFalse();
    }

    @Test
    @DisplayName("CLI-6: Redis 가 죽으면 조회는 통과(fail-open), 쓰기는 예외를 올리지 않는다")
    void ssoLoginFailsOpen() {
        willThrow(new RedisConnectionFailureException("down")).given(redisTemplate).hasKey(SSO_KEY);
        assertThat(sut.isSsoLoginRevoked(SSO_SID)).isFalse();

        willThrow(new RedisConnectionFailureException("down")).given(valueOps)
                .set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Duration.class));
        org.assertj.core.api.Assertions.assertThatCode(() -> sut.revokeSsoLogin(SSO_SID, 60L))
                .doesNotThrowAnyException();
    }
}
