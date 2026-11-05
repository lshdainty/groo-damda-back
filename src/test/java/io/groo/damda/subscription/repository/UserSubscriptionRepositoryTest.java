package io.groo.damda.subscription.repository;

import io.groo.core.type.YNType;
import io.groo.damda.common.config.QueryDslConfig;
import io.groo.damda.common.config.database.JpaAuditingConfig;
import io.groo.damda.common.config.database.LoginUserAuditorAware;
import io.groo.damda.subscription.domain.SubscriptionPlan;
import io.groo.damda.subscription.domain.UserSubscription;
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
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 권한 판정 조회(findEntitled)를 H2 에서 실제 SQL 로 검증한다.
 *
 * <p>지키려는 약속은 하나다 — <b>해지해도 이미 낸 기간까지는 Pro 가 유지되고, 그 기간이 지나면 막힌다.</b>
 * 판정이 조회 시점에 이뤄지므로 <b>이미 CANCELLED 로 적혀 있던 옛 행도</b> 남은 기간을 되찾는다.
 * 이 규칙은 JPQL 한 곳에만 있으므로 여기서 깨지면 권한 전체가 깨진다.
 *
 * <p>플랜·구독은 seed/서비스로만 만들어져 테스트용 팩토리가 없다 — 리플렉션으로 필드를 채워 저장한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueryDslConfig.class, JpaAuditingConfig.class, LoginUserAuditorAware.class})
@ActiveProfiles("test")
class UserSubscriptionRepositoryTest {

    @Autowired private TestEntityManager em;
    @Autowired private UserSubscriptionRepository repository;

    private static final long USER = 42L;

    /** 밀리초로 끊는다 — 저장·비교 값이 datetime 정밀도 안에 그대로 떨어져야 경계가 흔들리지 않는다. */
    private final LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    private SubscriptionPlan plan;

    @BeforeEach
    void setUpPlan() {
        plan = BeanUtils.instantiateClass(SubscriptionPlan.class);
        ReflectionTestUtils.setField(plan, "planCode", "SECURITIES");
        ReflectionTestUtils.setField(plan, "planName", "증권 구독");
        ReflectionTestUtils.setField(plan, "features", "[\"SECURITIES\"]");
        ReflectionTestUtils.setField(plan, "durationMonths", 1);
        ReflectionTestUtils.setField(plan, "price", 9900);
        ReflectionTestUtils.setField(plan, "isActive", YNType.Y);
        ReflectionTestUtils.setField(plan, "sortOrder", 1);
        ReflectionTestUtils.setField(plan, "isDeleted", YNType.N);
        em.persist(plan);
    }

    /** 상태·만료일·삭제표식을 직접 박은 구독 행. 실제 DB 에 이미 있는 모양을 그대로 재현한다. */
    private UserSubscription persistSubscription(SubscriptionStatus status, LocalDateTime periodEnd,
                                                 YNType autoRenew, YNType isDeleted) {
        UserSubscription sub = UserSubscription.activate(USER, plan, now.minusMonths(1), autoRenew == YNType.Y);
        ReflectionTestUtils.setField(sub, "status", status);
        ReflectionTestUtils.setField(sub, "currentPeriodEnd", periodEnd);
        ReflectionTestUtils.setField(sub, "isDeleted", isDeleted);
        if (status == SubscriptionStatus.CANCELLED) {
            ReflectionTestUtils.setField(sub, "cancelledAt", now.minusDays(1));
            ReflectionTestUtils.setField(sub, "cancellationReason", "사용자 요청");
        }
        em.persist(sub);
        em.flush();
        em.clear();
        return sub;
    }

    @Test
    @DisplayName("ACTIVE 이고 기간이 남았으면 권한이 산다")
    void entitled_active() {
        persistSubscription(SubscriptionStatus.ACTIVE, now.plusDays(20), YNType.Y, YNType.N);

        assertThat(repository.findEntitled(USER, YNType.N, now)).hasSize(1);
    }

    @Test
    @DisplayName("ACTIVE 이고 만료일이 없으면(무제한) 권한이 산다")
    void entitled_activeUnlimited() {
        persistSubscription(SubscriptionStatus.ACTIVE, null, YNType.Y, YNType.N);

        assertThat(repository.findEntitled(USER, YNType.N, now)).hasSize(1);
    }

