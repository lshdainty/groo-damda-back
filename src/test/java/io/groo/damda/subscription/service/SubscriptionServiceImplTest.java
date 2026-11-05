package io.groo.damda.subscription.service;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.InvalidValueException;
import io.groo.core.type.YNType;
import io.groo.damda.subscription.domain.SubscriptionPlan;
import io.groo.damda.subscription.domain.UserSubscription;
import io.groo.damda.subscription.repository.SubscriptionPlanRepository;
import io.groo.damda.subscription.repository.UserSubscriptionRepository;
import io.groo.damda.subscription.type.SubscriptionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 구독 라이프사이클 프로세스 — 부여(중복방지)·해지(자동갱신만 끄고 기간 유지)·만료/갱신.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionServiceImplTest {

    @Mock private SubscriptionPlanRepository planRepository;
    @Mock private UserSubscriptionRepository subscriptionRepository;
    @InjectMocks private SubscriptionServiceImpl sut;

    private static final long USER = 1L;

    private SubscriptionPlan plan(int durationMonths) {
        SubscriptionPlan plan = mock(SubscriptionPlan.class);
        given(plan.getDurationMonths()).willReturn(durationMonths);
        org.mockito.Mockito.lenient().when(plan.getPlanCode()).thenReturn("SECURITIES");
        org.mockito.Mockito.lenient().when(plan.getPlanName()).thenReturn("증권 구독");
        return plan;
    }

    @Test
    @DisplayName("활성 구독이 없으면 구독 부여 시 ACTIVE 로 저장")
    void subscribe_creates_active() {
        SubscriptionPlan p = plan(1);
        given(subscriptionRepository.findEntitled(eq(USER), eq(YNType.N), any()))
            .willReturn(List.of());
        given(planRepository.findByPlanCodeAndIsDeleted("SECURITIES", YNType.N))
            .willReturn(Optional.of(p));

        SubscriptionService.SubscriptionInfo info = sut.subscribe(USER, "SECURITIES");

        assertThat(info.status()).isEqualTo("ACTIVE");
        verify(subscriptionRepository).save(any(UserSubscription.class));
    }

    @Test
    @DisplayName("이미 활성 구독이 있으면 SUBSCRIPTION_ALREADY_ACTIVE")
    void subscribe_alreadyActive() {
        UserSubscription existing = UserSubscription.activate(USER, plan(1), LocalDateTime.now(), true);
        given(subscriptionRepository.findEntitled(eq(USER), eq(YNType.N), any()))
            .willReturn(List.of(existing));

        assertThatThrownBy(() -> sut.subscribe(USER, "SECURITIES"))
            .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("존재하지 않는 플랜이면 SUBSCRIPTION_PLAN_NOT_FOUND")
    void subscribe_planNotFound() {
        given(subscriptionRepository.findEntitled(eq(USER), eq(YNType.N), any()))
            .willReturn(List.of());
        given(planRepository.findByPlanCodeAndIsDeleted("UNKNOWN", YNType.N)).willReturn(Optional.empty());

        assertThatThrownBy(() -> sut.subscribe(USER, "UNKNOWN"))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("해지는 자동갱신만 끈다 — 상태는 CANCELLED, 남은 기간은 그대로 둔다")
    void cancel_keepsPeriod() {
        UserSubscription sub = UserSubscription.activate(USER, plan(1), LocalDateTime.now(), true);
        LocalDateTime periodEnd = sub.getCurrentPeriodEnd();
        given(subscriptionRepository.findEntitled(eq(USER), eq(YNType.N), any()))
            .willReturn(List.of(sub));

        sut.cancel(USER, "사용자 요청");

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(sub.getAutoRenew()).isEqualTo(YNType.N);
        // 확인창이 약속한 "그 전까지는 계속 쓸 수 있어요" — 만료일을 앞당기지 않는다
        assertThat(sub.getCurrentPeriodEnd()).isEqualTo(periodEnd);
        // 언제·왜 해지했는지는 남는다
        assertThat(sub.getCancelledAt()).isNotNull();
        assertThat(sub.getCancellationReason()).isEqualTo("사용자 요청");
    }

    @Test
    @DisplayName("남은 기간 중 해지를 한 번 더 눌러도 무동작 — 처음 해지한 시각·사유를 덮지 않는다")
    void cancel_idempotentWithinPeriod() {
        UserSubscription sub = UserSubscription.activate(USER, plan(1), LocalDateTime.now(), true);
        sub.cancel(LocalDateTime.now().minusDays(3), "처음 사유");
        LocalDateTime firstCancelledAt = sub.getCancelledAt();
        given(subscriptionRepository.findEntitled(eq(USER), eq(YNType.N), any()))
            .willReturn(List.of(sub));

        sut.cancel(USER, "두 번째 사유");

        assertThat(sub.getCancelledAt()).isEqualTo(firstCancelledAt);
        assertThat(sub.getCancellationReason()).isEqualTo("처음 사유");
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
    }

    @Test
    @DisplayName("권한이 살아 있는 구독이 없으면 해지 시 SUBSCRIPTION_NOT_FOUND")
    void cancel_notFound() {
        given(subscriptionRepository.findEntitled(eq(USER), eq(YNType.N), any()))
            .willReturn(List.of());

        assertThatThrownBy(() -> sut.cancel(USER, null))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("재구독 — 해지했어도 기간이 남았으면 SUBSCRIPTION_ALREADY_ACTIVE(기간 겹치는 행이 둘 생기지 않는다)")
    void subscribe_blockedWhileCancelledPeriodRemains() {
        UserSubscription cancelled = UserSubscription.activate(USER, plan(1), LocalDateTime.now(), true);
        cancelled.cancel(LocalDateTime.now(), "사용자 요청");
        given(subscriptionRepository.findEntitled(eq(USER), eq(YNType.N), any()))
            .willReturn(List.of(cancelled));

        assertThatThrownBy(() -> sut.subscribe(USER, "SECURITIES"))
            .isInstanceOf(InvalidValueException.class);
        verify(subscriptionRepository, never()).save(any(UserSubscription.class));
    }

    @Test
    @DisplayName("활성 플랜 목록을 정렬순으로 반환")
    void getActivePlans() {
        SubscriptionPlan p = plan(1);
        org.mockito.Mockito.lenient().when(p.getPlanCode()).thenReturn("SECURITIES");
        org.mockito.Mockito.lenient().when(p.getPlanName()).thenReturn("증권 구독");
        given(planRepository.findByIsActiveAndIsDeletedOrderBySortOrderAsc(YNType.Y, YNType.N))
            .willReturn(List.of(p));

        List<SubscriptionService.PlanInfo> plans = sut.getActivePlans();

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).planCode()).isEqualTo("SECURITIES");
    }

    private SubscriptionPlan plan(String code, String name, int durationMonths, int price) {
        SubscriptionPlan plan = mock(SubscriptionPlan.class);
        org.mockito.Mockito.lenient().when(plan.getPlanCode()).thenReturn(code);
        org.mockito.Mockito.lenient().when(plan.getPlanName()).thenReturn(name);
        org.mockito.Mockito.lenient().when(plan.getDurationMonths()).thenReturn(durationMonths);
        org.mockito.Mockito.lenient().when(plan.getPrice()).thenReturn(price);
        return plan;
    }

    @Test
    @DisplayName("활성 플랜 목록은 정렬 차례(월간 -> 연간) 그대로 price·durationMonths 를 싣는다")
    void getActivePlans_carriesPriceInSortOrder() {
        given(planRepository.findByIsActiveAndIsDeletedOrderBySortOrderAsc(YNType.Y, YNType.N))
            .willReturn(List.of(
                plan("SECURITIES", "증권 구독", 1, 9900),
                plan("SECURITIES_YEARLY", "증권 구독(연간)", 12, 99000)));

        List<SubscriptionService.PlanInfo> plans = sut.getActivePlans();

        assertThat(plans).extracting(SubscriptionService.PlanInfo::planCode)
            .containsExactly("SECURITIES", "SECURITIES_YEARLY");
        assertThat(plans).extracting(SubscriptionService.PlanInfo::durationMonths)
            .containsExactly(1, 12);
        assertThat(plans).extracting(SubscriptionService.PlanInfo::price)
            .containsExactly(9900, 99000);
    }

    @Test
    @DisplayName("연간 코드로 구독하면 기간 12개월이고 SubscriptionInfo 가 구독한 플랜의 price·durationMonths 를 싣는다")
    void subscribe_yearly_carriesPlanPriceAndDuration() {
        SubscriptionPlan yearly = plan("SECURITIES_YEARLY", "증권 구독(연간)", 12, 99000);
        given(subscriptionRepository.findEntitled(eq(USER), eq(YNType.N), any()))
            .willReturn(List.of());
        given(planRepository.findByPlanCodeAndIsDeleted("SECURITIES_YEARLY", YNType.N))
            .willReturn(Optional.of(yearly));

        SubscriptionService.SubscriptionInfo info = sut.subscribe(USER, "SECURITIES_YEARLY");

        assertThat(info.planCode()).isEqualTo("SECURITIES_YEARLY");
        assertThat(info.price()).isEqualTo(99000);
        assertThat(info.durationMonths()).isEqualTo(12);
        assertThat(info.currentPeriodEnd()).isEqualTo(info.startedAt().plusMonths(12));
    }

    @Test
    @DisplayName("만료 배치: auto_renew=Y 는 기간 연장, N 은 EXPIRED")
    void processExpiry_renewVsExpire() {
        UserSubscription renewable = UserSubscription.activate(USER, plan(1), LocalDateTime.now(), true);
        UserSubscription expiring = UserSubscription.activate(2L, plan(1), LocalDateTime.now(), false);
        LocalDateTime beforeRenew = renewable.getCurrentPeriodEnd();
        given(subscriptionRepository.findExpirable(eq(SubscriptionStatus.ACTIVE), eq(YNType.N), any()))
            .willReturn(List.of(renewable, expiring));

        int processed = sut.processExpiry();

        assertThat(processed).isEqualTo(2);
        assertThat(renewable.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(renewable.getCurrentPeriodEnd()).isAfter(beforeRenew); // 기간 연장
        assertThat(expiring.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
    }

    @Test
    @DisplayName("만료 배치는 ACTIVE 만 집는다 — 해지된 구독은 자동갱신으로 되살아나지 않는다")
    void processExpiry_onlyActive() {
        given(subscriptionRepository.findExpirable(eq(SubscriptionStatus.ACTIVE), eq(YNType.N), any()))
            .willReturn(List.of());

        assertThat(sut.processExpiry()).isZero();
        verify(subscriptionRepository).findExpirable(eq(SubscriptionStatus.ACTIVE), eq(YNType.N), any());
    }
}
