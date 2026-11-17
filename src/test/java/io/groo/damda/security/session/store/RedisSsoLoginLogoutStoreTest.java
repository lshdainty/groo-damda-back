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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * "담다에서 로그아웃한 SSO 로그인" 목록의 운영 구현 — <b>Redis 에 실제로 오가는 키와 수명</b>, 그리고
 * Redis 가 죽었을 때의 태도를 본다(보안 점검 CLI-6).
 *
 * <p>조회를 fail-closed 로 두면 <b>Redis 가 죽어 있는 동안 아무도 담다에 로그인하지 못한다.</b>
 * 코드를 읽어서는 try/catch 가 있다는 것만 보이고 어느 쪽으로 여는지는 안 보이므로 여기서 못 박는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisSsoLoginLogoutStoreTest {

    private static final String SSO_SID = "sso-login-1";
    private static final String KEY = "logged-out-sid:sso-login-1";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private RedisSsoLoginLogoutStore sut;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        sut = new RedisSsoLoginLogoutStore(redisTemplate);
    }

    @Test
    @DisplayName("SSO 로그인 단위 키에 90일 수명을 걸어 넣는다")
    void writesSsoLoginScopedKeyFor90Days() {
        sut.markLoggedOut(SSO_SID);

        verify(valueOps).set(KEY, "1", Duration.ofDays(90));
    }

    @Test
    @DisplayName("키가 있으면 로그아웃한 로그인, 없으면 통과")
    void readsByKey() {
        given(redisTemplate.hasKey(KEY)).willReturn(true);
        assertThat(sut.isLoggedOut(SSO_SID)).isTrue();

        given(redisTemplate.hasKey(KEY)).willReturn(false);
        assertThat(sut.isLoggedOut(SSO_SID)).isFalse();
    }

    @Test
    @DisplayName("hasKey 가 null 이어도 통과로 본다 — 파이프라인·트랜잭션 모드의 반환값이다")
    void nullMeansNotLoggedOut() {
        given(redisTemplate.hasKey(KEY)).willReturn(null);

        assertThat(sut.isLoggedOut(SSO_SID)).isFalse();
    }

    @Test
    @DisplayName("Redis 가 죽으면 로그인을 통과시킨다(fail-open) — 닫으면 그동안 아무도 로그인하지 못한다")
    void readFailsOpen() {
        willThrow(new RedisConnectionFailureException("down")).given(redisTemplate).hasKey(KEY);

        assertThat(sut.isLoggedOut(SSO_SID)).isFalse();
    }

    @Test
    @DisplayName("조회가 타임아웃 나도 같은 태도 — 로그인을 500 으로 만들지 않는다")
    void readTimeoutFailsOpen() {
        willThrow(new QueryTimeoutException("slow")).given(redisTemplate).hasKey(KEY);

        assertThat(sut.isLoggedOut(SSO_SID)).isFalse();
    }

    @Test
    @DisplayName("쓰기가 실패해도 예외를 던지지 않는다 — 기억하지 못해도 로그아웃은 끝나야 한다")
    void writeFailureDoesNotThrow() {
        willThrow(new RedisConnectionFailureException("down"))
                .given(valueOps).set(KEY, "1", Duration.ofDays(90));

        assertThatCode(() -> sut.markLoggedOut(SSO_SID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SSO 로그인 번호가 없으면 Redis 를 부르지 않는다 — sid 없는 옛 토큰이 여기로 온다")
    void blankSsoSidSkipsRedis() {
        assertThat(sut.isLoggedOut(null)).isFalse();
        assertThat(sut.isLoggedOut("")).isFalse();
        sut.markLoggedOut(null);
        sut.markLoggedOut(" ");

        then(redisTemplate).should(never()).hasKey(anyString());
        then(valueOps).shouldHaveNoInteractions();
    }
}
