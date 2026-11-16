package io.groo.damda.security.session.service;

import io.groo.damda.common.crypto.AesGcmCipher;
import io.groo.damda.security.client.SsoOAuth2Client;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 로그아웃 때 SSO 로그인 계열 폐기(/oauth2/revoke)를 <b>요청 스레드 밖에서</b> 부른다(감사 지적 B-6 후속).
 *
 * <p>커밋 뒤({@code afterCommit})에 부르는 것만으로는 모자랐다 — afterCommit 콜백은 커넥션을 돌려주는
 * {@code cleanupAfterCompletion} 보다 먼저 돌아, SSO 가 느리면(읽기 타임아웃 10초) 로그아웃 요청마다 커넥션을 하나씩
 * 쥐고 기다렸다. 여기서는 작업을 넘기기만 하고 바로 돌아온다.
 *
 * <p>전역 {@code @EnableAsync} 를 켜지 않고 이 용도 전용의 작은 풀을 둔다 — 담다에는 아직 비동기 작업이 없어, 전역으로
 * 켜면 앞으로 붙는 {@code @Async} 가 모두 같은 기본 풀을 나눠 쓰게 된다. 큐가 차면 그 폐기는 버리고 로그만 남긴다 —
 * 담다 세션은 이미 끊겼고, 실패해도 로그만 남기는 지금 규칙과 같다(SSO 쪽 계열은 refresh 만료 7일 안에 저절로 끝난다).
 */
@Slf4j
@Component
public class SsoLoginRevoker {

    private final SsoOAuth2Client ssoOAuth2Client;
    private final AesGcmCipher cipher;
    private final java.util.concurrent.Executor executor;
    /** 끌 때 멈출 풀 — 테스트용 생성자로 만들면 없다 */
    private final ThreadPoolTaskExecutor pool;

    @org.springframework.beans.factory.annotation.Autowired
    public SsoLoginRevoker(SsoOAuth2Client ssoOAuth2Client, AesGcmCipher cipher) {
        this(ssoOAuth2Client, cipher, newPool());
    }

    private SsoLoginRevoker(SsoOAuth2Client ssoOAuth2Client, AesGcmCipher cipher, ThreadPoolTaskExecutor pool) {
        this.ssoOAuth2Client = ssoOAuth2Client;
        this.cipher = cipher;
        this.executor = pool;
        this.pool = pool;
    }

    /** 테스트용 — 넘긴 실행기로 돌린다(예: {@code Runnable::run} 이면 그 자리에서) */
    SsoLoginRevoker(SsoOAuth2Client ssoOAuth2Client, AesGcmCipher cipher, java.util.concurrent.Executor executor) {
        this.ssoOAuth2Client = ssoOAuth2Client;
        this.cipher = cipher;
        this.executor = executor;
        this.pool = null;
    }

    private static ThreadPoolTaskExecutor newPool() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(4);
        pool.setQueueCapacity(200);
        pool.setThreadNamePrefix("sso-revoke-");
        pool.initialize();
        return pool;
    }

    /** 폐기를 맡기고 바로 돌아온다. 실패 · 큐 넘침은 로그만 남긴다. */
    public void revokeLater(String sessionId, String refreshTokenEnc) {
        try {
            executor.execute(() -> revokeNow(sessionId, refreshTokenEnc));
        } catch (java.util.concurrent.RejectedExecutionException e) { // TaskRejectedException 포함
            log.warn("SSO 로그인 계열 폐기를 맡기지 못했다(대기열 가득) — 담다 세션만 끊었다. sessionId={}", sessionId);
        }
    }

    /** 지금 이 스레드에서 부른다 — 실패해도 던지지 않는다. */
    void revokeNow(String sessionId, String refreshTokenEnc) {
        try {
            if (!ssoOAuth2Client.revokeRefreshToken(cipher.decrypt(refreshTokenEnc))) {
                log.warn("SSO 로그인 계열 폐기 실패 — 담다 세션만 끊었다. sessionId={}", sessionId);
            }
        } catch (Exception e) {
            log.error("SSO 로그인 계열 폐기 중 오류 — 담다 세션만 끊었다. sessionId={}, err={}", sessionId, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        if (pool != null) {
            pool.shutdown();
        }
    }
}
