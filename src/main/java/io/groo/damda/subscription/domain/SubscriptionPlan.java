package io.groo.damda.subscription.domain;

import io.groo.core.type.YNType;
import io.groo.damda.common.domain.AuditingFieldsWithIp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 구독 플랜 카탈로그. {@code features}(JSON 배열 문자열)로 부여 기능을 표현한다.
 * 결제(PG) 없음 — {@code price} 는 화면에 보이는 표시 가격(원)일 뿐 청구 금액이 아니다.
 */
@Entity
@Table(name = "subscription_plan")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionPlan extends AuditingFieldsWithIp {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Column(name = "plan_code", nullable = false, length = 50)
    private String planCode;

    @Column(name = "plan_name", nullable = false, length = 100)
    private String planName;

    /** 부여 기능 코드 JSON 배열 (예: {@code ["SECURITIES"]}). */
    @Column(name = "features", nullable = false, length = 500)
    private String features;

    /** 구독 기간(개월). 0 = 무제한. */
    @Column(name = "duration_months", nullable = false)
    private Integer durationMonths;

    /** 표시 가격(원). 결제(PG) 없음 — 화면에 보이는 값의 원본. */
    @Column(name = "price", nullable = false)
    private Integer price;

    @Enumerated(EnumType.STRING)
    @Column(name = "is_active", nullable = false, length = 1)
    private YNType isActive;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "is_deleted", nullable = false, length = 1)
    private YNType isDeleted;

    /** 플랜이 해당 기능 코드를 부여하는지 (features JSON 의 정확한 토큰 매칭). */
    public boolean hasFeature(String featureCode) {
        return features != null && features.contains("\"" + featureCode + "\"");
    }
}
