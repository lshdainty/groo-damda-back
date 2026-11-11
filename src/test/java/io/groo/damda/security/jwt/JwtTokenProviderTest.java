package io.groo.damda.security.jwt;

import io.groo.damda.common.config.properties.JwtProperties;
import io.groo.damda.security.principal.JwtClaimsPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtTokenProvider — access/embed 토큰 발급·검증 + typ 클레임/isEmbed 검증.
 */
class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-key-must-be-long-enough-for-hs256-aaaaaaaa";
    private static final long ACCESS_EXP_MS = 30 * 60 * 1000L;

    private JwtTokenProvider sut;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        props.setAccessTokenExpiration(ACCESS_EXP_MS);
        // 자체 토큰(HMAC) 발급·검증만 테스트하므로 SSO JWKS Locator 는 불필요 → null.
        sut = new JwtTokenProvider(props, null);
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("access 토큰 발급 — typ=access 클레임이 포함되고 만료가 약 30분")
    void createAccessToken_setsTypAccess() {
        String token = sut.createAccessToken("u1", "User", "u@e.com", 100L, "sid-1");

        Claims c = parse(token);
        assertThat(c.get("typ", String.class)).isEqualTo("access");
        assertThat(c.getSubject()).isEqualTo("u1");
        assertThat(c.get("userRowId", Long.class)).isEqualTo(100L);
        long remaining = c.getExpiration().getTime() - System.currentTimeMillis();
        // 5초 이상, 만료 ± 1초 오차 허용
        assertThat(remaining).isBetween(ACCESS_EXP_MS - 5_000L, ACCESS_EXP_MS + 1_000L);
    }

    @Test
    @DisplayName("embed 토큰 발급 — typ=embed, 만료 60초, 메인 secret 으로 서명되어 같은 provider 가 검증 가능")
    void createEmbedToken_typEmbed_60sec_verifiable() {
        String token = sut.createEmbedToken("u1", "User", "u@e.com", 100L, "sid-parent");

        Claims c = parse(token);
        assertThat(c.get("typ", String.class)).isEqualTo("embed");
        // 부모 세션(jti)을 그대로 싣는다 — 로그아웃 폐기 검사의 열쇠(S-3)
        assertThat(c.getId()).isEqualTo("sid-parent");
        long remaining = c.getExpiration().getTime() - System.currentTimeMillis();
        assertThat(remaining).isBetween(50_000L, 61_000L);

        assertThat(sut.isTokenValid(token)).isTrue();
        JwtClaimsPrincipal claims = sut.validateAndGetClaims(token);
        assertThat(claims.tokenType()).isEqualTo("embed");
        assertThat(claims.isEmbed()).isTrue();
        assertThat(claims.userRowId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("access 토큰의 isEmbed=false")
    void accessToken_isNotEmbed() {
        String token = sut.createAccessToken("u1", "User", "u@e.com", 100L, "sid-1");
        assertThat(sut.validateAndGetClaims(token).isEmbed()).isFalse();
    }

    @Test
    @DisplayName("기존 토큰 호환 — typ 클레임 없는 토큰도 validateAndGetClaims 는 access 로 간주")
    void legacyTokenWithoutTyp_defaultsToAccess() {
        // typ 클레임을 일부러 빼고 직접 발급(기존 토큰 모사)
        String legacyToken = Jwts.builder()
                .subject("u1")
                .claim("userName", "User")
                .claim("userEmail", "u@e.com")
                .claim("userRowId", 100L)
                .signWith(signingKey)
                .compact();

        JwtClaimsPrincipal claims = sut.validateAndGetClaims(legacyToken);
        assertThat(claims.tokenType()).isEqualTo("access");
        assertThat(claims.isEmbed()).isFalse();
    }

    private Claims parse(String token) {
        return Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
    }
}
