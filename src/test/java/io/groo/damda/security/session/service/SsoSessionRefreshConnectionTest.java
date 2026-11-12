package io.groo.damda.security.session.service;

import com.zaxxer.hikari.HikariDataSource;
import io.groo.core.type.YNType;
import io.groo.damda.common.crypto.AesGcmCipher;
import io.groo.damda.security.client.SsoOAuth2Client;
import io.groo.damda.security.principal.JwtClaimsPrincipal;
import io.groo.damda.security.service.TokenExchangeService;
import io.groo.damda.security.session.domain.UserSsoSession;
import io.groo.damda.security.session.repository.UserSsoSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 무음 재인증이 SSO 를 부르는 동안 <b>DB 연결·행 잠금을 쥐고 있지 않은지</b>, 그러면서도 같은 세션의
 * 동시 갱신이 SSO 를 한 번만 부르는지 — 진짜 트랜잭션 · 진짜 커넥션 풀(H2)로 본다(보안 점검 DAM-5 잔여).
 *
 * <p>단위 테스트로는 못 본다. 종전 코드의 문제는 {@code @Transactional} 이 어디까지 덮느냐였고,
 * 그건 프록시가 붙은 빈에서만 드러난다.
 *
 * <p>종전에는 {@code reauthenticate}(트랜잭션) → {@code refresh}(트랜잭션 합류) → 행 잠금 →
 * SSO 호출(읽기 타임아웃 10초)이 한 트랜잭션이라, SSO 가 느리면 요청마다 커넥션 하나와 그 세션의
 * 행 잠금이 최대 10초씩 묶였다. 풀은 기본 10개라 만료 직전 갱신이 몰리는 순간 열 건이면 서비스
 * 전체가 커넥션을 못 얻는다.
 */
