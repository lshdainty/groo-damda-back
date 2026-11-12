package io.groo.damda.security.filter;

import io.groo.damda.common.config.properties.JwtProperties;
import io.groo.damda.security.controller.TokenExchangeController;
import io.groo.damda.security.jwt.JwtTokenProvider;
import io.groo.damda.security.service.TokenExchangeService;
import io.groo.damda.security.session.store.SessionRevocationStore;
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
 * 로그아웃한 세션의 토큰이 정말 막히는지 — QA #44.
 *
 * <p>desk access token 은 무상태 JWT 라 로그아웃해도 서명과 exp 만으로 계속 통과했다. 세션 행을
 * 지우는 것만으로는 <b>이미 발급된 토큰</b>에 아무 영향이 없어서, 복사된 쿠키가 만료까지 살아
 * 있었다. 더 나쁜 것은 갱신 경로다 — 그 쿠키를 만료 전에 한 번이라도 쓰면 세션 확인 없이
 * 재서명돼 수명이 <b>무기한</b> 연장됐다. 두 자리를 같이 못 박는다.
 */
class JwtAuthenticationFilterRevocationTest {

    private static final String SECRET = "test-secret-key-must-be-long-enough-for-hs256-aaaaaaaa";
    private static final long ACCESS_EXP_MS = 3_600_000L;
    private static final long SESSION_EXP_MS = 604_800_000L;
    private static final String SESSION_ID = "sid-1";

