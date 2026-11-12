package io.groo.damda.calendar.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 보안 점검 DAM-4 — 초대 코드 틀린 시도 한도. */
class CalendarJoinRateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final CalendarJoinRateLimiter sut = new CalendarJoinRateLimiter(now::get);

    @Test
    @DisplayName("사용자 한 명이 10분에 10번 틀리면 막고, 창이 지나면 풀린다")
    void perUserWindow() {
        for (int i = 0; i < CalendarJoinRateLimiter.MAX_FAILURES_PER_USER; i++) {
            assertThat(sut.isAllowed(1L, "10.0.0." + i)).isTrue();
            sut.recordFailure(1L, "10.0.0." + i); // IP 를 바꿔도 사용자 기준으로 센다
        }
        assertThat(sut.isAllowed(1L, "10.0.0.99")).isFalse();
        assertThat(sut.isAllowed(2L, "10.0.0.99")).isTrue(); // 다른 사람은 영향 없음

        now.addAndGet(CalendarJoinRateLimiter.WINDOW_MILLIS);
        assertThat(sut.isAllowed(1L, "10.0.0.99")).isTrue();
    }

    @Test
    @DisplayName("IP 하나에서 여러 계정으로 30번 틀리면 그 IP 를 막는다 — 계정을 바꿔 가며 대입하는 것")
    void perIpWindow() {
        for (int i = 0; i < CalendarJoinRateLimiter.MAX_FAILURES_PER_IP; i++) {
            sut.recordFailure((long) i, "198.51.100.1");
        }
        assertThat(sut.isAllowed(999L, "198.51.100.1")).isFalse();
        assertThat(sut.isAllowed(999L, "198.51.100.2")).isTrue();
    }

    @Test
    @DisplayName("IP 를 모르면 한 칸(unknown)으로 묶어 센다")
    void unknownIp() {
        for (int i = 0; i < CalendarJoinRateLimiter.MAX_FAILURES_PER_IP; i++) {
            sut.recordFailure((long) i, null);
        }
        assertThat(sut.isAllowed(999L, " ")).isFalse();
    }

    @Test
    @DisplayName("지난 창은 맵이 커지면 걷어 낸다 — 바뀌는 IP 로 메모리가 쌓이지 않는다")
    void sweepsExpiredWindows() {
        for (int i = 0; i < 6_000; i++) {
            sut.recordFailure((long) i, "ip-" + i);
        }
        now.addAndGet(CalendarJoinRateLimiter.WINDOW_MILLIS);
        sut.recordFailure(1L, "fresh"); // 12,000 > 임계값 → 지난 창 정리
        // 정리 뒤에도 새 창은 살아 있다
        assertThat(sut.isAllowed(1L, "fresh")).isTrue();
        for (int i = 1; i < CalendarJoinRateLimiter.MAX_FAILURES_PER_USER; i++) sut.recordFailure(1L, "fresh");
        assertThat(sut.isAllowed(1L, "fresh")).isFalse();
    }
}
