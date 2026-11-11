package io.groo.damda.security.filter;

import io.groo.damda.apitoken.service.ApiTokenRateLimiter;
import io.groo.damda.apitoken.service.ApiTokenService;
import io.groo.damda.apitoken.service.dto.ApiTokenServiceDto.Caller;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.core.security.web.SecurityErrorResponder;
import io.groo.damda.security.principal.JwtUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 프로그램용 API 토큰 필터 — <b>열리는 자리가 셋뿐인지</b>, 막힌 요청이 뒤로 넘어가지 않는지.
 *
 * <p>토큰은 주인의 증권사 키로 가는 길이다. 여기서 범위가 새면 토큰 하나로 가계부·설정까지
 * 열린다. 그래서 통과하는 경우보다 막히는 경우를 더 많이 본다.
 */
class ApiTokenAuthenticationFilterTest {

    private static final String TOKEN = "pdk_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG";
    private static final Caller OWNER = new Caller(11L, 7L, "owner", "주인", "owner@porest.cloud");

    private ApiTokenService apiTokenService;
    private ApiTokenRateLimiter rateLimiter;
    private SecurityErrorResponder errorResponder;
    private ApiTokenAuthenticationFilter sut;

    @BeforeEach
    void setUp() {
        apiTokenService = mock(ApiTokenService.class);
        rateLimiter = mock(ApiTokenRateLimiter.class);
        errorResponder = mock(SecurityErrorResponder.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ApiTokenService> provider = mock(ObjectProvider.class);
        given(provider.getObject()).willReturn(apiTokenService);
        sut = new ApiTokenAuthenticationFilter(provider, rateLimiter, errorResponder);

        given(apiTokenService.authenticate(TOKEN)).willReturn(Optional.of(OWNER));
        given(rateLimiter.tryAcquire(anyLong())).willReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private record Result(MockHttpServletRequest request, MockHttpServletResponse response, MockFilterChain chain) {
        /** 필터가 뒤로 넘겼는지 — MockFilterChain 은 넘겨받은 요청을 기억한다. */
        boolean passedOn() {
            return chain.getRequest() != null;
        }
    }

    private Result run(String method, String path, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (authorization != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        sut.doFilter(request, response, chain);
        return new Result(request, response, chain);
    }

    private Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/securities/prices",
        "/api/v1/securities/exchange-rate",
        "/api/v1/securities/candles"
    })
    @DisplayName("증권 조회 셋은 토큰 주인으로 통과한다")
    void allowedPaths(String path) throws Exception {
        Result result = run("GET", path, "Bearer " + TOKEN);

        assertThat(result.passedOn()).isTrue();
        assertThat(authentication().getPrincipal()).isInstanceOfSatisfying(JwtUserPrincipal.class, p -> {
            assertThat(p.getUserRowId()).isEqualTo(7L);
            assertThat(p.getUserId()).isEqualTo("owner");
            assertThat(p.getUserName()).isEqualTo("주인");
            assertThat(p.getUserEmail()).isEqualTo("owner@porest.cloud");
            assertThat(p.getSessionId()).isNull();
            assertThat(p.getClaims().tokenType()).isEqualTo(ApiTokenAuthenticationFilter.TOKEN_TYPE);
        });
        verifyNoInteractions(errorResponder);
    }

    @ParameterizedTest
    @CsvSource({
        // 가계부·설정·세션 — 토큰으로 열리면 안 되는 자리
        "GET,    /api/v1/expenses",
        "GET,    /api/v1/users/me",
        "GET,    /api/v1/auth/sessions",
        // 토큰으로 토큰을 찍어 내거나 지우는 길
        "POST,   /api/v1/users/me/api-tokens",
        "GET,    /api/v1/users/me/api-tokens",
        "DELETE, /api/v1/users/me/api-tokens/1",
        // 증권사 키
        "GET,    /api/v1/users/me/securities-credentials",
        "POST,   /api/v1/users/me/securities-credentials/TOSS",
        // 증권사별 경로 — 잔고·계좌가 여기 있다
        "GET,    /api/v1/toss/holdings",
        "GET,    /api/v1/toss/accounts",
        "GET,    /api/v1/toss/candles",
        "GET,    /api/v1/namu/holdings",
        // 허용 경로와 닮았지만 다른 것 — 와일드카드였다면 통과했을 자리
        "GET,    /api/v1/securities/holdings",
        "GET,    /api/v1/securities/candles/",
        "GET,    /api/v1/securities/candles/extra",
        "GET,    /api/v1/securities",
        // 로그인 없이 열린 자리도 토큰으로는 안 연다
        "POST,   /api/v1/auth/logout"
    })
    @DisplayName("그 밖의 자리는 403 으로 막고 뒤로 넘기지 않는다 — DB 도 보지 않는다")
    void everythingElseIsDenied(String method, String path) throws Exception {
        Result result = run(method, path, "Bearer " + TOKEN);

        assertThat(result.passedOn()).isFalse();
        assertThat(authentication()).isNull();
        verify(errorResponder).write(any(), any(), eq(DeskErrorCode.API_TOKEN_SCOPE_DENIED));
        verifyNoInteractions(apiTokenService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("허용 경로라도 GET 이 아니면 막는다 — 토큰은 읽기 전용이다")
    void onlyGet(String method) throws Exception {
        Result result = run(method, "/api/v1/securities/candles", "Bearer " + TOKEN);

        assertThat(result.passedOn()).isFalse();
        verify(errorResponder).write(any(), any(), eq(DeskErrorCode.API_TOKEN_SCOPE_DENIED));
    }

    @Test
    @DisplayName("없는·폐기된 토큰은 401 — 인증을 세우지 않고 뒤로 넘기지도 않는다")
    void invalidToken() throws Exception {
        given(apiTokenService.authenticate(anyString())).willReturn(Optional.empty());

        Result result = run("GET", "/api/v1/securities/candles", "Bearer pdk_revoked");

        assertThat(result.passedOn()).isFalse();
        assertThat(authentication()).isNull();
        verify(errorResponder).write(any(), any(), eq(DeskErrorCode.API_TOKEN_INVALID));
        verify(rateLimiter, never()).tryAcquire(anyLong());
    }

    @Test
    @DisplayName("호출 제한을 넘으면 429 — 증권사까지 가지 않는다")
    void rateLimited() throws Exception {
        given(rateLimiter.tryAcquire(11L)).willReturn(false);

        Result result = run("GET", "/api/v1/securities/candles", "Bearer " + TOKEN);

        assertThat(result.passedOn()).isFalse();
        assertThat(authentication()).isNull();
        verify(errorResponder).write(any(), any(), eq(DeskErrorCode.API_TOKEN_RATE_LIMITED));
    }

    @Test
    @DisplayName("호출 제한은 토큰 단위로 센다")
    void rateLimitIsPerToken() throws Exception {
        run("GET", "/api/v1/securities/candles", "Bearer " + TOKEN);

        verify(rateLimiter).tryAcquire(11L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Bearer eyJhbGciOiJIUzI1NiJ9.e30.sig",   // desk JWT — JWT 필터 몫
        "Basic dXNlcjpwYXNz",
        "pdk_without_bearer"
    })
    @DisplayName("API 토큰이 아닌 요청은 손대지 않고 넘긴다 — 브라우저·앱 로그인은 그대로여야 한다")
    void notAnApiTokenRequest(String authorization) throws Exception {
        Result result = run("GET", "/api/v1/expenses", authorization);

        assertThat(result.passedOn()).isTrue();
        assertThat(authentication()).isNull();
        verifyNoInteractions(apiTokenService, errorResponder);
    }

    @Test
    @DisplayName("Authorization 이 없으면 손대지 않고 넘긴다")
    void noHeader() throws Exception {
        Result result = run("GET", "/api/v1/expenses", null);

        assertThat(result.passedOn()).isTrue();
        verifyNoInteractions(apiTokenService, errorResponder);
    }

    @Test
    @DisplayName("쿼리스트링은 경로 판정에 끼지 않는다")
    void queryStringIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/securities/candles");
        request.setQueryString("symbol=005930&interval=1m");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        MockFilterChain chain = new MockFilterChain();

        sut.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("컨텍스트 경로가 붙어도 같은 자리로 본다")
    void contextPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/desk/api/v1/securities/candles");
        request.setContextPath("/desk");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        MockFilterChain chain = new MockFilterChain();

        sut.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("isApiTokenRequest — JWT 필터가 건너뛸 요청을 가른다")
    void isApiTokenRequest() {
        MockHttpServletRequest api = new MockHttpServletRequest();
        api.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        MockHttpServletRequest jwt = new MockHttpServletRequest();
        jwt.addHeader(HttpHeaders.AUTHORIZATION, "Bearer eyJhbGciOiJIUzI1NiJ9.e30.sig");

        assertThat(ApiTokenAuthenticationFilter.isApiTokenRequest(api)).isTrue();
        assertThat(ApiTokenAuthenticationFilter.isApiTokenRequest(jwt)).isFalse();
        assertThat(ApiTokenAuthenticationFilter.isApiTokenRequest(new MockHttpServletRequest())).isFalse();
    }
}