    private JwtTokenProvider jwtTokenProvider;
    private TokenExchangeService tokenExchangeService;
    private SessionRevocationStore revocationStore;
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
        @SuppressWarnings("unchecked")
        ObjectProvider<TokenExchangeService> provider = mock(ObjectProvider.class);
        given(provider.getObject()).willReturn(tokenExchangeService);
        revocationStore = mock(SessionRevocationStore.class);
        sut = new JwtAuthenticationFilter(jwtTokenProvider, props, provider, revocationStore);
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse run(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(TokenExchangeController.ACCESS_TOKEN_COOKIE, token));
        MockHttpServletResponse response = new MockHttpServletResponse();
        sut.doFilter(request, response, new MockFilterChain());
        return response;
    }

    /** 아직 안 만료됐지만 남은 수명이 갱신 임계값(10분) 아래인 토큰. */
    private String almostExpiredToken(String sessionId) {
        var builder = Jwts.builder()
                .subject("tester")
                .claim("userName", "테스터")
                .claim("userEmail", "tester@porest.com")
                .claim("userRowId", 7L)
                .claim("typ", "access")
                .issuedAt(new Date(System.currentTimeMillis() - 3_300_000L))
                .expiration(new Date(System.currentTimeMillis() + 300_000L)); // 5분 남음
        if (sessionId != null) {
            builder.id(sessionId);
        }
        return builder.signWith(signingKey).compact();
    }

    private void revoked() {
        given(revocationStore.isRevoked(SESSION_ID)).willReturn(true);
    }

    @Test
    @DisplayName("폐기된 세션의 토큰은 인증되지 않는다 — 로그아웃 즉시 401")
    void revokedSession_isNotAuthenticated() throws Exception {
        revoked();

        run(jwtTokenProvider.createAccessToken("tester", "테스터", "tester@porest.com", 7L, SESSION_ID));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("막더라도 체인은 그대로 흘려보낸다 — 인증이 필요 없는 요청까지 깨면 안 된다")
    void revokedSession_stillPassesTheChain() throws Exception {
        revoked();

        MockHttpServletResponse response =
                run(jwtTokenProvider.createAccessToken("tester", "테스터", "tester@porest.com", 7L, SESSION_ID));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("폐기되지 않은 세션은 그대로 인증된다 — 이 검사가 멀쩡한 로그인을 깨면 안 된다")
    void liveSession_stillAuthenticates() throws Exception {
        given(revocationStore.isRevoked(SESSION_ID)).willReturn(false);

        run(jwtTokenProvider.createAccessToken("tester", "테스터", "tester@porest.com", 7L, SESSION_ID));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("폐기된 세션은 쿠키를 갱신하지 않는다 — 여기가 무기한 연장이 나던 자리다")
    void revokedSession_doesNotRenewCookie() throws Exception {
        revoked();

        MockHttpServletResponse response = run(almostExpiredToken(SESSION_ID));

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("폐기 안 된 세션은 임계값 아래에서 여전히 갱신된다 — 갱신 자체를 죽이지 않았다")
    void liveSession_stillRenewsCookie() throws Exception {
        given(revocationStore.isRevoked(SESSION_ID)).willReturn(false);
        given(tokenExchangeService.reauthenticate(any())).willReturn(
                jwtTokenProvider.createAccessToken("tester", "테스터", "tester@porest.com", 7L, SESSION_ID));

        MockHttpServletResponse response = run(almostExpiredToken(SESSION_ID));

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains(TokenExchangeController.ACCESS_TOKEN_COOKIE);
    }

    @Test
    @DisplayName("만료 전 갱신도 세션을 거친다 — 세션이 죽었거나 절대 수명이 지나 재발급이 거절되면 쿠키를 갱신하지 않는다(보안 점검 DAM-5)")
    void renewalGoesThroughSession_refusedMeansNoCookie() throws Exception {
        given(revocationStore.isRevoked(SESSION_ID)).willReturn(false);
        given(tokenExchangeService.reauthenticate(any())).willReturn(null);

        MockHttpServletResponse response = run(almostExpiredToken(SESSION_ID));

        // 종전엔 claims 만으로 재서명해 세션이 없어도 쿠키가 새로 나갔다.
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        // 남은 몇 분은 지금 토큰으로 그대로 인증된다 — 갱신 실패가 이 요청을 막지는 않는다.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(tokenExchangeService).reauthenticate(any());
    }

    @Test
    @DisplayName("세션 없는 옛 토큰은 만료 전에도 갱신하지 않는다 — 확인할 세션이 없으면 늘려 줄 근거도 없다(보안 점검 DAM-5)")
    void legacyTokenWithoutSession_isNotRenewed() throws Exception {
        MockHttpServletResponse response = run(almostExpiredToken(null));

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        verify(tokenExchangeService, never()).reauthenticate(any());
    }

    @Test
    @DisplayName("jti 없는 옛 토큰은 표식을 조회하지 않는다 — 폐기할 세션이 없다")
    void legacyTokenWithoutSession_skipsLookup() throws Exception {
        run(almostExpiredToken(null));

        verify(revocationStore, never()).isRevoked(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    private MockHttpServletResponse runBearer(String method, String uri, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        sut.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private String embedToken(String sessionId) {
        return jwtTokenProvider.createEmbedToken("tester", "테스터", "tester@porest.com", 7L, sessionId);
    }

    @Test
    @DisplayName("임베드 토큰은 부모 세션이 로그아웃되면 막힌다 — 예전엔 jti 가 없어 폐기 검사를 건너뛰었다(S-3)")
    void embedToken_revokedParentSession_isNotAuthenticated() throws Exception {
        revoked();

        runBearer("GET", "/api/v1/securities/candles", embedToken(SESSION_ID));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("임베드 토큰은 살아 있는 세션이면 차트 조회 3곳(GET)에서 인증된다")
    void embedToken_liveSession_candlePathsAuthenticate() throws Exception {
        given(revocationStore.isRevoked(SESSION_ID)).willReturn(false);

        for (String path : new String[]{"/api/v1/securities/candles", "/api/v1/toss/candles", "/api/v1/namu/candles"}) {
            SecurityContextHolder.clearContext();
            runBearer("GET", path, embedToken(SESSION_ID));
            assertThat(SecurityContextHolder.getContext().getAuthentication()).as(path).isNotNull();
        }
    }

    @Test
    @DisplayName("임베드 토큰은 차트 조회 밖에서는 인증되지 않는다 — 가계부·토큰 재발급 등(S-3)")
    void embedToken_otherPaths_areNotAuthenticated() throws Exception {
        given(revocationStore.isRevoked(SESSION_ID)).willReturn(false);

        String[][] cases = {
                {"POST", "/api/v1/auth/embed-token"},   // 스스로 연장하던 자리
                {"GET", "/api/v1/expenses"},
                {"GET", "/api/v1/auth/check"},
                {"POST", "/api/v1/securities/candles"}, // 경로가 맞아도 GET 만
                {"GET", "/api/v1/securities/candles/extra"},
        };
        for (String[] c : cases) {
            SecurityContextHolder.clearContext();
            MockHttpServletResponse response = runBearer(c[0], c[1], embedToken(SESSION_ID));
            assertThat(SecurityContextHolder.getContext().getAuthentication()).as(c[0] + " " + c[1]).isNull();
            assertThat(response.getStatus()).as("체인은 흘려보낸다").isEqualTo(200);
        }
    }

    @Test
    @DisplayName("세션 없는 옛 부모에서 나온 임베드 토큰은 표식을 조회하지 않는다 — 부모보다 강해지지도 약해지지도 않는다")
    void embedTokenWithoutSession_skipsLookup() throws Exception {
        runBearer("GET", "/api/v1/securities/candles", embedToken(null));

        verify(revocationStore, never()).isRevoked(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("임베드 토큰은 쿠키를 갱신하지 않는다 — access 쿠키로 승격되지 않는다")
    void embedToken_neverRenewsCookie() throws Exception {
        given(revocationStore.isRevoked(SESSION_ID)).willReturn(false);

        MockHttpServletResponse response = runBearer("GET", "/api/v1/securities/candles", embedToken(SESSION_ID));

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    @DisplayName("만료된 토큰의 무음 재인증 경로는 표식을 보지 않는다 — 세션 행(DB)이 더 강한 근거다")
    void expiredToken_reauthPathDoesNotConsultTheStore() throws Exception {
        String expired = Jwts.builder()
                .subject("tester")
                .id(SESSION_ID)
                .claim("userRowId", 7L)
                .claim("typ", "access")
                .expiration(new Date(System.currentTimeMillis() - 1_000L))
                .signWith(signingKey)
                .compact();

        run(expired);

        verify(revocationStore, never()).isRevoked(any());
        // 폐기된 세션이면 SsoSessionService.refresh 가 살아 있는 행을 못 찾아 거절한다.
        verify(tokenExchangeService).reauthenticate(any());
    }
}
