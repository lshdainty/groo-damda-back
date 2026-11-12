package io.groo.damda.export.service;

import io.groo.damda.common.validation.PeriodLimits;
import io.groo.damda.export.type.ExportPeriod;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 기간 프리셋 → 실제 [start, end] 날짜 범위 해석. front/app 과 동일 규칙.
 */
@Component
public class PeriodResolver {

    public record DateRange(LocalDate start, LocalDate end) {}

    /**
     * @param today 기준 "오늘". 서버(UTC) 기준으로 잡으면 한국 사용자에게 하루 어긋나므로
     *              호출자가 사용자 타임존 기준 날짜를 넘긴다.
     */
    public DateRange resolve(ExportPeriod period, LocalDate customStart, LocalDate customEnd, LocalDate today) {
        YearMonth thisMonth = YearMonth.from(today);

        return switch (period) {
            case THIS_MONTH -> new DateRange(thisMonth.atDay(1), thisMonth.atEndOfMonth());
            case LAST_MONTH -> {
                YearMonth last = thisMonth.minusMonths(1);
                yield new DateRange(last.atDay(1), last.atEndOfMonth());
            }
            case LAST_3_MONTHS -> new DateRange(thisMonth.minusMonths(2).atDay(1), thisMonth.atEndOfMonth());
            case THIS_YEAR -> new DateRange(LocalDate.of(today.getYear(), 1, 1), LocalDate.of(today.getYear(), 12, 31));
            case CUSTOM -> {
                if (customStart == null || customEnd == null) {
                    throw new IllegalArgumentException("기간을 직접 고르려면 시작일과 종료일이 필요해요");
                }
                if (customStart.isAfter(customEnd)) {
                    throw new IllegalArgumentException("시작일이 종료일보다 늦을 수 없어요");
                }
                // 직접 고른 기간만 바깥에서 온다 — 0001~9999 같은 폭을 막는다(보안 점검 DAM-2).
                PeriodLimits.check(customStart, customEnd);
                yield new DateRange(customStart, customEnd);
            }
        };
    }
}
