package io.groo.damda.security.jwt;

import io.groo.damda.common.config.properties.JwtProperties;
import io.groo.damda.security.principal.JwtClaimsPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtTokenProvider — access/embed 토큰 발급·검증 + typ 클레임/isEmbed 검증.
 */
class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-key-must-be-long-enough-for-hs256-aaaaaaaa";
    private static final long ACCESS_EXP_MS = 30 * 60 * 1000L;

    private JwtTokenProvider sut;
    private JwtProperties props;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        props = new JwtProperties();
        props.setSecret(SECRET);
        props.setAccessTokenExpiration(ACCESS_EXP_MS);
        // 자체 토큰(HMAC) 발급·검증만 테스트하므로 SSO JWKS Locator 는 불필요 → null.
        sut = new JwtTokenProvider(props, null);
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("access 토큰 발급 — typ=access 클레임이 포함되고 만료가 약 30분")
    void createAccessToken_setsTypAccess() {
        String token = sut.createAccessToken("u1", "User", "u@e.com", 100L, "sid-1", null);

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
        String token = sut.createAccessToken("u1", "User", "u@e.com", 100L, "sid-1", null);
        assertThat(sut.validateAndGetClaims(token).isEmbed()).isFalse();
    }

    @Test
    @DisplayName("새 토큰은 iss=porest-desk 를 싣는다")
    void newTokens_carryDeskIssuer() {
        assertThat(parse(sut.createAccessToken("u1", "User", "u@e.com", 100L, "sid-1", null)).getIssuer())
                .isEqualTo(JwtTokenProvider.DESK_ISSUER);
        assertThat(parse(sut.createEmbedToken("u1", "User", "u@e.com", 100L, "sid-1")).getIssuer())
                .isEqualTo(JwtTokenProvider.DESK_ISSUER);
    }

    /**
     * N-2 재현 — 이음(HR) 토큰 모양. 이음은 iss=porest-hr, type=hr_access 를 싣고 typ 는 없다.
     * 두 서비스의 HMAC 시크릿이 같으면 서명이 맞아 예전엔 담다가 access 로 받아들였다.
     */
    private String ieumShapedToken(Date exp) {
        return Jwts.builder()
                .subject("u1")
                .issuer("porest-hr")
                .claim("type", "hr_access")
                .claim("ssoUserRowId", 100L)
                .claim("name", "User")
                .claim("email", "u@e.com")
                .issuedAt(new Date())
                .expiration(exp)
                .signWith(signingKey)
                .compact();
    }

    @Test
    @DisplayName("N-2: 같은 키로 서명된 이음 토큰은 거절한다(유효·만료 둘 다)")
    void foreignServiceToken_rejected() {
        String live = ieumShapedToken(new Date(System.currentTimeMillis() + 600_000L));
        assertThat(sut.isTokenValid(live)).isFalse();
        assertThatThrownBy(() -> sut.validateAndGetClaims(live)).isInstanceOf(JwtException.class);

        String expired = ieumShapedToken(new Date(System.currentTimeMillis() - 60_000L));
        // 만료 토큰은 무음 재인증으로 가는데, 거기서도 남의 토큰이면 재발급 대상이 아니다
        assertThat(sut.parseExpiredClaims(expired)).isNull();
    }

    @Test
    @DisplayName("N-2: typ 이 access 여도 iss 가 다른 서비스면 거절한다")
    void otherIssuerWithDeskTyp_rejected() {
        String token = Jwts.builder()
                .subject("u1").issuer("porest-hr").claim("typ", "access").claim("userRowId", 100L)
                .expiration(new Date(System.currentTimeMillis() + 600_000L))
                .signWith(signingKey).compact();
        assertThat(sut.isTokenValid(token)).isFalse();
    }

    @Test
    @DisplayName("N-2: typ 이 없거나 모르는 값이면 거절한다 — 예전엔 없으면 access 로 봐 줬다")
    void missingOrUnknownTyp_rejected() {
        String noTyp = Jwts.builder()
                .subject("u1").issuer(JwtTokenProvider.DESK_ISSUER).claim("userRowId", 100L)
                .expiration(new Date(System.currentTimeMillis() + 600_000L))
                .signWith(signingKey).compact();
        assertThat(sut.isTokenValid(noTyp)).isFalse();

        String refresh = Jwts.builder()
                .subject("u1").issuer(JwtTokenProvider.DESK_ISSUER).claim("typ", "refresh")
                .claim("userRowId", 100L)
                .expiration(new Date(System.currentTimeMillis() + 600_000L))
                .signWith(signingKey).compact();
        assertThat(sut.isTokenValid(refresh)).isFalse();
    }

    /** 배포 전 이 서버가 발급한 토큰 모양 — typ 은 있고 iss 는 없다. */
    private String preDeployToken(Date exp) {
        return Jwts.builder()
                .subject("u1").id("sid-old")
                .claim("userName", "User").claim("userEmail", "u@e.com")
                .claim("userRowId", 100L).claim("typ", "access")
                .issuedAt(new Date())
                .expiration(exp)
                .signWith(signingKey).compact();
    }

    @Test
    @DisplayName("전환: 배포 전 토큰(iss 없음)은 유예 기한까지 받는다 — 로그인 유지·무음 재인증 그대로")
    void issuerlessLegacyToken_acceptedUntilCutoff() {
        props.setLegacyIssuerlessAcceptedUntil(Instant.now().plusSeconds(3600));

        String live = preDeployToken(new Date(System.currentTimeMillis() + 600_000L));
        assertThat(sut.isTokenValid(live)).isTrue();
        assertThat(sut.validateAndGetClaims(live).tokenType()).isEqualTo("access");

        String expired = preDeployToken(new Date(System.currentTimeMillis() - 60_000L));
        JwtClaimsPrincipal p = sut.parseExpiredClaims(expired);
        assertThat(p).isNotNull();
        assertThat(p.sessionId()).isEqualTo("sid-old");
    }

    @Test
    @DisplayName("전환: 유예 기한이 지나면 iss 없는 토큰도 거절한다")
    void issuerlessLegacyToken_rejectedAfterCutoff() {
        props.setLegacyIssuerlessAcceptedUntil(Instant.now().minusSeconds(1));

        assertThat(sut.isTokenValid(preDeployToken(new Date(System.currentTimeMillis() + 600_000L)))).isFalse();
        assertThat(sut.parseExpiredClaims(preDeployToken(new Date(System.currentTimeMillis() - 60_000L)))).isNull();
    }

    // ── SSO 로그인 번호(sso_sid) — 보안 점검 CLI-6 ─────────────────────────
    //
    // 로그아웃이 "어느 SSO 로그인을 담다에서 로그아웃했는지" 를 이 값으로 기억한다. 토큰에 안 실리거나
    // 연장 때 떨어지면 로그아웃이 아무것도 기억하지 못해, 비밀번호 없이 다시 들어오는 길이 열린다.

    @Test
    @DisplayName("CLI-6: SSO 로그인 번호를 sso_sid 클레임으로 싣고, 검증하면 그대로 돌아온다")
    void accessToken_carriesSsoSid() {
        String token = sut.createAccessToken("u1", "User", "u@e.com", 100L, "sid-1", "sso-login-1");

        // 클레임 이름은 고정이다 — 이미 발급된 토큰이 이 이름으로 돌아다닌다
        assertThat(parse(token).get("sso_sid", String.class)).isEqualTo("sso-login-1");
        JwtClaimsPrincipal claims = sut.validateAndGetClaims(token);
        assertThat(claims.ssoSid()).isEqualTo("sso-login-1");
        assertThat(claims.hasSsoSid()).isTrue();
        assertThat(claims.sessionId()).isEqualTo("sid-1");
    }

    @Test
    @DisplayName("CLI-6: SSO 로그인 번호가 없으면(옛 SSO 토큰) 클레임 자체를 싣지 않는다")
    void accessToken_withoutSsoSid_omitsClaim() {
        for (String none : new String[] {null, "", " "}) {
            String token = sut.createAccessToken("u1", "User", "u@e.com", 100L, "sid-1", none);

            assertThat(parse(token)).doesNotContainKey("sso_sid");
            JwtClaimsPrincipal claims = sut.validateAndGetClaims(token);
            assertThat(claims.ssoSid()).isNull();
            assertThat(claims.hasSsoSid()).isFalse();
        }
    }

    @Test
    @DisplayName("CLI-6: 만료된 토큰에서도 sso_sid 가 읽힌다 — 조용한 연장이 이 값을 새 토큰으로 옮긴다")
    void expiredToken_keepsSsoSid() {
        props.setAccessTokenExpiration(-60_000L); // 발급하자마자 만료된 토큰
        String expired = sut.createAccessToken("u1", "User", "u@e.com", 100L, "sid-1", "sso-login-1");

        JwtClaimsPrincipal claims = sut.parseExpiredClaims(expired);

        assertThat(claims).isNotNull();
        assertThat(claims.ssoSid()).isEqualTo("sso-login-1");

        // 연장 — 만료 토큰의 값을 그대로 넘겨 다시 발급하면 같은 번호가 이어진다
        props.setAccessTokenExpiration(ACCESS_EXP_MS);
        String renewed = sut.createAccessToken(claims.userId(), claims.userName(), claims.userEmail(),
                claims.userRowId(), claims.sessionId(), claims.ssoSid());
        assertThat(sut.validateAndGetClaims(renewed).ssoSid()).isEqualTo("sso-login-1");
    }

    @Test
    @DisplayName("CLI-6: 임베드 토큰에는 sso_sid 가 없다 — 로그아웃에 쓰이는 토큰이 아니다")
    void embedToken_hasNoSsoSid() {
        String token = sut.createEmbedToken("u1", "User", "u@e.com", 100L, "sid-parent");

        assertThat(parse(token)).doesNotContainKey("sso_sid");
        assertThat(sut.validateAndGetClaims(token).hasSsoSid()).isFalse();
    }

    private Claims parse(String token) {
        return Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
    }
}
