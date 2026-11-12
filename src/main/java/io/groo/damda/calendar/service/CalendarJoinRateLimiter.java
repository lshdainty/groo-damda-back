package io.groo.damda.calendar.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 초대 코드 참여의 <b>틀린 시도</b> 한도 — 사용자별·IP별 고정 창.
 *
 * <p>종전엔 한도가 없어 짧은 코드(32비트)를 무작위로 대입할 수 있었다(보안 점검 DAM-4). 코드를 128비트로
 * 늘려 대입 자체가 무의미해졌지만, 아직 바뀌지 않은 옛 코드·앞으로의 실수를 위해 한 겹 더 둔다.
 *
 * <p><b>틀린 시도만 센다.</b> 맞는 코드로 참여한 것은 세지 않는다 — 여러 캘린더에 연달아 참여하는 정상 사용자를
 * 막지 않으려고. 한도를 넘으면 맞는 코드라도 창이 끝날 때까지 받지 않는다(대입 도중 맞힌 것을 통과시키지 않는다).
 *
 * <p>기존 {@code ApiTokenRateLimiter} 와 같은 인스턴스 메모리 카운터다 — 담다는 인스턴스 하나로 돈다.
 * 재기동하면 비워진다. 창이 지난 항목은 맵이 커질 때 걷어 낸다(IP 를 바꿔 가며 보내도 메모리가 쌓이지 않게).
 */
@Component
public class CalendarJoinRateLimiter {

    /** 사용자 한 명이 창 하나에 틀릴 수 있는 횟수. */
    static final int MAX_FAILURES_PER_USER = 10;
    /** IP 하나가 창 하나에 틀릴 수 있는 횟수 — 한 집·사무실에서 여러 사람이 쓸 수 있어 사용자보다 넉넉하다. */
    static final int MAX_FAILURES_PER_IP = 30;
    /** 창 길이 — 10분. */
    static final long WINDOW_MILLIS = 10 * 60 * 1000L;
    /** 이보다 커지면 지난 창을 걷어 낸다. */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final LongSupplier clockMillis;

    public CalendarJoinRateLimiter() {
        this(System::currentTimeMillis);
    }

    CalendarJoinRateLimiter(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** 지금 시도를 받아도 되는가 — 세지 않는다. */
    public boolean isAllowed(Long userRowId, String clientIp) {
        long now = clockMillis.getAsLong();
        return count(userKey(userRowId), now) < MAX_FAILURES_PER_USER
            && count(ipKey(clientIp), now) < MAX_FAILURES_PER_IP;
    }

    /** 틀린 시도를 센다. */
    public void recordFailure(Long userRowId, String clientIp) {
        long now = clockMillis.getAsLong();
        bump(userKey(userRowId), now);
        bump(ipKey(clientIp), now);
        if (windows.size() > SWEEP_THRESHOLD) {
            windows.entrySet().removeIf(e -> now - e.getValue().startedAt() >= WINDOW_MILLIS);
        }
    }

    private int count(String key, long now) {
        Window w = windows.get(key);
        return w == null || now - w.startedAt() >= WINDOW_MILLIS ? 0 : w.count();
    }

    private void bump(String key, long now) {
        windows.compute(key, (k, old) ->
            old == null || now - old.startedAt() >= WINDOW_MILLIS ? new Window(now, 1) : new Window(old.startedAt(), old.count() + 1));
    }

    private static String userKey(Long userRowId) {
        return "u:" + userRowId;
    }

    private static String ipKey(String clientIp) {
        return "ip:" + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp);
    }

    private record Window(long startedAt, int count) {
    }
}
