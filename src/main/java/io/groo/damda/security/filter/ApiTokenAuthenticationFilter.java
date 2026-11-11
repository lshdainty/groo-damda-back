package io.groo.damda.security.filter;

import io.groo.damda.apitoken.service.ApiTokenRateLimiter;
import io.groo.damda.apitoken.service.ApiTokenService;
import io.groo.damda.apitoken.service.dto.ApiTokenServiceDto.Caller;
import io.groo.damda.apitoken.type.ApiTokenFormat;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.core.security.web.SecurityErrorResponder;
import io.groo.damda.security.principal.JwtClaimsPrincipal;
import io.groo.damda.security.principal.JwtUserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

/**
 * 프로그램용 API 토큰({@code Authorization: Bearer pdk_…}) 인증.
 *
 * <p>desk 는 사용자마다 <b>자기 증권사 키로 자기 데이터</b>를 대신 불러 준다. 브라우저·앱은
 * 로그인(JWT)으로 "누구인지" 를 밝히는데, 사용자가 만든 프로그램은 그 로그인을 할 수 없다.
 * 이 토큰이 그 자리를 대신한다 — 토큰 주인으로 로그인한 것과 같은 사용자를 세우므로
 * 뒤의 {@code @LoginUser} · 구독 게이트({@code FeatureGateInterceptor})가 그대로 돈다.
 *
 * <p><b>열리는 자리는 {@link #ALLOWED_PATHS} 셋뿐</b>(GET). 가계부·설정·키 등록·토큰 발급은
 * 토큰으로 못 부른다. 와일드카드를 쓰지 않는 이유 — {@code /api/v1/securities/} 아래에 잔고
 * 같은 경로가 새로 생겨도 저절로 열리면 안 된다. 여는 것은 여기 한 줄을 더하는 결정이어야 한다.
 *
 * <p>순서: 범위 → 토큰 → 호출 제한. 범위를 먼저 보는 이유는 열리지 않는 자리에서 DB 를 보지
 * 않기 위해서다(그래서 가짜 토큰으로 막힌 자리를 부르면 401 이 아니라 403 이 나간다).
 * 막을 때는 컨트롤러와 같은 봉투로 답한다({@link SecurityErrorResponder}).
 *
 * <p>토큰 원문은 로그에 남기지 않는다. 요청 로그의 {@code Authorization} 은 core 마스커가 가린다.
 *
 * <p><b>빈이 아니다</b> — {@code SecurityConfig} 가 직접 만들어 체인에 끼운다.
 * {@code @Component} 로 두면 부트가 서블릿 필터로 한 번 더 등록하고, {@code @WebMvcTest} 슬라이스가
 * 전부 이 필터를 끌어들여 (슬라이스에 없는) 의존 빈을 찾다 깨진다. JWT 필터는 그래서 슬라이스 40곳이
 * 하나하나 제외 설정을 달고 있다 — 같은 짐을 늘리지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

    static final Set<String> ALLOWED_PATHS = Set.of(
        "/api/v1/securities/prices",
        "/api/v1/securities/exchange-rate",
        "/api/v1/securities/candles"
    );

    /** {@link JwtClaimsPrincipal#tokenType()} 에 싣는 값. */
    public static final String TOKEN_TYPE = "api";

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 지연 조회 — 시큐리티 체인은 JPA 보다 먼저 만들어질 수 있다. 서비스를 직접 받으면 그 시점에
     * 리포지토리 → EntityManager 가 끌려 들어온다({@link JwtAuthenticationFilter} 의 같은 필드 주석
     * 참고). 첫 API 토큰 요청이 올 때 꺼내면 컨텍스트가 이미 다 올라와 있다.
     */
    private final ObjectProvider<ApiTokenService> apiTokenServiceProvider;
    private final ApiTokenRateLimiter rateLimiter;
    private final SecurityErrorResponder errorResponder;

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveApiToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!isAllowed(request)) {
            log.warn("API 토큰 거절 - 열리지 않는 자리: {} {}", request.getMethod(), request.getRequestURI());
            errorResponder.write(request, response, DeskErrorCode.API_TOKEN_SCOPE_DENIED);
            return;
        }

        Optional<Caller> caller = apiTokenServiceProvider.getObject().authenticate(token);
        if (caller.isEmpty()) {
            log.warn("API 토큰 거절 - 없거나 폐기됨: {} {}", request.getMethod(), request.getRequestURI());
            errorResponder.write(request, response, DeskErrorCode.API_TOKEN_INVALID);
            return;
        }

        if (!rateLimiter.tryAcquire(caller.get().tokenRowId())) {
            log.warn("API 토큰 호출 제한 초과. tokenRowId={}, userRowId={}",
                caller.get().tokenRowId(), caller.get().userRowId());
            errorResponder.write(request, response, DeskErrorCode.API_TOKEN_RATE_LIMITED);
            return;
        }

        authenticate(caller.get());
        filterChain.doFilter(request, response);
    }

    /** 이 요청이 API 토큰으로 들어왔는지. JWT 필터가 이 요청을 건너뛸 때 쓴다. */
    public static boolean isApiTokenRequest(HttpServletRequest request) {
        return resolveApiToken(request) != null;
    }

    private static String resolveApiToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String value = header.substring(BEARER_PREFIX.length()).trim();
        return ApiTokenFormat.looksLike(value) ? value : null;
    }

    private static boolean isAllowed(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return ALLOWED_PATHS.contains(path);
    }

    private void authenticate(Caller caller) {
        JwtClaimsPrincipal claims = new JwtClaimsPrincipal(caller.userId(), caller.userName(),
            caller.userEmail(), caller.userRowId(), TOKEN_TYPE, null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new JwtUserPrincipal(claims), null, Collections.emptyList()));
    }
}
