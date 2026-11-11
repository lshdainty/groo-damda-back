package io.groo.damda.apitoken.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.apitoken.controller.dto.ApiTokenApiDto.ApiTokenIssueRequest;
import io.groo.damda.apitoken.controller.dto.ApiTokenApiDto.ApiTokenIssueResponse;
import io.groo.damda.apitoken.controller.dto.ApiTokenApiDto.ApiTokenResponse;
import io.groo.damda.apitoken.service.ApiTokenService;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
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
 * 프로그램용 API 토큰 발급·목록·폐기.
 * 활성 구독(SECURITIES) 필요 — {@code FeatureGateInterceptor} 가 게이트한다.
 *
 * <p><b>API 토큰으로는 여기를 못 부른다</b> — 토큰이 열 수 있는 자리는 증권 조회 셋뿐이라
 * ({@code ApiTokenAuthenticationFilter}) 토큰으로 토큰을 새로 찍어 내는 길이 없다.
 * 발급·폐기는 로그인한 본인만 한다.
 */
@RestController
@RequestMapping("/api/v1/users/me/api-tokens")
@RequiredArgsConstructor
public class UserApiTokenApiController {

    private final ApiTokenService apiTokenService;

    @GetMapping
    public ApiResponse<List<ApiTokenResponse>> getTokens(@LoginUser UserPrincipal loginUser) {
        return ApiResponse.success(apiTokenService.getTokens(loginUser.getRowId()).stream()
            .map(ApiTokenResponse::from)
            .toList());
    }

    @PostMapping
    public ApiResponse<ApiTokenIssueResponse> issue(
            @LoginUser UserPrincipal loginUser,
            @RequestBody ApiTokenIssueRequest request) {
        return ApiResponse.success(ApiTokenIssueResponse.from(
            apiTokenService.issue(loginUser.getRowId(), request.name())));
    }

    @DeleteMapping("/{tokenRowId}")
    public ApiResponse<Void> revoke(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long tokenRowId) {
        apiTokenService.revoke(loginUser.getRowId(), tokenRowId);
        return ApiResponse.success();
    }
}
