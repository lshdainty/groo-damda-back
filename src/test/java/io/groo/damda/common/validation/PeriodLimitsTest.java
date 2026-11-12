package io.groo.damda.common.validation;

import io.groo.core.exception.InvalidValueException;
import io.groo.damda.common.exception.DeskErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 보안 점검 DAM-2 — 기간 조회의 연도 범위·최대 폭. */
class PeriodLimitsTest {

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    @Test
    @DisplayName("0001~9999 처럼 ISO 가 받는 끝까지 넓힌 기간은 거부한다")
    void rejectsIsoExtremes() {
        assertThatThrownBy(() -> PeriodLimits.check(d(1, 1, 1), d(9999, 12, 31)))
            .isInstanceOf(InvalidValueException.class)
            .extracting("errorCode").isEqualTo(DeskErrorCode.PERIOD_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("화면 달력이 고를 수 있는 가장 넓은 기간(오늘±100년의 양 끝)은 통과한다")
    void acceptsWidestPickerRange() {
        // 2026년 기준 웹·앱 달력의 yearRange 기본값 = 1926~2126
        assertThatCode(() -> PeriodLimits.check(d(1926, 1, 1), d(2126, 12, 31)))
            .doesNotThrowAnyException();
        // 2099년(이 상수가 달력을 품는 마지막 해) 기준 = 1999~2199
        assertThatCode(() -> PeriodLimits.check(d(1999, 1, 1), d(2199, 12, 31)))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("폭이 201년을 넘으면 거부한다 — 경계는 시작 + 201년의 전날까지")
    void rejectsWiderThanMaxSpan() {
        assertThatCode(() -> PeriodLimits.check(d(1950, 3, 1), d(2151, 2, 28)))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> PeriodLimits.check(d(1950, 3, 1), d(2151, 3, 1)))
            .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("연도 범위 밖은 한쪽만 와도 거부한다(열린 기간)")
    void rejectsOutOfYearsOnOpenRange() {
        assertThatThrownBy(() -> PeriodLimits.check(d(1899, 12, 31), null))
            .isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> PeriodLimits.check(null, d(2200, 1, 1)))
            .isInstanceOf(InvalidValueException.class);
        assertThatCode(() -> PeriodLimits.check(d(1900, 1, 1), null)).doesNotThrowAnyException();
        assertThatCode(() -> PeriodLimits.check(null, d(2199, 12, 31))).doesNotThrowAnyException();
        assertThatCode(() -> PeriodLimits.check(null, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("통계·내보내기의 일상 기간(한 달·한 해)은 통과한다")
    void acceptsEverydayRanges() {
        assertThatCode(() -> PeriodLimits.check(d(2026, 11, 1), d(2026, 11, 30))).doesNotThrowAnyException();
        assertThatCode(() -> PeriodLimits.check(d(2026, 1, 1), d(2026, 12, 31))).doesNotThrowAnyException();
    }
}
