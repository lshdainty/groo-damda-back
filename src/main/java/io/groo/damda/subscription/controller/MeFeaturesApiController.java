package io.groo.damda.subscription.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import io.groo.damda.securities.service.SecuritiesCredentialService;
import io.groo.damda.securities.type.SecuritiesBroker;
import io.groo.damda.subscription.service.SubscriptionEntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 현재 사용자의 기능권한·연결상태. 프론트/앱 메뉴 게이트 + 설정 노출의 단일 소스.
 * 게이트 미적용 — 권한 판단용이므로 인증 사용자 누구나 호출(비구독자는 features 빈 배열).
 */
@RestController
@RequestMapping("/api/v1/users/me/features")
@RequiredArgsConstructor
public class MeFeaturesApiController {

    private final SubscriptionEntitlementService entitlementService;
    private final SecuritiesCredentialService credentialService;

    @GetMapping
    public ApiResponse<MeFeaturesResponse> getMyFeatures(@LoginUser UserPrincipal loginUser) {
        Long userRowId = loginUser.getRowId();
        List<String> features = entitlementService.getActiveFeatures(userRowId);
        List<String> connectedBrokers = credentialService.getConnections(userRowId).stream()
            .filter(c -> c.connected())
            .map(c -> c.broker().name())
            .toList();
        String primaryBroker = credentialService.getPrimaryBroker(userRowId)
            .map(SecuritiesBroker::name)
            .orElse(null);
        return ApiResponse.success(new MeFeaturesResponse(
            features, connectedBrokers, primaryBroker,
            connectedBrokers.contains(SecuritiesBroker.TOSS.name())));
    }

    /**
     * @param connectedBrokers 연결된 증권사 코드. 증권사가 늘어도 이 배열만 늘어난다
     * @param primaryBroker    가계부 자산 평가에 쓰는 증권사. 연결이 없으면 null
     * @param tossConnected    <b>구버전 클라이언트 호환용 파생값.</b> 새 코드는 connectedBrokers 를 봐라.
     *                         지우면 옛 앱이 "미연결" 로 읽어 증권 화면이 연결 유도로 되돌아간다
     */
    public record MeFeaturesResponse(
        List<String> features,
        List<String> connectedBrokers,
        String primaryBroker,
        boolean tossConnected
    ) {
    }
}
