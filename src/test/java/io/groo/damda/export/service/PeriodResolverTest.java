package io.groo.damda.export.service;

import io.groo.damda.export.service.PeriodResolver.DateRange;
import io.groo.damda.export.type.ExportPeriod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 오늘은 인자로 넘기는 순수 함수라 시계에 기대지 않는다. */
class PeriodResolverTest {

    private final PeriodResolver resolver = new PeriodResolver();

    private DateRange thisYear(LocalDate today) {
        return resolver.resolve(ExportPeriod.THIS_YEAR, null, null, today);
    }

    @Test
    @DisplayName("올해는 연초에도 12월 31일까지다")
    void thisYear_onJan1() {
        assertThat(thisYear(LocalDate.of(2026, 1, 1)))
                .isEqualTo(new DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
    }

    @Test
    @DisplayName("올해는 연중에도 12월 31일까지다")
    void thisYear_midYear() {
        assertThat(thisYear(LocalDate.of(2026, 11, 7)))
                .isEqualTo(new DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
    }

    @Test
    @DisplayName("올해는 12월 31일에도 같은 범위다")
    void thisYear_onDec31() {
        assertThat(thisYear(LocalDate.of(2026, 12, 31)))
                .isEqualTo(new DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
    }

    @Test
    @DisplayName("올해는 윤년(2028)에도 12월 31일까지다")
    void thisYear_leapYear() {
        assertThat(thisYear(LocalDate.of(2028, 2, 29)))
                .isEqualTo(new DateRange(LocalDate.of(2028, 1, 1), LocalDate.of(2028, 12, 31)));
    }

    @Test
    @DisplayName("다른 프리셋은 그대로다")
    void otherPresetsUnchanged() {
        LocalDate today = LocalDate.of(2026, 2, 10);
        assertThat(resolver.resolve(ExportPeriod.THIS_MONTH, null, null, today))
                .isEqualTo(new DateRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)));
        assertThat(resolver.resolve(ExportPeriod.LAST_MONTH, null, null, today))
                .isEqualTo(new DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));
        assertThat(resolver.resolve(ExportPeriod.LAST_3_MONTHS, null, null, today))
                .isEqualTo(new DateRange(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 2, 28)));
    }
}
