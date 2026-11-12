package io.groo.damda.calendar.service;

import lombok.extern.slf4j.Slf4j;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 반복 일정(rrule)을 조회 구간의 발생(occurrence) 시각들로 전개한다.
 *
 * <p>지금까지 rrule 은 저장·전달만 되고 어느 쪽도 전개하지 않아, 매주 반복이
 * 첫 회차 한 번만 화면에 남았다(신규 가입 E2E 발견). 전개를 서버에 두는 이유:
 * 웹·앱 두 클라이언트가 같은 결과를 공짜로 받고, 구간 밖 원본을 포함하는 조회
 * 조건과 전개 규칙이 한곳에 붙어 있어야 어긋나지 않는다.
 *
 * <p>전개는 <b>조회 구간 기준</b>이다 — 시작일이 아무리 과거여도 구간 안 발생은
 * 빠지지 않는다. 예전에는 시작일부터 회차를 세며 1200 회에서 잘라, 3년 전 시작한
 * 매일 반복이 이번 달 달력에서 조용히 사라졌다(QA #35). 지금은 구간 첫 회차로
 * 바로 점프하고, 상한은 "구간 안 발생 수" 에 걸리며 도달하면 로그를 남긴다.
 *
 * <p>지원 범위는 <b>클라이언트 폼이 만드는 값 그대로</b>다 — 순수
 * {@code FREQ=DAILY|WEEKLY|MONTHLY|YEARLY} (INTERVAL·UNTIL·COUNT 없음, 웹·앱
 * EventForm 공통). 그 밖의 rrule 은 전개하지 않고 원본 1회만 돌려준다 — 모르는
 * 규칙을 임의 해석해 엉뚱한 날짜를 만드는 것보다 안전하다.
 *
 * <p>MONTHLY 는 시작일의 "일(day-of-month)" 을 매달 유지하되 그 일이 없는 달은
 * <b>말일로 내린다</b>(1/31 → 2/28) — 반복 거래의 안내문("해당 일이 없는 달은
 * 말일에 처리됩니다")과 같은 규칙. YEARLY 도 2/29 → 2/28 같은 방식이다.
 *
 * <p>발생마다 원본의 지속시간(start~end)이 그대로 따라간다. recurrence_id 기반
 * 예외(한 회차만 수정/삭제)는 아직 만들어지는 곳이 없어 다루지 않는다 — 생기면
 * 여기서 그 날짜의 발생을 빼는 것으로 확장한다.
 */
@Slf4j
public final class RecurrenceExpander {

    /**
     * 한 이벤트가 <b>조회 구간 안에서</b> 만들 수 있는 발생 수 상한 — 무한 전개 방어용.
     *
     * <p>시작일이 아니라 구간 기준이다. 일정 목록 조회는 서비스가 구간 폭을
     * {@code CalendarEventServiceImpl.MAX_QUERY_SPAN_DAYS}(62일)로 막으므로 DAILY 도
     * 63회를 넘지 않는다 — 이 상한은 그 검사를 거치지 않는 호출을 위한 마지막 방어선이고,
     * 걸린다면 구간이 비정상적으로 넓다는 뜻이라 로그가 남는다.
     */
    static final int MAX_OCCURRENCES_IN_RANGE = 2000;

    private RecurrenceExpander() {
    }

    public record Occurrence(LocalDateTime startDate, LocalDateTime endDate) {
    }

    /**
     * [rangeStart, rangeEnd] 와 겹치는 발생들을 시각 순서로 돌려준다.
     * rrule 이 없거나 지원 밖이면 원본 1회(구간과 겹칠 때)만 돌려준다.
     */
    public static List<Occurrence> expand(LocalDateTime baseStart, LocalDateTime baseEnd, String rrule,
                                          LocalDateTime rangeStart, LocalDateTime rangeEnd) {
        Duration duration = Duration.between(baseStart, baseEnd);
        Freq freq = parse(rrule);
        if (freq == null) {
            return overlaps(baseStart, baseEnd, rangeStart, rangeEnd)
                ? List.of(new Occurrence(baseStart, baseEnd))
                : List.of();
        }

        // 시작일부터 세지 않고 구간 첫 회차로 바로 점프한다 — 3년 전 시작한 매일
        // 반복도 이번 달 발생이 그대로 나와야 한다(QA #35).
        List<Occurrence> out = new ArrayList<>();
        long n = firstIndexReaching(baseStart, duration, freq, rangeStart);
        for (int guard = 0; guard < MAX_OCCURRENCES_IN_RANGE; guard++, n++) {
            LocalDateTime start = occurrenceStart(baseStart, freq, n);
            if (start.isAfter(rangeEnd)) {
                return out;   // 정상 종료 경로
            }
            LocalDateTime end = start.plus(duration);
            if (overlaps(start, end, rangeStart, rangeEnd)) {
                out.add(new Occurrence(start, end));
            }
        }
        // 상한만큼 채우고 빠져나왔다. 바로 다음 회차가 이미 구간 밖이면 자른 건 없다 —
        // 발생 수가 상한과 딱 맞아떨어졌을 뿐이라 경고할 일이 아니다.
        if (occurrenceStart(baseStart, freq, n).isAfter(rangeEnd)) {
            return out;
        }
        // 여기 오면 구간이 비정상적으로 넓다. 조용히 자르면 예전처럼 아무도 모른다.
        log.warn("반복 전개 상한 도달 — 구간 안 발생을 잘랐다: rrule={}, baseStart={}, range={}~{}, cap={}",
            rrule, baseStart, rangeStart, rangeEnd, MAX_OCCURRENCES_IN_RANGE);
        return out;
    }

    /** 폼이 만드는 순수 FREQ 값만 인식한다. 나머지(확장 파라미터 포함)는 null. */
    static Freq parse(String rrule) {
        if (rrule == null) return null;
        return switch (rrule.trim()) {
            case "FREQ=DAILY" -> Freq.DAILY;
            case "FREQ=WEEKLY" -> Freq.WEEKLY;
            case "FREQ=MONTHLY" -> Freq.MONTHLY;
            case "FREQ=YEARLY" -> Freq.YEARLY;
            default -> null;
        };
    }

    /**
     * {@code end >= rangeStart} 를 처음 만족하는 회차 번호 — 구간 앞쪽 발생을 건너뛰는 점프.
     *
     * <p>{@code occurrenceStart} 가 n 에 대해 단조 증가하므로(plusDays/Weeks/Months/Years
     * 전부 단조) 그 조건을 처음 만족하는 n 아래는 전부 버려도 안전하다. 근사치는
     * {@link ChronoUnit#between} 으로 잡고 두 회차 물러선 뒤 앞으로 걸어 보정한다 —
     * MONTHLY·YEARLY 말일 클램프 때문에 근사치가 한두 회차 어긋날 수 있다.
     */
    private static long firstIndexReaching(LocalDateTime base, Duration duration, Freq freq,
                                           LocalDateTime rangeStart) {
        // 여러 날에 걸친 원본은 구간 시작 전에 시작해도 구간에 걸친다 —
        // duration 만큼 앞을 앵커로 잡아야 그 발생을 통째로 건너뛰지 않는다.
        Duration back = duration.isNegative() ? Duration.ZERO : duration;
        LocalDateTime anchor;
        try {
            anchor = rangeStart.minus(back);
        } catch (DateTimeException | ArithmeticException overflow) {
            return 0;   // 비정상적으로 긴 원본 — 0회차부터 훑는다
        }
        if (!anchor.isAfter(base)) {
            return 0;   // 원본이 구간 안이거나 뒤 → 점프할 게 없다
        }

        long n = switch (freq) {
            case DAILY -> ChronoUnit.DAYS.between(base, anchor);
            case WEEKLY -> ChronoUnit.WEEKS.between(base, anchor);
            case MONTHLY -> ChronoUnit.MONTHS.between(base, anchor);
            case YEARLY -> ChronoUnit.YEARS.between(base, anchor);
        };
        n = Math.max(0, n - 2);
        // 구간에 전혀 못 미치는 발생만 건너뛴다(보통 0~3회 돈다).
        while (occurrenceStart(base, freq, n).plus(back).isBefore(rangeStart)) {
            n++;
        }
        return n;
    }

    private static LocalDateTime occurrenceStart(LocalDateTime base, Freq freq, long n) {
        if (n == 0) return base;
        return switch (freq) {
            case DAILY -> base.plusDays(n);
            case WEEKLY -> base.plusWeeks(n);
            // plusMonths/plusYears 는 없는 일자를 말일로 내린다(1/31+1개월=2/28) —
            // 원하는 규칙 그대로라 별도 클램프가 필요 없다. 단 "31일 시작이 2월을
            // 지나며 28일로 줄어드는" 누적 오차를 피하려고 항상 base 에서 더한다.
            // (구간으로 점프해도 base 에서 한 번에 더하므로 값이 달라지지 않는다.)
            case MONTHLY -> base.plusMonths(n);
            case YEARLY -> base.plusYears(n);
        };
    }

    private static boolean overlaps(LocalDateTime start, LocalDateTime end,
                                    LocalDateTime rangeStart, LocalDateTime rangeEnd) {
        return !start.isAfter(rangeEnd) && !end.isBefore(rangeStart);
    }

    enum Freq { DAILY, WEEKLY, MONTHLY, YEARLY }
}
