package io.groo.damda.common.config;

import io.groo.core.redis.NamespacedStringRedisTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 담다의 Redis 키 접두사(보안 점검 INF-2 · SSO-13) — Redis 계정이 {@code damda:*} 만 허용하므로,
 * 템플릿이 다른 접두사를 붙이거나 안 붙이면 운영에서 모든 Redis 호출이 권한 오류로 떨어진다.
 */
@DisplayName("Redis 키 접두사 설정")
class RedisConfigTest {

    @Test
    @DisplayName("stringRedisTemplate 은 모든 키에 damda: 를 붙인다")
    void templatePrefixesEveryKeyWithServiceName() {
        StringRedisTemplate template = new RedisConfig().stringRedisTemplate(mock(RedisConnectionFactory.class));

        assertThat(template).isInstanceOf(NamespacedStringRedisTemplate.class);
        assertThat(((NamespacedStringRedisTemplate) template).keyPrefix()).isEqualTo("damda:");
        @SuppressWarnings("unchecked")
        byte[] raw = ((org.springframework.data.redis.serializer.RedisSerializer<String>) template.getKeySerializer())
                .serialize("session:revoked:sid-1");
        assertThat(new String(raw, StandardCharsets.UTF_8)).isEqualTo("damda:session:revoked:sid-1");
    }
}
