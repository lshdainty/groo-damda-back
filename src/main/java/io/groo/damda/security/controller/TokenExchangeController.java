package io.groo.damda.security.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.common.config.properties.JwtProperties;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.controller.dto.TokenExchangeDto;
import io.groo.damda.security.jwt.JwtTokenProvider;
import io.groo.damda.security.principal.JwtUserPrincipal;
import io.groo.damda.security.principal.UserPrincipal;
import io.groo.damda.security.service.TokenExchangeService;
import io.groo.damda.security.session.service.SsoSessionService;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class TokenExchangeController {
    private final TokenExchangeService tokenExchangeService;
    private final JwtProperties jwtProperties;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final SsoSessionService ssoSessionService;

    public static final String ACCESS_TOKEN_COOKIE = "desk_access_token";

    /**
     * OAuth2 Authorization Code 교환 — 인가코드(code)+PKCE code_verifier 를 SSO 에 교환해
     * 자체 desk JWT 를 발급(httpOnly 쿠키). SSO 로그인 완료 후 desk 세션 진입점.
     */
    @PostMapping("/exchange-code")
    public ApiResponse<TokenExchangeDto.Response> exchangeCode(
            @RequestBody TokenExchangeDto.CodeRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        TokenExchangeDto.Response exchangeResponse = tokenExchangeService.exchangeCode(
                request.code(), request.codeVerifier(), request.redirectUri(),
                httpRequest.getHeader(HttpHeaders.USER_AGENT));
        setAccessTokenCookie(response, exchangeResponse.accessToken());
        return ApiResponse.success(exchangeResponse);
    }

    /**
     * 로그아웃 — 쿠키를 지우고 이 기기의 SSO 세션도 끊는다.
     *
     * <p>세션을 남겨 두면 refresh token 이 최대 7일 더 살아 있다. 로그아웃한 사용자의 재발급
     * 수단이 서버에 남는 셈이라 반드시 같이 끊는다. 다른 기기 세션은 건드리지 않는다.
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof JwtUserPrincipal principal
                && principal.getSessionId() != null) {
            ssoSessionService.revoke(principal.getSessionId());
        }
        clearAccessTokenCookie(response);
        return ApiResponse.success(null);
    }

    @GetMapping("/check")
    public ApiResponse<TokenExchangeDto.CheckResponse> checkLogin(@LoginUser UserPrincipal loginUser) {
        // 가입일시(User.createAt) 노출 — PK 단건 조회. 유저 미존재/미조회 시 null.
        LocalDateTime joinedAt = userRepository.findById(loginUser.getRowId())
            .map(User::getCreateAt)
            .orElse(null);
        return ApiResponse.success(new TokenExchangeDto.CheckResponse(
            loginUser.getRowId(),
            loginUser.getUserId(),
            loginUser.getUserName(),
            loginUser.getUserEmail(),
            "Asia/Seoul",
            joinedAt
        ));
    }

    /**
     * 임베드(차트 WebView 등) 단명 컨텍스트용 60초 토큰 발급.
     * 인증된 사용자만 호출 가능. 발급된 토큰은 Authorization: Bearer 로 사용하며,
     * JwtAuthenticationFilter 는 typ=embed 토큰의 쿠키 갱신을 skip 한다.
     */
    @PostMapping("/embed-token")
    public ApiResponse<TokenExchangeDto.EmbedTokenResponse> issueEmbedToken(@LoginUser UserPrincipal loginUser) {
        // 임베드 토큰은 세션에 속하지 않는다(60초 단명). jti 없이 만들어 재발급 대상에서 빠진다.
        String token = jwtTokenProvider.createEmbedToken(
            loginUser.getUserId(),
            loginUser.getUserName(),
            loginUser.getUserEmail(),
            loginUser.getRowId()
        );
        return ApiResponse.success(new TokenExchangeDto.EmbedTokenResponse(
            token, JwtTokenProvider.EMBED_TOKEN_EXPIRATION_MS / 1000
        ));
    }

    private void setAccessTokenCookie(HttpServletResponse response, String accessToken) {
        ResponseCookie cookie = ResponseCookie.from(ACCESS_TOKEN_COOKIE, accessToken)
                .httpOnly(true)
                .secure(true)
                .path("/")
                // 토큰 수명이 아니라 세션 수명으로 둔다. 토큰과 같이 1시간 만에 사라지면
                // 만료된 토큰이 서버에 도달조차 못 해 재발급할 세션을 찾을 수 없다.
                .maxAge(jwtProperties.getSessionExpiration() / 1000)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearAccessTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(ACCESS_TOKEN_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
