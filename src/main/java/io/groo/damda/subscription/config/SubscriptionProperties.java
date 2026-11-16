package io.groo.damda.subscription.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 구독 설정. {@code app.subscription.*} 를 바인딩한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.subscription")
public class SubscriptionProperties {

    /**
     * 사용자가 화면에서 스스로 구독을 시작할 수 있는가. <b>기본은 닫힘(false)</b> 이다.
     *
     * <p>결제(PG) 연동 전이라 {@code POST /api/v1/subscriptions} 는 돈을 받지 않고 바로 켠다.
     * 그래서 지금은 막아 두고 관리자가 DB({@code user_subscription})에 행을 넣어 일부 사용자만 켠다.
     * 운영 env 를 아무것도 안 넣어도 닫혀 있어야 하므로 기본값을 false 로 둔다 — 열 때만
     * {@code SUBSCRIPTION_SELF_SIGNUP_ENABLED=true}. 웹·앱 시작 버튼은
     * {@code GET /users/me/features} 의 {@code subscriptionSelfSignupEnabled} 로 이 값을 따른다.
     */
    private boolean selfSignupEnabled = false;
}
