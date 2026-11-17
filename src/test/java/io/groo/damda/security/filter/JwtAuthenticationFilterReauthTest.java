package io.groo.damda.security.filter;

import io.groo.damda.common.config.properties.JwtProperties;
import io.groo.damda.security.controller.TokenExchangeController;
import io.groo.damda.security.jwt.JwtTokenProvider;
import io.groo.damda.security.principal.JwtUserPrincipal;
import io.groo.damda.security.service.TokenExchangeService;
import io.groo.damda.security.session.store.InMemorySessionRevocationStore;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 만료된 토큰이 필터에서 조용히 되살아나는지 — 사용자가 말한 "인증 풀리면 알아서 재인증" 자리.
 *
 * <p>인증 해제를 여기 한 곳에서만 처리하므로, 여기가 틀리면 전 API 가 같이 틀린다.
 */
class JwtAuthenticationFilterReauthTest {

    private static final String SECRET = "test-secret-key-must-be-long-enough-for-hs256-aaaaaaaa";
    private static final long ACCESS_EXP_MS = 3_600_000L;
    private static final long SESSION_EXP_MS = 604_800_000L;

    private JwtTokenProvider jwtTokenProvider;
    private TokenExchangeService tokenExchangeService;
    private JwtAuthenticationFilter sut;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        props.setAccessTokenExpiration(ACCESS_EXP_MS);
        props.setSessionExpiration(SESSION_EXP_MS);

        jwtTokenProvider = new JwtTokenProvider(props, null);
        tokenExchangeService = mock(TokenExchangeService.class);
        // 운영에서는 ObjectProvider 로 지연 조회한다 — 직접 주입하면 톰캣이 필터 빈을 모으는
        // 시점에 JPA 가 통째로 끌려 들어와 기동이 깨진다(ApplicationContextLoadTest 참고).
        @SuppressWarnings("unchecked")
        ObjectProvider<TokenExchangeService> provider = mock(ObjectProvider.class);
        given(provider.getObject()).willReturn(tokenExchangeService);
        // 폐기 표식은 비워 둔다 — 이 테스트가 보는 건 재인증 경로다.
        // 폐기된 세션의 동작은 JwtAuthenticationFilterRevocationTest 가 본다.
        sut = new JwtAuthenticationFilter(
                jwtTokenProvider, props, provider, new InMemorySessionRevocationStore());
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 이미 만료된 access 토큰 — 서명은 정상. */
    private String expiredToken(String sessionId) {
        Date issued = new Date(System.currentTimeMillis() - 7_200_000L);
        Date expiry = new Date(System.currentTimeMillis() - 3_600_000L);
        var builder = Jwts.builder()
                .issuer(io.groo.damda.security.jwt.JwtTokenProvider.DESK_ISSUER)
                .subject("tester")
                .claim("userName", "테스터")
                .claim("userEmail", "tester@porest.com")
                .claim("userRowId", 7L)
                .claim("typ", "access")
                .issuedAt(issued)
                .expiration(expiry);
        if (sessionId != null) {
            builder.id(sessionId);
        }
        return builder.signWith(signingKey).compact();
    }

    private MockHttpServletResponse run(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(TokenExchangeController.ACCESS_TOKEN_COOKIE, token));
        MockHttpServletResponse response = new MockHttpServletResponse();
        sut.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static String setCookie(MockHttpServletResponse response) {
        return response.getHeader(HttpHeaders.SET_COOKIE);
    }

    @Test
    @DisplayName("만료된 토큰 — 재발급되면 인증된 채로 요청이 진행되고 쿠키가 갱신된다")
    void expiredToken_reauthenticated() throws Exception {
        String newToken = jwtTokenProvider.createAccessToken("tester", "테스터", "tester@porest.com", 7L, "sid-1", null);
        given(tokenExchangeService.reauthenticate(any())).willReturn(newToken);

        MockHttpServletResponse response = run(expiredToken("sid-1"));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(((JwtUserPrincipal) auth.getPrincipal()).getUserRowId()).isEqualTo(7L);
        // 새 토큰이 쿠키로 안 나가면 다음 요청이 또 만료 토큰을 들고 와 매번 재발급한다
        assertThat(setCookie(response)).contains(newToken);
    }

