package io.groo.damda.security.jwt;

import io.groo.damda.common.config.properties.JwtProperties;
import io.groo.damda.security.principal.JwtClaimsPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
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
     * 담다 자체 토큰의 발급자(iss). 이음은 {@code porest-hr} 를 쓴다 — 짝을 맞춘 내부 식별자다(화면에 안 보인다).
     *
     * <p>서명 키(HMAC)는 서비스마다 다르지만, 키 하나만으로 "담다가 발급했다" 를 믿으면 키가 겹치는 순간
     * 남의 토큰이 통과한다(보안 점검 N-2). 그래서 서명에 더해 iss 와 typ 를 본다.
     */
    public static final String DESK_ISSUER = "porest-desk";

    /** 이 서버가 발급하는 토큰 종류. 이 밖의 typ(또는 typ 없음)는 받지 않는다. */
    private static final Set<String> DESK_TOKEN_TYPES = Set.of("access", "embed");

    /**
     * 이 세션을 만든 SSO 로그인 번호를 싣는 클레임 — SSO access token 의 {@code sid} 를 그대로 옮긴 값이다.
     * 로그아웃할 때 이 값을 "담다에서 로그아웃한 SSO 로그인" 으로 기억한다(보안 점검 CLI-6).
     */
    private static final String SSO_SID_CLAIM = "sso_sid";

    /**
     * 로그인 세션 토큰.
     *
     * <p>{@code sessionId} 는 jti 로 들어가 이 토큰이 어느 기기의 세션인지 가리킨다. 만료된
     * 토큰에서도 읽히므로(서명은 그대로 검증된다) 만료 후 조용히 재발급할 때 세션을 찾는
     * 열쇠가 된다. 재발급하더라도 같은 값을 유지해야 세션이 이어진다.
     *
     * <p>{@code ssoSid} 는 이 세션을 만든 SSO 로그인 번호다({@link #SSO_SID_CLAIM}). 있을 때만 싣는다 —
     * SSO 가 sid 를 싣기 전에 발급한 토큰에서 온 세션이면 {@code null} 이다. 재발급 때도 그대로 넘겨야
     * 로그아웃이 어느 SSO 로그인을 기억할지 안다.
     */
    public String createAccessToken(String userId, String userName, String userEmail, Long userRowId,
                                    String sessionId, String ssoSid) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtProperties.getAccessTokenExpiration());

        JwtBuilder builder = Jwts.builder()
            .issuer(DESK_ISSUER)
            .subject(userId)
            .id(sessionId)
            .claim("userName", userName)
            .claim("userEmail", userEmail)
            .claim("userRowId", userRowId)
            .claim("typ", "access");
        if (ssoSid != null && !ssoSid.isBlank()) {
            builder.claim(SSO_SID_CLAIM, ssoSid);
        }
        return builder
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
            .issuer(DESK_ISSUER)
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

        requireDeskToken(claims);
        return toPrincipal(claims);
    }

    /**
     * 서명이 맞은 토큰이 정말 이 서버가 발급한 것인지 본다(보안 점검 N-2). 아니면 {@link JwtException}.
     *
     * <ul>
     *   <li><b>typ</b> 는 {@code access}·{@code embed} 만 받는다. 없으면 거절 — typ 는 2026-06-23 부터 모든 토큰에
     *       실렸고, 그 전 토큰은 세션 절대 수명(30일)이 지나 이미 쓸 수 없다. 이음 토큰은 typ 대신
     *       {@code type=hr_access} 를 싣으므로 여기서 걸린다.</li>
     *   <li><b>iss</b> 는 {@link #DESK_ISSUER} 여야 한다. 다른 값이면 언제나 거절.
     *       <b>없으면</b>(이 변경 전에 발급한 토큰) {@code jwt.legacy-issuerless-accepted-until} 까지만 받는다 —
     *       배포 순간 전원이 로그아웃되지 않게 하는 전환 유예다. 그 사이 쓰인 토큰은 갱신·무음 재인증으로
     *       iss 가 실린 새 토큰으로 바뀐다. 기한은 토큰 값(iat)이 아니라 <b>서버 시각</b>으로 잰다 —
     *       토큰 안의 값은 키를 가진 쪽이 마음대로 적을 수 있어 기준이 못 된다.</li>
     * </ul>
     */
    private void requireDeskToken(Claims claims) {
        String typ = claims.get("typ", String.class);
        if (typ == null || !DESK_TOKEN_TYPES.contains(typ)) {
            throw new JwtException("담다 토큰이 아니다(typ=" + typ + ")");
        }
        String iss = claims.getIssuer();
        if (iss == null) {
            Instant until = jwtProperties.getLegacyIssuerlessAcceptedUntil();
            if (until == null || !Instant.now().isBefore(until)) {
                throw new JwtException("iss 없는 옛 토큰 — 전환 유예가 끝났다");
            }
            return;
        }
        if (!DESK_ISSUER.equals(iss)) {
            throw new JwtException("담다 토큰이 아니다(iss=" + iss + ")");
        }
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
            try {
                requireDeskToken(e.getClaims());
            } catch (JwtException notOurs) {
                // 남의 토큰은 만료됐어도 재발급 대상이 아니다 — 무음 재인증이 세션을 찾으러 가지 않게 여기서 끊는다
                log.warn("JWT token rejected (expired, not a desk token): {}", notOurs.getMessage());
                return null;
            }
            return toPrincipal(e.getClaims());
        } catch (Exception e) {
            log.warn("JWT token invalid (not expired): {}", e.getMessage());
            return null;
        }
    }

    /** {@link #requireDeskToken} 을 통과한 claims 만 받는다 — typ 는 access·embed 중 하나다. */
    private JwtClaimsPrincipal toPrincipal(Claims claims) {
        return new JwtClaimsPrincipal(
            claims.getSubject(),
            claims.get("userName", String.class),
            claims.get("userEmail", String.class),
            claims.get("userRowId", Long.class),
            claims.get("typ", String.class),
            claims.getId(), // jti — 세션 도입 前 토큰에는 없다(null)
            claims.get(SSO_SID_CLAIM, String.class) // SSO 로그인 번호 — 없는 토큰이 있다(null)
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
            Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
            requireDeskToken(claims);
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
