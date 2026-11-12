package io.groo.damda.user.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import io.groo.damda.user.controller.dto.OAuthLinkDto;
import io.groo.damda.user.service.OAuthLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 소셜 계정 연동(link) 프록시 컨트롤러 (BFF).
 *
 * <p>desk 쿠키 인증(@LoginUser) 필수 — {@code /api/v1/oauth/**} 는 permitAll 이 아니므로 자동 authenticated.
 * desk-front 요청을 서비스 토큰으로 SSO 에 relay 한다.
 */
@RestController
@RequestMapping("/api/v1/oauth")
@RequiredArgsConstructor
public class OAuthLinkApiController {

    private final OAuthLinkService oAuthLinkService;

    /**
     * 소셜 계정 연동 시작 — 브라우저가 이동할 SSO 연동 시작 URL 반환.
     *
     * <p>{@code X-Reauth-Token}(재인증 티켓, purpose=oauth-link)을 그대로 SSO 에 넘긴다(보안 점검 S-2).
     * 검증·단회 처리는 SSO 가 한다 — 여기서 먼저 소모하면 SSO 에 닿기 전에 티켓이 타 버린다. SSO 가 직접
     * 막으므로 담다를 거치지 않는 호출도 같이 막힌다.
     */
    @PostMapping("/link/{provider}")
    public ApiResponse<OAuthLinkDto.StartUrlResp> startLink(
            @LoginUser UserPrincipal loginUser,
            @PathVariable String provider,
            @RequestHeader(value = "X-Reauth-Token", required = false) String reauthToken,
            @RequestBody(required = false) OAuthLinkDto.LinkStartReq request) {
        String startUrl = oAuthLinkService.startLink(
                loginUser.getUserId(),
                provider,
                request == null ? null : request.getReturnUrl(),
                reauthToken
        );
        return ApiResponse.success(OAuthLinkDto.StartUrlResp.builder().startUrl(startUrl).build());
    }

    /** 연동 가능한 제공자 목록과 사용자별 연동 상태 조회. */
    @GetMapping("/providers")
    public ApiResponse<List<OAuthLinkDto.ProviderInfoResp>> getProviders(
            @LoginUser UserPrincipal loginUser) {
        return ApiResponse.success(oAuthLinkService.getProviders(loginUser.getUserId()));
    }

    /**
     * 소셜 계정 연동 해제.
     *
     * <p>연동 시작과 같이 {@code X-Reauth-Token}(재인증 티켓, purpose=oauth-link)을 그대로 SSO 에 넘긴다
     * (보안 점검 S-2 잔여). 로그인 토큰 · 쿠키를 잠깐 쥔 사람이 본인의 구글 연결을 끊어 복구 수단을 없애지 못하게 한다.
     */
    @DeleteMapping("/link/{provider}")
    public ApiResponse<Void> unlink(
            @LoginUser UserPrincipal loginUser,
            @PathVariable String provider,
            @RequestHeader(value = "X-Reauth-Token", required = false) String reauthToken) {
        oAuthLinkService.unlink(loginUser.getUserId(), provider, reauthToken);
        return ApiResponse.success();
    }
}
