package io.groo.damda.securities.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import io.groo.damda.securities.controller.dto.SecuritiesCredentialApiDto.BrokerConnectionResponse;
import io.groo.damda.securities.controller.dto.SecuritiesCredentialApiDto.RegisterRequest;
import io.groo.damda.securities.service.SecuritiesCredentialService;
import io.groo.damda.securities.type.SecuritiesBroker;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 증권사별 크리덴셜 등록/상태/해제 + 기본 시세 소스 지정.
 * 활성 구독(SECURITIES) 필요 — {@code FeatureGateInterceptor} 가 게이트한다.
 *
 * <p>키/시크릿은 요청 본문으로만 받고, 응답에는 평문도 암호문도 절대 싣지 않는다.
 */
@RestController
@RequestMapping("/api/v1/users/me/securities-credentials")
@RequiredArgsConstructor
public class UserSecuritiesCredentialApiController {

    private final SecuritiesCredentialService credentialService;

    /** 전 증권사 연결 상태 — 미연결도 포함해 화면이 목록을 그린다. */
    @GetMapping
    public ApiResponse<List<BrokerConnectionResponse>> getConnections(@LoginUser UserPrincipal loginUser) {
        return ApiResponse.success(credentialService.getConnections(loginUser.getRowId()).stream()
            .map(BrokerConnectionResponse::from)
            .toList());
    }

    @PostMapping("/{broker}")
    public ApiResponse<Void> register(
            @LoginUser UserPrincipal loginUser,
            @PathVariable String broker,
            @RequestBody RegisterRequest request) {
        credentialService.register(loginUser.getRowId(), SecuritiesBroker.from(broker),
            request.apiKey(), request.apiSecret());
        return ApiResponse.success();
    }

    @DeleteMapping("/{broker}")
    public ApiResponse<Void> disconnect(
            @LoginUser UserPrincipal loginUser,
            @PathVariable String broker) {
        credentialService.disconnect(loginUser.getRowId(), SecuritiesBroker.from(broker));
        return ApiResponse.success();
    }

    /** 가계부 자산 평가에 쓸 증권사를 사용자가 고른다. */
    @PutMapping("/{broker}/primary")
    public ApiResponse<Void> setPrimary(
            @LoginUser UserPrincipal loginUser,
            @PathVariable String broker) {
        credentialService.setPrimary(loginUser.getRowId(), SecuritiesBroker.from(broker));
        return ApiResponse.success();
    }
}