@SpringBootTest(properties = "app.security.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("test")
class SsoSessionRefreshConnectionTest {

    @MockitoBean private SsoOAuth2Client ssoOAuth2Client;

    @Autowired private SsoSessionService ssoSessionService;
    @Autowired private TokenExchangeService tokenExchangeService;
    @Autowired private UserSsoSessionRepository sessionRepository;
    @Autowired private AesGcmCipher cipher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private DataSource dataSource;

    /** 하루 전에 로그인해 refresh {@code T0} 를 든, 아직 갱신된 적 없는 세션. */
    private String newSession() {
        String sessionId = "sid-" + UUID.randomUUID();
        transactionTemplate.executeWithoutResult(s -> sessionRepository.save(UserSsoSession.issue(
                7L, sessionId, cipher.encrypt("T0"), LocalDateTime.now(ZoneOffset.UTC).plusDays(3), "iPhone")));
        return sessionId;
    }

    private UserSsoSession load(String sessionId) {
        return transactionTemplate.execute(s ->
                sessionRepository.findBySessionIdAndIsDeleted(sessionId, YNType.N).orElse(null));
    }

    private int activeConnections() throws java.sql.SQLException {
        return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
    }

    @Test
    @DisplayName("SSO 를 부르는 동안 트랜잭션도, 쥔 커넥션도 없다 — 필터가 타는 reauthenticate 경로 그대로")
    void ssoCallHoldsNoTransactionNorConnection() throws Exception {
        String sessionId = newSession();
        List<Boolean> inTx = new ArrayList<>();
        List<Integer> active = new ArrayList<>();
        given(ssoOAuth2Client.refreshTokens(anyString())).willAnswer(inv -> {
            inTx.add(TransactionSynchronizationManager.isActualTransactionActive());
            active.add(activeConnections());
            return null; // 거부 — 유효한 SSO access token 을 만들 필요 없이 경로만 본다
        });

        String token = tokenExchangeService.reauthenticate(new JwtClaimsPrincipal(
                "tester", "테스터", "tester@porest.com", 7L, "access", sessionId));

        assertThat(token).isNull();
        assertThat(inTx).as("SSO 호출 중 트랜잭션").containsExactly(false);
        assertThat(active).as("SSO 호출 중 이 스레드가 쥔 커넥션").containsExactly(0);
        // 거부는 여전히 세션을 끊는다
        assertThat(load(sessionId)).isNull();
    }

    @Test
    @DisplayName("같은 세션 동시 갱신 8건 — SSO 는 한 번만 불리고 전원 갱신, 저장된 refresh 는 방금 받은 T1")
    void concurrentRefreshCallsSsoOnce() throws Exception {
        String sessionId = newSession();
        int threads = 8;
        AtomicInteger ssoCalls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> activeDuringSso = Collections.synchronizedList(new ArrayList<>());
        given(ssoOAuth2Client.refreshTokens(anyString())).willAnswer(inv -> {
            ssoCalls.incrementAndGet();
            assertThat((String) inv.getArgument(0)).isEqualTo("T0");
            release.await(10, TimeUnit.SECONDS); // 느린 SSO — 나머지가 뒤에 줄을 선다
            activeDuringSso.add(activeConnections());
            return new SsoOAuth2Client.TokenPair("A1", "T1");
        });

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<SsoSessionService.RefreshResult>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> ssoSessionService.refresh(sessionId)));
            }
            // 전원이 같은 세션의 갱신 줄에 들어올 때까지 SSO 응답을 붙잡는다 — 한 명이라도
            // 줄 밖에서 SSO 를 또 부르면 아래 호출 수 단언이 깨진다.
            long deadline = System.currentTimeMillis() + 10_000;
            while (ssoSessionService.refreshQueueLength(sessionId) < threads
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(ssoSessionService.refreshQueueLength(sessionId)).isEqualTo(threads);
            release.countDown();

            int fromSso = 0;
            int reused = 0;
            for (Future<SsoSessionService.RefreshResult> f : results) {
                SsoSessionService.RefreshResult r = f.get(20, TimeUnit.SECONDS);
                assertThat(r.renewed()).isTrue();
                if (r.ssoAccessToken() != null) fromSso++; else reused++;
            }
            assertThat(ssoCalls.get()).as("SSO 호출 수 — 둘째부터 부르면 rotation 으로 T1 이 폐기된다").isEqualTo(1);
            assertThat(fromSso).isEqualTo(1);
            assertThat(reused).isEqualTo(threads - 1);
            // 줄 선 7건도 SSO 를 기다리는 동안 커넥션을 쥐지 않는다 — 종전엔 전원이 행 잠금 대기로 하나씩 쥐었다
            assertThat(activeDuringSso).containsExactly(0);
        } finally {
            pool.shutdownNow();
        }

        UserSsoSession after = load(sessionId);
        assertThat(after).isNotNull();
        assertThat(cipher.decrypt(after.getRefreshTokenEnc())).isEqualTo("T1");
        assertThat(after.getLastUsedAt()).isNotNull();
        assertThat(ssoSessionService.refreshQueueLength(sessionId)).as("줄 기록이 남지 않는다").isZero();
    }

    @Test
    @DisplayName("desk 토큰 발급 검증이 실패하면 세션 회전도 롤백된다 — 종전 한 트랜잭션일 때와 같은 결말")
    void exchangeFailureRollsBackRotation() {
        String sessionId = newSession();
        // 서명 검증을 통과하지 못하는 SSO access token — doExchange 가 AUTH_EXCHANGE_FAILED 로 던진다
        given(ssoOAuth2Client.refreshTokens(anyString())).willReturn(new SsoOAuth2Client.TokenPair("not-a-jwt", "T1"));

        assertThatThrownBy(() -> tokenExchangeService.reauthenticate(new JwtClaimsPrincipal(
                "tester", "테스터", "tester@porest.com", 7L, "access", sessionId)))
                .isInstanceOf(io.groo.core.exception.UnauthorizedException.class);

        UserSsoSession after = load(sessionId);
        assertThat(after).isNotNull();
        assertThat(cipher.decrypt(after.getRefreshTokenEnc())).isEqualTo("T0");
        assertThat(after.getLastUsedAt()).as("유예 창을 열지 않는다 — 뒤따라온 요청이 검증 없이 재사용하면 안 된다").isNull();
        assertThat(ssoSessionService.refreshQueueLength(sessionId)).isZero();
    }

    @Test
    @DisplayName("한 세션의 SSO 호출이 붙잡혀 있어도 다른 세션의 갱신은 기다리지 않는다")
    void otherSessionIsNotBlocked() throws Exception {
        String slow = newSession();
        String fast = newSession();
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(ssoOAuth2Client.refreshTokens(anyString())).willAnswer(inv -> {
            if (Thread.currentThread().getName().startsWith("slow")) {
                slowEntered.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            return new SsoOAuth2Client.TokenPair("A", "T-" + UUID.randomUUID());
        });

        ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "slow-refresh"));
        try {
            Future<SsoSessionService.RefreshResult> slowResult = pool.submit(() -> ssoSessionService.refresh(slow));
            assertThat(slowEntered.await(10, TimeUnit.SECONDS)).isTrue();

            long t0 = System.nanoTime();
            assertThat(ssoSessionService.refresh(fast).ssoAccessToken()).isEqualTo("A");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(5_000);

            release.countDown();
            assertThat(slowResult.get(10, TimeUnit.SECONDS).renewed()).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
