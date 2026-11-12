package io.groo.damda.subscription.service;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.InvalidValueException;
import io.groo.core.type.YNType;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.subscription.domain.SubscriptionPlan;
import io.groo.damda.subscription.domain.UserSubscription;
import io.groo.damda.subscription.repository.SubscriptionPlanRepository;
import io.groo.damda.subscription.repository.UserSubscriptionRepository;
import io.groo.damda.subscription.type.SubscriptionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionServiceImpl implements SubscriptionService {

    private final SubscriptionPlanRepository planRepository;
    private final UserSubscriptionRepository subscriptionRepository;

    @Override
    @Transactional
    public SubscriptionInfo subscribe(Long userRowId, String planCode) {
        LocalDateTime now = LocalDateTime.now();
        // 중복 구독 방지 (앱 레벨). 해지했어도 남은 기간을 쓰는 중이면 아직 구독이 살아 있는 것이라
        // 여기서 막는다 — 통과시키면 기간이 겹치는 행이 둘 생기고, 결제가 붙는 순간 두 번 청구된다.
        if (!subscriptionRepository.findEntitled(userRowId, YNType.N, now).isEmpty()) {
            throw new InvalidValueException(DeskErrorCode.SUBSCRIPTION_ALREADY_ACTIVE);
        }
        SubscriptionPlan plan = planRepository.findByPlanCodeAndIsDeleted(planCode, YNType.N)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.SUBSCRIPTION_PLAN_NOT_FOUND));
        // 판매 중지 플랜은 받지 않는다 — 목록(/plans)은 is_active=Y 만 보여 주는데, 여기서는 그 조건 없이 찾아
        // 목록에 없는 플랜 코드로도 구독이 생겼다(보안 점검 DAM-8). 스스로 켜는 동작 자체는 결제 연동 전 의도라 그대로 둔다.
        if (plan.getIsActive() != YNType.Y) {
            log.warn("판매 중지 플랜 구독 시도: userRowId={}, plan={}", userRowId, planCode);
            throw new InvalidValueException(DeskErrorCode.SUBSCRIPTION_PLAN_NOT_ON_SALE);
        }

        UserSubscription sub = UserSubscription.activate(userRowId, plan, now, true);
        subscriptionRepository.save(sub);
        log.info("구독 부여: userRowId={}, plan={}", userRowId, planCode);
        return SubscriptionInfo.from(sub);
    }

    /**
     * 해지 — 자동갱신을 끄고 상태를 CANCELLED 로 적는다. <b>남은 기간의 권한은 그대로 둔다.</b>
     *
     * <p>해지한 뒤에도 기간이 남아 있으면 화면은 여전히 Pro 로 보이므로 해지 버튼이 한 번 더
     * 눌릴 수 있다. 두 번째 호출은 무동작으로 성공시킨다(DELETE 는 멱등하다) —
     * 다시 {@code cancel()} 을 태우면 처음 해지한 시각·사유가 덮여 기록이 사라진다.
     */
    @Override
    @Transactional
    public void cancel(Long userRowId, String reason) {
        List<UserSubscription> entitled =
            subscriptionRepository.findEntitled(userRowId, YNType.N, LocalDateTime.now());
        if (entitled.isEmpty()) {
            throw new EntityNotFoundException(DeskErrorCode.SUBSCRIPTION_NOT_FOUND);
        }
        Optional<UserSubscription> active = entitled.stream()
            .filter(s -> s.getStatus() == SubscriptionStatus.ACTIVE)
            .findFirst();
        if (active.isEmpty()) {
            log.info("구독 해지 요청(이미 해지됨, 남은 기간 사용 중): userRowId={}", userRowId);
            return;
        }
        active.get().cancel(LocalDateTime.now(), reason);
        log.info("구독 해지: userRowId={}", userRowId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SubscriptionInfo> getMySubscription(Long userRowId) {
        return subscriptionRepository
            .findFirstByUserRowIdAndIsDeletedOrderByStartedAtDesc(userRowId, YNType.N)
            .map(SubscriptionInfo::from);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlanInfo> getActivePlans() {
        return planRepository.findByIsActiveAndIsDeletedOrderBySortOrderAsc(YNType.Y, YNType.N)
            .stream().map(PlanInfo::from).toList();
    }

    @Override
    @Transactional
    public int processExpiry() {
        LocalDateTime now = LocalDateTime.now();
        List<UserSubscription> expirable =
            subscriptionRepository.findExpirable(SubscriptionStatus.ACTIVE, YNType.N, now);
        for (UserSubscription sub : expirable) {
            if (sub.getAutoRenew() == YNType.Y) {
                sub.renew();
            } else {
                sub.expire();
            }
        }
        if (!expirable.isEmpty()) {
            log.info("구독 만료 배치: {}건 처리", expirable.size());
        }
        return expirable.size();
    }
}
