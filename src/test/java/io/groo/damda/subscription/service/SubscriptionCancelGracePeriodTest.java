package io.groo.damda.subscription.service;

import io.groo.core.type.YNType;
import io.groo.damda.common.config.QueryDslConfig;
import io.groo.damda.common.config.database.JpaAuditingConfig;
import io.groo.damda.common.config.database.LoginUserAuditorAware;
import io.groo.damda.subscription.domain.SubscriptionPlan;
import io.groo.damda.subscription.domain.UserSubscription;
import io.groo.damda.subscription.repository.SubscriptionPlanRepository;
import io.groo.damda.subscription.repository.UserSubscriptionRepository;
import io.groo.damda.subscription.type.SubscriptionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 해지 → 권한 조회를 <b>실제 쿼리로</b> 이어 붙여, QA 가 dev 에서 본 증상을 그대로 재현한다.
 *
 * <p>증상: 구독 직후 {@code features [SECURITIES]} → 해지 직후 {@code features []} · 증권 API 403.
 * <b>돈을 냈는데 해지한 그 자리에서 막혔다.</b> 웹·앱 확인창은 "{날짜}부터 Free 로 전환…
 * 그 전까지는 계속 쓸 수 있어요" 라고 약속하고 있었고, 틀린 쪽은 서버였다.
 *
 * <p>단위 테스트는 리포지토리를 mock 해서 이 구간을 못 본다 — 해지가 상태를 어떻게 적든,
 * 그 상태를 권한으로 번역하는 일은 JPQL 이 한다. 그래서 여기서는 서비스 둘을 실제 리포지토리로
 * 엮어 해지 직후·기간 경과 후를 모두 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueryDslConfig.class, JpaAuditingConfig.class, LoginUserAuditorAware.class})
@ActiveProfiles("test")
class SubscriptionCancelGracePeriodTest {

    @Autowired private TestEntityManager em;
    @Autowired private UserSubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionPlanRepository planRepository;

    private static final long USER = 7L;
    private static final String PLAN = "SECURITIES";

    private SubscriptionService subscriptionService;
    private SubscriptionEntitlementService entitlementService;

    @BeforeEach
    void setUp() {
        SubscriptionPlan plan = BeanUtils.instantiateClass(SubscriptionPlan.class);
        ReflectionTestUtils.setField(plan, "planCode", PLAN);
        ReflectionTestUtils.setField(plan, "planName", "증권 구독");
        ReflectionTestUtils.setField(plan, "features", "[\"SECURITIES\"]");
        ReflectionTestUtils.setField(plan, "durationMonths", 1);
        ReflectionTestUtils.setField(plan, "price", 9900);
        ReflectionTestUtils.setField(plan, "isActive", YNType.Y);
        ReflectionTestUtils.setField(plan, "sortOrder", 1);
        ReflectionTestUtils.setField(plan, "isDeleted", YNType.N);
        em.persist(plan);
        em.flush();

        subscriptionService = new SubscriptionServiceImpl(planRepository, subscriptionRepository);
        entitlementService = new SubscriptionEntitlementServiceImpl(subscriptionRepository);
    }

