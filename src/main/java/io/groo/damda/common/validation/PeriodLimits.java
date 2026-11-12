package io.groo.damda.common.validation;

import io.groo.core.exception.InvalidValueException;
import io.groo.damda.common.exception.DeskErrorCode;

import java.time.LocalDate;

/**
 * 기간 조회의 연도 범위·최대 폭. 날짜 두 개를 받는 조회가 공통으로 태운다.
 *
 * <p>종전엔 시작 ≤ 종료만 봤다. ISO 날짜는 {@code 0001-01-01} 부터 {@code 9999-12-31} 까지
 * 그대로 파싱되므로, 그 폭을 달마다 도는 조회(내보내기의 예산 표·기간 요약의 월별 버킷)가
 * 요청 한 건에 12만 번을 돌았다(보안 점검 DAM-2).
 *
 * <p><b>값의 근거 — 화면이 보낼 수 있는 가장 넓은 기간을 깨지 않는다.</b>
 * 웹 {@code date-picker.tsx}·앱 {@code p_date_picker.dart} 의 달력은 둘 다 <b>오늘 ± 100년</b>
 * ({@code yearRange} 기본값)까지만 고르게 한다. 그래서
 * <ul>
 *   <li>연도 범위 {@value #MIN_YEAR}~{@value #MAX_YEAR} — 오늘이 2000~2099년인 동안
 *       달력의 양 끝이 이 안에 든다. 앱 거래 필터(2000~2100)도 이 안이다.</li>
 *   <li>최대 폭 {@value #MAX_SPAN_YEARS}년 — 달력의 처음(오늘−100년 1월 1일)부터
 *       끝(오늘+100년 12월 31일)까지를 고른 것이 가장 넓은 정상 요청이다.</li>
 * </ul>
 * 내보내기 프리셋(이번 달·지난달·최근 3개월·올해)과 통계의 월·분기·연 보기는 전부 1년 이하라
 * 훨씬 안쪽이다. 달력의 범위를 넓히면 여기도 같이 넓혀야 한다.
 */
public final class PeriodLimits {

    /** 고를 수 있는 가장 이른 연도. */
    public static final int MIN_YEAR = 1900;

    /** 고를 수 있는 가장 늦은 연도. */
    public static final int MAX_YEAR = 2199;

    /** 시작~종료 최대 폭(년). 종료일은 시작일 + 이 값 년의 전날까지다. */
    public static final int MAX_SPAN_YEARS = 201;

    private PeriodLimits() {
    }

    /**
     * 기간을 검사한다. 한쪽이 비어 있으면(열린 기간) 있는 쪽의 연도만 본다.
     * 시작 &gt; 종료 판정은 호출자 몫이다 — 모듈마다 기존 오류 코드가 따로 있다.
     *
     * @throws InvalidValueException 연도가 범위를 벗어나거나 폭이 상한을 넘으면
     *         ({@link DeskErrorCode#PERIOD_OUT_OF_RANGE})
     */
    public static void check(LocalDate start, LocalDate end) {
        if (outOfYears(start) || outOfYears(end)) {
            throw new InvalidValueException(DeskErrorCode.PERIOD_OUT_OF_RANGE);
        }
        if (start != null && end != null && !end.isBefore(start.plusYears(MAX_SPAN_YEARS))) {
            throw new InvalidValueException(DeskErrorCode.PERIOD_OUT_OF_RANGE);
        }
    }

    private static boolean outOfYears(LocalDate d) {
        return d != null && (d.getYear() < MIN_YEAR || d.getYear() > MAX_YEAR);
    }
}
