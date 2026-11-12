package io.groo.damda.security.session.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class KeyedMutexTest {

    private final KeyedMutex mutex = new KeyedMutex();

    @Test
    @DisplayName("같은 키는 한 명만 쥔다 — 기다리다 시간이 넘으면 false, 줄 기록도 남기지 않는다")
    void sameKeyIsExclusive() throws Exception {
        assertThat(mutex.tryLock("a", 1, TimeUnit.SECONDS)).isTrue();

        boolean other = CompletableFuture.supplyAsync(() -> {
            try {
                return mutex.tryLock("a", 50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }).get(5, TimeUnit.SECONDS);

        assertThat(other).isFalse();
        assertThat(mutex.queueLength("a")).isEqualTo(1);

        mutex.unlock("a");
        assertThat(mutex.queueLength("a")).as("다 빠지면 항목을 지운다").isZero();
    }

    @Test
    @DisplayName("다른 키는 서로 기다리지 않는다")
    void differentKeysAreIndependent() throws Exception {
        assertThat(mutex.tryLock("a", 1, TimeUnit.SECONDS)).isTrue();

        boolean other = CompletableFuture.supplyAsync(() -> {
            try {
                boolean got = mutex.tryLock("b", 50, TimeUnit.MILLISECONDS);
                if (got) mutex.unlock("b");
                return got;
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }).get(5, TimeUnit.SECONDS);

        assertThat(other).isTrue();
        mutex.unlock("a");
    }
}