    /** 시간을 앞으로 못 돌리니 만료일을 뒤로 당겨 "기간이 지난 뒤" 를 만든다. */
    private void movePeriodEndToPast(long userRowId) {
        UserSubscription sub = subscriptionRepository
            .findFirstByUserRowIdAndIsDeletedOrderByStartedAtDesc(userRowId, YNType.N)
            .orElseThrow();
        ReflectionTestUtils.setField(sub, "currentPeriodEnd", LocalDateTime.now().minusSeconds(1));
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("해지 직후 — features 에 SECURITIES 가 그대로 있고 증권 게이트가 열려 있다")
    void featuresSurviveCancel() {
        subscriptionService.subscribe(USER, PLAN);
        assertThat(entitlementService.getActiveFeatures(USER)).containsExactly("SECURITIES");

        subscriptionService.cancel(USER, "사용자 요청");
        em.flush();
        em.clear();

        assertThat(entitlementService.getActiveFeatures(USER)).containsExactly("SECURITIES");
        assertThat(entitlementService.hasFeature(USER, "SECURITIES")).isTrue();
        // 게이트가 403 을 던지지 않는다
        entitlementService.requireFeature(USER, "SECURITIES");
    }

    @Test
    @DisplayName("해지의 본래 효과는 남는다 — 자동갱신 꺼짐 · 상태 CANCELLED · 해지 시각·사유 기록 · 만료일 그대로")
    void cancelStillRecorded() {
        subscriptionService.subscribe(USER, PLAN);
        em.flush();
        em.clear();
        // 저장된 값끼리 비교한다 — 메모리의 나노초는 datetime 컬럼에 못 들어가 되읽으면 잘린다
        LocalDateTime periodEndBefore = subscriptionRepository
            .findFirstByUserRowIdAndIsDeletedOrderByStartedAtDesc(USER, YNType.N).orElseThrow()
            .getCurrentPeriodEnd();

        subscriptionService.cancel(USER, "더 이상 사용 안 함");
        em.flush();
        em.clear();

        UserSubscription sub = subscriptionRepository
            .findFirstByUserRowIdAndIsDeletedOrderByStartedAtDesc(USER, YNType.N).orElseThrow();
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(sub.getAutoRenew()).isEqualTo(YNType.N);
        assertThat(sub.getCancelledAt()).isNotNull();
        assertThat(sub.getCancellationReason()).isEqualTo("더 이상 사용 안 함");
        // 만료일을 앞당기지 않는다 — 앞당기면 "그 전까지는 쓸 수 있어요" 가 거짓이 된다
        assertThat(sub.getCurrentPeriodEnd()).isEqualTo(periodEndBefore);
    }

    @Test
    @DisplayName("해지 뒤에도 GET /me 응답 모양은 그대로 — status CANCELLED · autoRenew false · 만료일은 미래")
    void mySubscriptionStillReadable() {
        subscriptionService.subscribe(USER, PLAN);
        subscriptionService.cancel(USER, "사용자 요청");
        em.flush();
        em.clear();

        assertThat(subscriptionService.getMySubscription(USER))
            .hasValueSatisfying(info -> {
                assertThat(info.status()).isEqualTo("CANCELLED");
                assertThat(info.autoRenew()).isFalse();
                assertThat(info.currentPeriodEnd()).isAfter(LocalDateTime.now());
            });
    }

    @Test
    @DisplayName("재구독 — 남은 기간 중에는 막는다(기간이 겹치는 구독이 둘 생기지 않는다)")
    void resubscribeBlockedWithinPeriod() {
        subscriptionService.subscribe(USER, PLAN);
        subscriptionService.cancel(USER, "사용자 요청");
        em.flush();
        em.clear();

        assertThatThrownBy(() -> subscriptionService.subscribe(USER, PLAN))
            .isInstanceOf(io.groo.core.exception.InvalidValueException.class);
        assertThat(subscriptionRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("기간이 지나면 막힌다 — 유예지 영구 무료가 아니다")
    void blockedAfterPeriodEnd() {
        subscriptionService.subscribe(USER, PLAN);
        subscriptionService.cancel(USER, "사용자 요청");
        em.flush();

        movePeriodEndToPast(USER);

        assertThat(entitlementService.getActiveFeatures(USER)).isEmpty();
        assertThat(entitlementService.hasFeature(USER, "SECURITIES")).isFalse();
        assertThatThrownBy(() -> entitlementService.requireFeature(USER, "SECURITIES"))
            .isInstanceOf(io.groo.core.exception.ForbiddenException.class);
    }

    @Test
    @DisplayName("만료 배치가 돌아도 해지된 구독은 그대로 CANCELLED — 자동갱신으로 되살아나지 않는다")
    void expiryBatchLeavesCancelledAlone() {
        subscriptionService.subscribe(USER, PLAN);
        subscriptionService.cancel(USER, "사용자 요청");
        em.flush();
        movePeriodEndToPast(USER);

        assertThat(subscriptionService.processExpiry()).isZero();
        em.flush();
        em.clear();

        UserSubscription sub = subscriptionRepository
            .findFirstByUserRowIdAndIsDeletedOrderByStartedAtDesc(USER, YNType.N).orElseThrow();
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(sub.getCurrentPeriodEnd()).isBefore(LocalDateTime.now());
        assertThat(entitlementService.getActiveFeatures(USER)).isEmpty();
    }

    @Test
    @DisplayName("기간이 끝난 뒤에는 다시 구독할 수 있다 — 새 구독이 ACTIVE 로 서고 권한이 돌아온다")
    void resubscribeAfterPeriodEnd() {
        subscriptionService.subscribe(USER, PLAN);
        subscriptionService.cancel(USER, "사용자 요청");
        em.flush();
        movePeriodEndToPast(USER);

        SubscriptionService.SubscriptionInfo again = subscriptionService.subscribe(USER, PLAN);
        em.flush();
        em.clear();

        assertThat(again.status()).isEqualTo("ACTIVE");
        assertThat(again.autoRenew()).isTrue();
        assertThat(entitlementService.getActiveFeatures(USER)).containsExactly("SECURITIES");
    }
}
