package io.groo.damda.common.config;

import io.groo.damda.common.event.SsoSessionEventSubscriber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * SSO 세션 폐기 이벤트 수신 배선.
 *
 * <p>채널 이름은 발행 쪽(porest-sso-back {@code SsoSessionEventPublisher})과 <b>문자열이
 * 같아야 한다.</b> 두 레포 사이에 공유 상수가 없어 어긋나도 아무도 안 죽는다 — 그냥
 * 조용히 아무 이벤트도 안 온다.
 *
 * <p>{@code app.session-events.enabled=false} 로 끌 수 있다(기본 켜짐). 리스너 컨테이너는
 * 시작할 때 Redis 에 붙지 못하면 <b>애플리케이션 자체를 못 뜨게 한다</b> — Redis 없이 도는
 * 테스트 컨텍스트를 위한 스위치다. 운영에서는 끄지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = "app.session-events.enabled", havingValue = "true", matchIfMissing = true)
public class RedisSessionEventConfig {

    @Bean
    public ChannelTopic sessionEventTopic(
            // 기본값은 옛 이름이고, 새 스택은 SSO_SESSION_EVENTS_CHANNEL 로 바꾼다(발행 쪽 sso 와 같아야 한다)
            @Value("${app.redis.channels.session-events:porest:sso:session-events}") String channel) {
        return new ChannelTopic(channel);
    }

    @Bean
    public MessageListenerAdapter sessionEventListenerAdapter(SsoSessionEventSubscriber subscriber) {
        return new MessageListenerAdapter(subscriber, "handleSessionEvent");
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter sessionEventListenerAdapter,
            ChannelTopic sessionEventTopic) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(sessionEventListenerAdapter, sessionEventTopic);
        return container;
    }
}
