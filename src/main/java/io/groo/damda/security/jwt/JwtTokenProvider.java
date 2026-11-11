package io.groo.damda.security.jwt;

import io.groo.damda.common.config.properties.JwtProperties;
import io.groo.damda.security.principal.JwtClaimsPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {
    private final JwtProperties jwtProperties;
    /** SSO RS256 토큰 검증용 JWKS 공개키 Locator(kid 매칭). */
    private final Locator<Key> ssoSigningKeyLocator;

    /** 임베드 토큰 만료(ms). 60초 — 차트 임베드 등 단명 컨텍스트 전용. */
    public static final long EMBED_TOKEN_EXPIRATION_MS = 60_000L;

    /**
     * 로그인 세션 토큰.
     *
     * <p>{@code sessionId} 는 jti 로 들어가 이 토큰이 어느 기기의 세션인지 가리킨다. 만료된
     * 토큰에서도 읽히므로(서명은 그대로 검증된다) 만료 후 조용히 재발급할 때 세션을 찾는
     * 열쇠가 된다. 재발급하더라도 같은 값을 유지해야 세션이 이어진다.
     */
    public String createAccessToken(String userId, String userName, String userEmail, Long userRowId,
                                    String sessionId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtProperties.getAccessTokenExpiration());

        return Jwts.builder()
            .subject(userId)
            .id(sessionId)
            .claim("userName", userName)
            .claim("userEmail", userEmail)
            .claim("userRowId", userRowId)
            .claim("typ", "access")
            .issuedAt(now)
            .expiration(expiry)
            .signWith(getSigningKey())
            .compact();
    }

    /** 세션 아이디를 새로 뽑는다 — 로그인 1회 = 기기 1대 = 이 값 1개. */
    public String newSessionId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 임베드용 단명(60초) 토큰. 메인 secret 으로 서명되어 JwtAuthenticationFilter 가
     * 그대로 인식한다. claim {@code typ=embed} 로 필터의 쿠키 갱신을 skip 시킨다.
     * 차트 WebView 처럼 외부 컨텍스트로 한 번 노출되는 단명 컨텍스트에 사용.
     *
     * <p>{@code sessionId} 는 발급을 요청한 access 토큰의 세션(jti)을 그대로 싣는다. 그래야 로그아웃·기기
     * 해제로 그 세션이 폐기되면 임베드 토큰도 같이 막힌다(보안 점검 S-3 — 예전엔 jti 가 없어 폐기 검사를
     * 건너뛰었다). 세션이 없는 옛 access 토큰이면 null — 부모보다 강해지지 않는다.
     */
    public String createEmbedToken(String userId, String userName, String userEmail, Long userRowId,
                                   String sessionId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + EMBED_TOKEN_EXPIRATION_MS);

        return Jwts.builder()
            .subject(userId)
            .id(sessionId)
            .claim("userName", userName)
            .claim("userEmail", userEmail)
            .claim("userRowId", userRowId)
            .claim("typ", "embed")
            .issuedAt(now)
            .expiration(expiry)
            .signWith(getSigningKey())
            .compact();
    }

    public JwtClaimsPrincipal validateAndGetClaims(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();

        return toPrincipal(claims);
    }

    /**
     * 만료된 토큰의 claims — 서명은 검증하되 만료만 눈감는다.
     *
     * <p>만료 후 조용히 재발급하려면 그 토큰이 누구 것인지(jti) 알아야 하는데, 정상 파싱은
     * 만료에서 막힌다. jjwt 는 서명을 먼저 검증하고 나서 만료를 보므로 {@code ExpiredJwtException}
     * 이 들고 있는 claims 는 이미 서명이 확인된 값이다 — 위조 토큰으로는 여기 못 들어온다.
     *
     * <p>만료가 아닌 이유(서명 불일치·형식 오류)로 실패하면 {@code null}. 그런 토큰은 재발급
     * 대상이 아니라 그냥 거절 대상이다.
     */
    public JwtClaimsPrincipal parseExpiredClaims(String token) {
        try {
            Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token);
            return null; // 아직 안 만료됨 — 이 경로로 올 토큰이 아니다
        } catch (ExpiredJwtException e) {
            return toPrincipal(e.getClaims());
        } catch (Exception e) {
            log.warn("JWT token invalid (not expired): {}", e.getMessage());
            return null;
        }
    }

    private JwtClaimsPrincipal toPrincipal(Claims claims) {
        String typ = claims.get("typ", String.class);
        return new JwtClaimsPrincipal(
            claims.getSubject(),
            claims.get("userName", String.class),
            claims.get("userEmail", String.class),
            claims.get("userRowId", Long.class),
            typ == null ? "access" : typ, // 기존 토큰 호환(typ 미존재 → access)
            claims.getId() // jti — 세션 도입 前 토큰에는 없다(null)
        );
    }

    /**
     * SSO 토큰(RS256) 검증 — SSO JWKS 의 공개키로 서명 검증 후 claims 반환.
     * (SSO 가 HMAC 공유 secret → RS256 비대칭 서명으로 전환됨. 클라는 공개키 검증만.)
     */
    public Claims validateSsoToken(String ssoToken) {
        return Jwts.parser()
            .keyLocator(ssoSigningKeyLocator)
            .build()
            .parseSignedClaims(ssoToken)
            .getPayload();
    }

    public long getRemainingExpiration(String token) {
        try {
            Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
            return claims.getExpiration().getTime() - System.currentTimeMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean isTokenValid(String token) {
        try {
            Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT token expired: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("JWT token invalid: {}", e.getMessage());
        }
        return false;
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }
}
