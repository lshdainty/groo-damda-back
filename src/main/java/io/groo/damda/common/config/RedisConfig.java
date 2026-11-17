package io.groo.damda.common.config;

import io.groo.core.redis.NamespacedStringRedisTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 담다의 Redis 키를 전부 {@code damda:} 아래에 둔다(보안 점검 INF-2 · SSO-13, core 3.5.0).
 *
 * <p>Redis 한 대를 SSO · 이음 · 담다가 같이 쓴다. 예전에는 세 서비스가 계정 하나로 붙어서, 담다가 뚫리면 SSO 의
 * 차단 기록이나 로그인 잠금을 지울 수 있었다. 이제 담다는 전용 계정({@code REDIS_USERNAME=damda})으로 붙고, 그 계정은
 * {@code damda:*} 키만 읽고 쓸 수 있다. Redis 의 계정 권한은 키 이름으로만 나눌 수 있으므로 담다가 쓰는 키는
 * 하나도 빠짐없이 {@code damda:} 로 시작해야 한다.
 *
 * <p>그래서 접두사를 키마다 손으로 붙이지 않고 <b>템플릿이 한 번에 붙인다.</b> 코드의 키 이름에는 서비스 접두사를
 * 쓰지 않는다 — {@code session:revoked:abc} 라고 쓰면 Redis 에는 {@code damda:session:revoked:abc} 로 들어간다.
 * core 의 {@code SsoEventVerifier} 가 기억하는 이벤트 번호도 이 템플릿을 받아 같은 접두사 아래에 놓인다.
 *
 * <p><b>새 Redis 키를 만들 때</b>: {@code StringRedisTemplate} 을 주입받아 쓰면 된다. 접두사를 직접 붙이면
 * {@code damda:damda:...} 가 되고, 템플릿을 거치지 않고 연결에 바로 명령을 보내면 접두사가 빠져 권한 오류
 * (NOPERM)가 난다. 채널 이름(pub/sub)에는 접두사가 붙지 않는다.
 */
@Configuration
public class RedisConfig {

    /** Redis 계정 권한({@code ~damda:*})과 같은 이름이어야 한다 — 다르면 모든 키가 권한 밖이 된다. */
    public static final String KEY_NAMESPACE = "damda";

    /** Spring Boot 가 기본으로 만드는 {@code stringRedisTemplate} 을 대신한다. */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new NamespacedStringRedisTemplate(connectionFactory, KEY_NAMESPACE);
    }
}