    @Test
    @DisplayName("해지(CANCELLED)해도 기간이 남았으면 권한이 산다 — 이미 CANCELLED 로 적힌 옛 행도 남은 기간을 되찾는다")
    void entitled_cancelledWithinPeriod() {
        persistSubscription(SubscriptionStatus.CANCELLED, now.plusDays(20), YNType.N, YNType.N);

        assertThat(repository.findEntitled(USER, YNType.N, now))
            .singleElement()
            .satisfies(s -> {
                assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
                assertThat(s.getAutoRenew()).isEqualTo(YNType.N);
                // 언제·왜 해지했는지는 그대로 남는다
                assertThat(s.getCancelledAt()).isNotNull();
                assertThat(s.getCancellationReason()).isEqualTo("사용자 요청");
            });
    }

    @Test
    @DisplayName("해지했고 기간이 지났으면 막힌다 — 유예지 영구 무료가 아니다")
    void notEntitled_cancelledAfterPeriod() {
        persistSubscription(SubscriptionStatus.CANCELLED, now.minusSeconds(1), YNType.N, YNType.N);

        assertThat(repository.findEntitled(USER, YNType.N, now)).isEmpty();
    }

    @Test
    @DisplayName("경계 — 만료 시각 정각은 이미 끝난 것으로 본다(그 직전까지만 열려 있다)")
    void notEntitled_atExactPeriodEnd() {
        // 비교 폭은 1초로 둔다 — 나노 단위는 datetime 컬럼 정밀도 밖이라 바인딩에서 반올림돼
        // 경계가 아니라 드라이버를 시험하게 된다.
        LocalDateTime end = now.plusDays(20);
        persistSubscription(SubscriptionStatus.CANCELLED, end, YNType.N, YNType.N);

        assertThat(repository.findEntitled(USER, YNType.N, end.minusSeconds(1))).hasSize(1);
        assertThat(repository.findEntitled(USER, YNType.N, end)).isEmpty();
        assertThat(repository.findEntitled(USER, YNType.N, end.plusSeconds(1))).isEmpty();
    }

    @Test
    @DisplayName("해지했는데 만료일이 없으면(무제한) 그 자리에서 막힌다 — 되돌아올 날이 없어 영구 무료가 된다")
    void notEntitled_cancelledUnlimited() {
        persistSubscription(SubscriptionStatus.CANCELLED, null, YNType.N, YNType.N);

        assertThat(repository.findEntitled(USER, YNType.N, now)).isEmpty();
    }

    @Test
    @DisplayName("EXPIRED 는 기간이 남아 있어도 권한이 없다")
    void notEntitled_expired() {
        persistSubscription(SubscriptionStatus.EXPIRED, now.plusDays(20), YNType.N, YNType.N);

        assertThat(repository.findEntitled(USER, YNType.N, now)).isEmpty();
    }

    @Test
    @DisplayName("지운 구독은 상태·기간과 무관하게 권한이 없다")
    void notEntitled_deleted() {
        persistSubscription(SubscriptionStatus.CANCELLED, now.plusDays(20), YNType.N, YNType.Y);

        assertThat(repository.findEntitled(USER, YNType.N, now)).isEmpty();
    }

    @Test
    @DisplayName("남의 구독은 안 걸린다")
    void notEntitled_otherUser() {
        persistSubscription(SubscriptionStatus.ACTIVE, now.plusDays(20), YNType.Y, YNType.N);

        assertThat(repository.findEntitled(USER + 1, YNType.N, now)).isEmpty();
    }

    @Test
    @DisplayName("만료 배치는 해지된 구독을 집지 않는다 — 기간이 지나도 CANCELLED 로 남는다")
    void findExpirable_skipsCancelled() {
        persistSubscription(SubscriptionStatus.CANCELLED, now.minusDays(1), YNType.N, YNType.N);

        assertThat(repository.findExpirable(SubscriptionStatus.ACTIVE, YNType.N, now)).isEmpty();
        // 배치가 손대지 않아도 권한 조회에서 빠지므로 막히는 건 그대로다
        assertThat(repository.findEntitled(USER, YNType.N, now)).isEmpty();
    }

    @Test
    @DisplayName("만료 배치는 기간이 지난 ACTIVE 구독은 그대로 집는다")
    void findExpirable_picksExpiredActive() {
        persistSubscription(SubscriptionStatus.ACTIVE, now.minusDays(1), YNType.N, YNType.N);

        assertThat(repository.findExpirable(SubscriptionStatus.ACTIVE, YNType.N, now)).hasSize(1);
    }
}
