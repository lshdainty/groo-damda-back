package io.groo.damda.security.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.core.exception.ForbiddenException;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.common.config.properties.JwtProperties;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.controller.dto.TokenExchangeDto;
import io.groo.damda.security.jwt.JwtTokenProvider;
import io.groo.damda.security.principal.JwtUserPrincipal;
import io.groo.damda.security.principal.UserPrincipal;
import io.groo.damda.security.service.TokenExchangeService;
import io.groo.damda.security.session.service.SsoSessionService;
import io.groo.damda.security.session.store.SsoLoginLogoutStore;
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
    private final SsoLoginLogoutStore ssoLoginLogoutStore;

    public static final String ACCESS_TOKEN_COOKIE = "desk_access_token";

    /**
     * OAuth2 Authorization Code 교환 — 인가코드(code)+PKCE code_verifier 를 SSO 에 교환해
     * 자체 desk JWT 를 발급(httpOnly 쿠키). SSO 로그인 완료 후 desk 세션 진입점.
     *
     * <p>토큰은 <b>쿠키로만</b> 준다 — 본문에는 사용자 정보만 싣는다(보안 점검 N-4, {@link TokenExchangeDto.LoginResponse}).
     */
    @PostMapping("/exchange-code")
    public ApiResponse<TokenExchangeDto.LoginResponse> exchangeCode(
            @RequestBody TokenExchangeDto.CodeRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        TokenExchangeDto.Response exchangeResponse = tokenExchangeService.exchangeCode(
                request.code(), request.codeVerifier(), request.redirectUri(),
                httpRequest.getHeader(HttpHeaders.USER_AGENT));
        setAccessTokenCookie(response, exchangeResponse.accessToken());
        return ApiResponse.success(TokenExchangeDto.LoginResponse.from(exchangeResponse));
    }

    /**
     * 로그아웃 — <b>담다만</b> 로그아웃한다. 쿠키를 지우고 이 기기의 담다 세션(저장해 둔 SSO refresh 포함)을 끊는다.
     *
     * <p>세션을 남겨 두면 refresh token 이 최대 7일 더 살아 있다. 로그아웃한 사용자의 재발급
     * 수단이 서버에 남는 셈이라 반드시 같이 끊는다. 다른 기기 세션은 건드리지 않는다.
     *
     * <p><b>그루 계정(SSO) 로그인은 끊지 않는다</b>(보안 점검 CLI-6, 사용자 결정) — 서비스에서 로그아웃하면
     * 그 서비스만 끝난다. 예전엔 SSO 에 알려 그 로그인 계열을 통째로 폐기해서, 담다에서 로그아웃하면 같은
     * 브라우저의 이음까지 끊겼다. 대신 이 세션을 만든 SSO 로그인을 기억해 두고, 같은 로그인으로 돌아오는
     * 교환을 거절한다({@code AUTH_030}) — SSO 로그인이 살아 있어도 비밀번호 없이는 담다에 다시 못 들어온다.
     * 기억하지 못해도(Redis 장애) 로그아웃은 끝난다.
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof JwtUserPrincipal principal) {
            if (principal.getSessionId() != null) {
                ssoSessionService.revoke(principal.getSessionId());
            }
            // 세션을 끊은 뒤에 기억한다 — 끊다가 실패했는데 기록만 남으면, 살아 있는 세션을 두고 새 로그인만 막힌다.
            // sso_sid 가 없는 토큰(SSO 가 sid 를 싣기 전 로그인)은 기억할 것이 없다.
            if (principal.getClaims().hasSsoSid()) {
                ssoLoginLogoutStore.markLoggedOut(principal.getSsoSid());
            }
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
     * 발급된 토큰은 Authorization: Bearer 로 사용하며, JwtAuthenticationFilter 는 typ=embed 토큰을
     * 차트 조회 경로에서만 인증하고 쿠키 갱신·무음 재인증을 하지 않는다.
     *
     * <p><b>로그인 세션(typ=access)으로만 부를 수 있다</b>(보안 점검 S-3). 예전엔 임베드 토큰으로도 이 경로를
     * 불러 새 임베드 토큰을 받을 수 있어, 한 번 샌 토큰이 60초마다 스스로 연장됐다. 지금은 임베드 토큰이
     * 이 경로에서 인증 자체가 안 되지만(필터의 경로 제한), 그 제한이 풀려도 여기서 한 번 더 막는다.
     * 새 토큰에는 부모 세션(jti)을 실어 로그아웃하면 같이 죽게 한다.
     */
    @PostMapping("/embed-token")
    public ApiResponse<TokenExchangeDto.EmbedTokenResponse> issueEmbedToken(@LoginUser UserPrincipal loginUser) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof JwtUserPrincipal principal)
                || !"access".equals(principal.getClaims().tokenType())) {
            throw new ForbiddenException(DeskErrorCode.AUTH_ACCESS_DENIED);
        }
        String token = jwtTokenProvider.createEmbedToken(
            loginUser.getUserId(),
            loginUser.getUserName(),
            loginUser.getUserEmail(),
            loginUser.getRowId(),
            principal.getSessionId()
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
