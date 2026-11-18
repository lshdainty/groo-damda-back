package io.groo.damda.security.filter;

import io.groo.damda.common.config.properties.JwtProperties;
import io.groo.damda.security.controller.TokenExchangeController;
import io.groo.damda.security.jwt.JwtTokenProvider;
import io.groo.damda.security.principal.JwtUserPrincipal;
import io.groo.damda.security.service.TokenExchangeService;
import io.groo.damda.security.session.store.SessionRevocationStore;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * JWT 필터는 프로그램용 API 토큰 요청을 <b>건너뛴다</b>.
 *
 * <p>같은 {@code Authorization: Bearer} 자리에 오지만 JWT 가 아니다. 읽으려 들면 요청마다 경고가
 * 남고, 쿠키까지 실려 왔다면 그 쿠키 주인으로 인증을 덮어써 "토큰 주인" 이 아닌 사람으로 조회한다.
 */
class JwtAuthenticationFilterApiTokenTest {

    private static final String SECRET = "test-secret-key-must-be-long-enough-for-hs256-aaaaaaaa";

    private JwtTokenProvider jwtTokenProvider;
    private TokenExchangeService tokenExchangeService;
    private SessionRevocationStore revocationStore;
    private JwtAuthenticationFilter sut;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        props.setAccessTokenExpiration(3_600_000L);
        props.setSessionExpiration(604_800_000L);

        jwtTokenProvider = new JwtTokenProvider(props, null);
        tokenExchangeService = mock(TokenExchangeService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TokenExchangeService> provider = mock(ObjectProvider.class);
        revocationStore = mock(SessionRevocationStore.class);
        sut = new JwtAuthenticationFilter(jwtTokenProvider, props, provider, revocationStore);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private String cookieJwt() {
        return jwtTokenProvider.createAccessToken(
            "cookie-user", "쿠키", "cookie@hangroo.io", 99L, UUID.randomUUID().toString(), null);
    }

    @Test
    @DisplayName("API 토큰 요청은 쿠키가 같이 와도 그 쿠키로 인증하지 않는다")
    void apiTokenRequestIsNotAuthenticatedByCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/securities/candles");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer pdk_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG");
        request.setCookies(new Cookie(TokenExchangeController.ACCESS_TOKEN_COOKIE, cookieJwt()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        sut.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("뒤로는 넘긴다").isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        verifyNoInteractions(revocationStore, tokenExchangeService);
    }

    @Test
    @DisplayName("같은 쿠키라도 API 토큰이 없으면 평소대로 인증한다 — 건너뛰는 건 API 토큰 요청뿐이다")
    void cookieStillWorksWithoutApiToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/expenses");
        request.setCookies(new Cookie(TokenExchangeController.ACCESS_TOKEN_COOKIE, cookieJwt()));

        sut.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
            .isInstanceOfSatisfying(JwtUserPrincipal.class, p -> assertThat(p.getUserRowId()).isEqualTo(99L));
    }
}
