package io.groo.damda.security.session.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 키(세션 id)마다 따로 거는 프로세스 안 잠금. 같은 키끼리만 줄을 서고 다른 키는 서로 기다리지 않는다.
 *
 * <p>쓰는 키가 끝없이 늘어나므로 대기자·소유자가 모두 빠지면 항목을 지운다(참조 수). 항목의
 * 생성·증감·삭제는 전부 {@link ConcurrentHashMap#compute} 안에서 일어나 원자적이다 — 막 지워진
 * 잠금을 누군가 쥐고 있는 일이 없다.
 */
final class KeyedMutex {

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        /** 이 키의 잠금을 쥐었거나 기다리는 스레드 수. 0 이 되면 항목을 지운다. */
        int holders;
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * 잠금을 얻는다. 얻었으면 반드시 {@link #unlock} 을 불러야 한다.
     *
     * @return 제한 시간 안에 얻었으면 true. false 면 {@link #unlock} 을 부르지 않는다.
     */
    boolean tryLock(String key, long timeout, TimeUnit unit) throws InterruptedException {
        Entry entry = entries.compute(key, (k, e) -> {
            Entry target = e != null ? e : new Entry();
            target.holders++;
            return target;
        });
        boolean acquired = false;
        try {
            acquired = entry.lock.tryLock(timeout, unit);
            return acquired;
        } finally {
            if (!acquired) {
                release(key);
            }
        }
    }

    void unlock(String key) {
        Entry entry = entries.get(key);
        entry.lock.unlock();
        release(key);
    }

    /** 이 키에 걸려 있는(쥐었거나 기다리는) 스레드 수 — 테스트·진단용. */
    int queueLength(String key) {
        Entry entry = entries.get(key);
        return entry == null ? 0 : entry.holders;
    }

    private void release(String key) {
        entries.computeIfPresent(key, (k, e) -> --e.holders == 0 ? null : e);
    }
}
