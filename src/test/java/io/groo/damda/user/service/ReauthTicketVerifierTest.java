package io.groo.damda.user.service;

import io.groo.core.exception.UnauthorizedException;
import io.groo.damda.security.jwt.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 재인증 티켓 검증 — <b>이 조작을, 이 사람이, 방금 확인했나.</b>
 *
 * <p>셋 다 봐야 한다. 하나라도 빠지면 이런 일이 생긴다.
 * <ul>
 *   <li>용도를 안 보면 — 다른 조작으로 받은 티켓이 해지에 통과한다</li>
 *   <li>단회를 안 보면 — 한 번 받은 티켓으로 계속 해지를 시도한다</li>
 *   <li><b>주인을 안 보면 — 남의 티켓으로 내 계정을 해지시킬 수 있다</b></li>
 * </ul>
 *
 * <p>마지막 줄이 실제로 뚫려 있었다(2026-09-17 QA: r13 계정으로 받은 티켓을 r12 세션에
 * 실어 보냈더니 통과해 해지 루틴까지 들어갔다). 검증기는 주인을 돌려주고 있었는데
 * 컨트롤러가 그 반환값을 버렸다 — 그래서 <b>대조까지 하는 입구</b>를 따로 뒀다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("재인증 티켓 검증")
class ReauthTicketVerifierTest {

    private static final String TICKET = "signed.reauth.ticket";
    private static final String OWNER = "user-a";

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private Claims claims;

    @InjectMocks private ReauthTicketVerifier verifier;

    @BeforeEach
    void setUp() {
        given(stringRedisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .willReturn(true);
        given(jwtTokenProvider.validateSsoToken(TICKET)).willReturn(claims);
        given(claims.get("type", String.class)).willReturn("reauth");
        given(claims.get("purpose", String.class)).willReturn("withdraw");
        given(claims.getId()).willReturn("jti-1");
        given(claims.getSubject()).willReturn(OWNER);
        given(claims.getExpiration())
                .willReturn(new Date(System.currentTimeMillis() + 600_000));
    }

    @Nested
    @DisplayName("주인 대조")
    class Owner {

        @Test
        @DisplayName("주인이 맞으면 통과하고 그 아이디를 돌려준다")
        void sameOwnerPasses() {
            assertThat(verifier.consumeFor(TICKET, "withdraw", OWNER)).isEqualTo(OWNER);
        }

        @Test
        @DisplayName("남의 티켓은 막는다 — 이게 뚫려 있었다")
        void otherOwnerRejected() {
            assertThatThrownBy(() -> verifier.consumeFor(TICKET, "withdraw", "user-b"))
                    .isInstanceOf(UnauthorizedException.class);
        }

        @Test
        @DisplayName("로그인 아이디가 비면 막는다 — 비교 대상이 없으면 통과가 아니라 거절이다")
        void blankExpectedRejected() {
            assertThatThrownBy(() -> verifier.consumeFor(TICKET, "withdraw", null))
                    .isInstanceOf(UnauthorizedException.class);
            assertThatThrownBy(() -> verifier.consumeFor(TICKET, "withdraw", " "))
                    .isInstanceOf(UnauthorizedException.class);
        }

        @Test
        @DisplayName("주인이 달라도 티켓은 태운다 — 남겨 두면 맞는 세션을 찾아 계속 시도한다")
        void mismatchStillBurnsTicket() {
            assertThatThrownBy(() -> verifier.consumeFor(TICKET, "withdraw", "user-b"))
                    .isInstanceOf(UnauthorizedException.class);

            verify(valueOps).setIfAbsent(
                    eq("reauth:used:jti-1"), anyString(), anyLong(), any(TimeUnit.class));
        }
    }

    @Nested
    @DisplayName("주인 말고도 보는 것")
    class Rest {

        @Test
        @DisplayName("티켓이 없으면 막고 Redis 까지 가지 않는다")
        void missingTicket() {
            assertThatThrownBy(() -> verifier.consumeFor(null, "withdraw", OWNER))
                    .isInstanceOf(UnauthorizedException.class);
            assertThatThrownBy(() -> verifier.consumeFor("  ", "withdraw", OWNER))
                    .isInstanceOf(UnauthorizedException.class);

            verify(valueOps, never())
                    .setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        }

        @Test
        @DisplayName("용도가 다르면 막는다 — 다른 조작으로 받은 티켓이 해지에 통과하면 안 된다")
        void wrongPurpose() {
            assertThatThrownBy(() -> verifier.consumeFor(TICKET, "change-password", OWNER))
                    .isInstanceOf(UnauthorizedException.class);
        }

        @Test
        @DisplayName("접근 토큰을 티켓 자리에 넣으면 막는다")
        void wrongType() {
            given(claims.get("type", String.class)).willReturn("access");

            assertThatThrownBy(() -> verifier.consumeFor(TICKET, "withdraw", OWNER))
                    .isInstanceOf(UnauthorizedException.class);
        }

        @Test
        @DisplayName("이미 쓴 티켓은 막는다")
        void reuseRejected() {
            given(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                    .willReturn(false);

            assertThatThrownBy(() -> verifier.consumeFor(TICKET, "withdraw", OWNER))
                    .isInstanceOf(UnauthorizedException.class);
        }

        @Test
        @DisplayName("서명이 안 맞으면 막는다")
        void badSignature() {
            given(jwtTokenProvider.validateSsoToken(TICKET))
                    .willThrow(new IllegalArgumentException("bad signature"));

            assertThatThrownBy(() -> verifier.consumeFor(TICKET, "withdraw", OWNER))
                    .isInstanceOf(UnauthorizedException.class);
        }
    }
}
