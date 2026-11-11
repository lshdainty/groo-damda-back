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

    /** 소셜 계정 연동 시작 — 브라우저가 이동할 SSO 연동 시작 URL 반환. */
    @PostMapping("/link/{provider}")
    public ApiResponse<OAuthLinkDto.StartUrlResp> startLink(
            @LoginUser UserPrincipal loginUser,
            @PathVariable String provider,
            @RequestBody(required = false) OAuthLinkDto.LinkStartReq request) {
        String startUrl = oAuthLinkService.startLink(
                loginUser.getUserId(),
                provider,
                request == null ? null : request.getReturnUrl()
        );
        return ApiResponse.success(OAuthLinkDto.StartUrlResp.builder().startUrl(startUrl).build());
    }

    /** 연동 가능한 제공자 목록과 사용자별 연동 상태 조회. */
    @GetMapping("/providers")
    public ApiResponse<List<OAuthLinkDto.ProviderInfoResp>> getProviders(
            @LoginUser UserPrincipal loginUser) {
        return ApiResponse.success(oAuthLinkService.getProviders(loginUser.getUserId()));
    }

    /** 소셜 계정 연동 해제. */
    @DeleteMapping("/link/{provider}")
    public ApiResponse<Void> unlink(
            @LoginUser UserPrincipal loginUser,
            @PathVariable String provider) {
        oAuthLinkService.unlink(loginUser.getUserId(), provider);
        return ApiResponse.success();
    }
}