    @Test
    @DisplayName("재발급된 토큰은 같은 세션을 유지한다 — 매번 새 세션이면 기기 관리가 무너진다")
    void reauthenticated_keepsSessionId() throws Exception {
        String newToken = jwtTokenProvider.createAccessToken("tester", "테스터", "tester@porest.com", 7L, "sid-1", null);
        given(tokenExchangeService.reauthenticate(any())).willReturn(newToken);

        run(expiredToken("sid-1"));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(((JwtUserPrincipal) auth.getPrincipal()).getSessionId()).isEqualTo("sid-1");
    }

    @Test
    @DisplayName("재발급 불가 — 인증 없이 넘긴다(401 로 떨어지게)")
    void reauthFailed_leavesUnauthenticated() throws Exception {
        given(tokenExchangeService.reauthenticate(any())).willReturn(null);

        MockHttpServletResponse response = run(expiredToken("sid-1"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(setCookie(response)).isNull();
    }

    @Test
    @DisplayName("재발급 중 예외가 나도 요청을 500 으로 만들지 않는다")
    void reauthThrows_doesNotBreakRequest() throws Exception {
        given(tokenExchangeService.reauthenticate(any())).willThrow(new RuntimeException("boom"));

        MockHttpServletResponse response = run(expiredToken("sid-1"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(200); // 체인은 그대로 흘러갔다
    }

    @Test
    @DisplayName("jti 없는 만료 토큰(세션 도입 前 발급분)은 재발급을 시도하지 않는다")
    void expiredLegacyToken_noSession_skipsReauth() throws Exception {
        run(expiredToken(null));

        verify(tokenExchangeService, never()).reauthenticate(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("서명이 다른 토큰은 만료여도 재발급 대상이 아니다 — 위조로 세션을 살릴 수 없어야 한다")
    void forgedToken_skipsReauth() throws Exception {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "another-secret-key-long-enough-for-hs256-bbbbbbbbbb".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .issuer(io.groo.damda.security.jwt.JwtTokenProvider.DESK_ISSUER)
                .subject("attacker")
                .id("sid-1")
                .claim("userRowId", 7L)
                .claim("typ", "access")
                .expiration(new Date(System.currentTimeMillis() - 1000))
                .signWith(otherKey)
                .compact();

        run(forged);

        verify(tokenExchangeService, never()).reauthenticate(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("만료된 임베드 토큰은 재발급하지 않는다 — 단명 컨텍스트가 세션으로 승격되면 안 된다")
    void expiredEmbedToken_skipsReauth() throws Exception {
        String embed = Jwts.builder()
                .issuer(io.groo.damda.security.jwt.JwtTokenProvider.DESK_ISSUER)
                .subject("tester")
                .id("sid-1") // jti 가 있어도 typ=embed 면 대상 아님
                .claim("userRowId", 7L)
                .claim("typ", "embed")
                .expiration(new Date(System.currentTimeMillis() - 1000))
                .signWith(signingKey)
                .compact();

        run(embed);

        verify(tokenExchangeService, never()).reauthenticate(any());
    }

    @Test
    @DisplayName("아직 안 만료된 토큰은 재발급 경로를 타지 않는다")
    void validToken_skipsReauth() throws Exception {
        String valid = jwtTokenProvider.createAccessToken("tester", "테스터", "tester@porest.com", 7L, "sid-1", null);

        run(valid);

        verify(tokenExchangeService, never()).reauthenticate(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("쿠키 수명은 토큰이 아니라 세션 수명 — 짧으면 만료 토큰이 서버에 도달조차 못 한다")
    void renewedCookie_usesSessionLifetime() throws Exception {
        String newToken = jwtTokenProvider.createAccessToken("tester", "테스터", "tester@porest.com", 7L, "sid-1", null);
        given(tokenExchangeService.reauthenticate(any())).willReturn(newToken);

        MockHttpServletResponse response = run(expiredToken("sid-1"));

        assertThat(setCookie(response)).contains("Max-Age=" + (SESSION_EXP_MS / 1000));
    }
}
