package io.groo.damda.subscription.service;

import io.groo.core.type.YNType;
import io.groo.damda.subscription.domain.UserSubscription;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 구독 라이프사이클. 결제(PG) 없음 — 부여는 self-grant(추후 결제완료가 {@link #subscribe} 호출),
 * 갱신/만료는 {@code processExpiry} 스케줄러가 처리.
 */
public interface SubscriptionService {

    /**
     * 구독 부여(결제 없이 즉시 ACTIVE). 이미 권한이 살아 있으면 충돌 —
     * 해지했어도 남은 기간을 쓰는 중이면 여기에 걸린다.
     */
    SubscriptionInfo subscribe(Long userRowId, String planCode);

    /** 구독 해지 — 자동갱신만 끄고 남은 기간의 권한은 유지한다. 이미 해지된 구독이면 무동작. */
    void cancel(Long userRowId, String reason);

    /** 사용자의 최근 구독 1건. */
    Optional<SubscriptionInfo> getMySubscription(Long userRowId);

    /** 만료 배치: 만료된 구독을 auto_renew 면 기간연장, 아니면 EXPIRED. 처리 건수 반환. */
    int processExpiry();

    /** 판매중(활성) 플랜 목록 — 플랜 화면용. */
    java.util.List<PlanInfo> getActivePlans();

    record PlanInfo(String planCode, String planName, Integer durationMonths, Integer price) {
        public static PlanInfo from(io.groo.damda.subscription.domain.SubscriptionPlan p) {
            return new PlanInfo(p.getPlanCode(), p.getPlanName(), p.getDurationMonths(), p.getPrice());
        }
    }

    record SubscriptionInfo(
        String planCode,
        String planName,
        String status,
        LocalDateTime startedAt,
        LocalDateTime currentPeriodEnd,
        boolean autoRenew,
        Integer price,
        Integer durationMonths
    ) {
        public static SubscriptionInfo from(UserSubscription s) {
            return new SubscriptionInfo(
                s.getPlan().getPlanCode(),
                s.getPlan().getPlanName(),
                s.getStatus().name(),
                s.getStartedAt(),
                s.getCurrentPeriodEnd(),
                s.getAutoRenew() == YNType.Y,
                s.getPlan().getPrice(),
                s.getPlan().getDurationMonths()
            );
        }
    }
}
