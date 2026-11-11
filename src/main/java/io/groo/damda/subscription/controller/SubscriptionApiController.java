package io.groo.damda.subscription.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import io.groo.damda.subscription.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 구독 관리 API. 결제(PG) 없음 — 구독 부여는 self-grant(추후 결제완료가 이 진입점 대체).
 * 게이트 미적용 — 구독을 해야 기능권한이 생기므로 구독 자체는 인증 사용자 누구나 호출.
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
public class SubscriptionApiController {

    private final SubscriptionService subscriptionService;

    /** 판매중 플랜 목록 (플랜 화면용). 인증만 필요. */
    @GetMapping("/plans")
    public ApiResponse<java.util.List<SubscriptionService.PlanInfo>> getPlans() {
        return ApiResponse.success(subscriptionService.getActivePlans());
    }

    @PostMapping
    public ApiResponse<SubscriptionResponse> subscribe(
            @LoginUser UserPrincipal loginUser,
            @RequestBody SubscribeRequest request) {
        return ApiResponse.success(
            SubscriptionResponse.from(subscriptionService.subscribe(loginUser.getRowId(), request.planCode())));
    }

    @DeleteMapping("/me")
    public ApiResponse<Void> cancel(
            @LoginUser UserPrincipal loginUser,
            @RequestBody(required = false) CancelRequest request) {
        subscriptionService.cancel(loginUser.getRowId(), request == null ? null : request.reason());
        return ApiResponse.success();
    }

    @GetMapping("/me")
    public ApiResponse<SubscriptionResponse> getMySubscription(@LoginUser UserPrincipal loginUser) {
        return ApiResponse.success(
            subscriptionService.getMySubscription(loginUser.getRowId())
                .map(SubscriptionResponse::from)
                .orElse(null));
    }

    public record SubscribeRequest(String planCode) {
    }

    public record CancelRequest(String reason) {
    }

    public record SubscriptionResponse(
        String planCode,
        String planName,
        String status,
        LocalDateTime startedAt,
        LocalDateTime currentPeriodEnd,
        boolean autoRenew,
        Integer price,
        Integer durationMonths
    ) {
        public static SubscriptionResponse from(SubscriptionService.SubscriptionInfo i) {
            return new SubscriptionResponse(
                i.planCode(), i.planName(), i.status(), i.startedAt(), i.currentPeriodEnd(), i.autoRenew(),
                i.price(), i.durationMonths());
        }
    }
}
