package io.groo.damda.securities.client;

import io.groo.damda.common.crypto.AesGcmCipher;
import io.groo.damda.securities.type.SecuritiesBroker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 토큰 캐시 — 운영 기본값.
 *
 * <p>인스턴스끼리 토큰을 공유하고 재시작해도 살아남는다. 나무처럼 <b>재발급이 보안 알림으로
 * 이어지는</b> 증권사에서는 이게 사실상 필수다.
 *
 * <p>TTL 은 Redis 가 관리한다 — 만료 시각을 값에 담아 직접 비교하면 인스턴스 시계가
 * 어긋났을 때 한쪽만 만료로 보는 일이 생긴다.
 *
 * <h2>값은 암호문으로 넣는다</h2>
 * 여기 들어가는 액세스 토큰은 잔고 조회·주문 권한을 가진 24시간짜리 자격증명이다. 그런데
 * <b>그 토큰을 만들어 내는 크리덴셜</b>({@code api_key_enc}·{@code api_secret_enc})은 DB 에
 * AES-GCM 암호문으로 넣는다 — 같은 급의 비밀을 한쪽만 가리면 경계가 어긋난 것이다.
 *
 * <p>가르는 기준은 <b>값이 우리 프로세스 밖으로 나가느냐</b>다. Redis 는 별도 프로세스이고,
 * 인스턴스끼리 공유되며, 스냅샷으로 디스크에 남고, 접속 비밀번호만 있으면 누구나 읽는다.
 * {@code KEYS securities:token:*} 한 번이면 전 사용자 토큰이 그대로 나왔다.
 * 반대로 {@link InMemoryBrokerTokenStore} 는 같은 힙 안이라 암호화해도 키가 옆에 있어
 * 힙 덤프 앞에서는 아무것도 못 막는다 — 거기는 평문으로 둔다.
 *
 * <h2>평문 시절 값이 남아 있다</h2>
 * 이 변경 전에 저장된 값은 평문이라 복호화가 실패한다. 그걸 <b>캐시 미스로 처리</b>해
 * 재발급을 태운다(암호키를 바꿨을 때도 같은 경로로 자연히 복구된다). 나무는 발급 1회가
 * 알림톡 1건이지만 <b>사용자당 딱 한 번</b>이고, 그 뒤로는 암호문이 들어가 정상 캐시된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.securities.token-store", havingValue = "redis", matchIfMissing = true)
@RequiredArgsConstructor
public class RedisBrokerTokenStore implements BrokerTokenStore {

    // 서비스 접두사(damda:)는 여기 쓰지 않는다 — 템플릿(RedisConfig)이 모든 키에 붙인다
    private static final String KEY_PREFIX = "securities:token:";

    private final StringRedisTemplate redisTemplate;
    private final AesGcmCipher cipher;

    @Override
    public Optional<String> get(SecuritiesBroker broker, Long userRowId) {
        String key = key(broker, userRowId);
        String stored = redisTemplate.opsForValue().get(key);
        if (stored == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(cipher.decrypt(stored));
        } catch (RuntimeException e) {
            // 평문 시절 값이거나 다른 키로 암호화된 값이다. 여기서 던지면 조회가 통째로 죽으므로
            // 캐시 미스로 낮춰 재발급으로 흘린다. 값은 남기지 않는다 — 그게 지금 막으려는 것이다.
            log.warn("{} 토큰 캐시를 복호화하지 못해 버린다, 다음 호출에서 재발급 (userRowId={}, 원인={})",
                broker, userRowId, e.getClass().getSimpleName());
            redisTemplate.delete(key);
            return Optional.empty();
        }
    }

    @Override
    public void put(SecuritiesBroker broker, Long userRowId, String accessToken, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return;
        }
        redisTemplate.opsForValue().set(
            key(broker, userRowId), cipher.encrypt(accessToken), Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public void evict(SecuritiesBroker broker, Long userRowId) {
        redisTemplate.delete(key(broker, userRowId));
    }

    private static String key(SecuritiesBroker broker, Long userRowId) {
        return KEY_PREFIX + broker.name() + ':' + userRowId;
    }
}
