package io.groo.damda.export.type;

/**
 * 기간 프리셋 (디자인 5종). CUSTOM 은 요청의 startDate/endDate 를 그대로 사용.
 * 경계 규칙은 front/app/back 공통:
 *  - THIS_MONTH:    이번 달 1일 ~ 말일
 *  - LAST_MONTH:    지난 달 1일 ~ 말일
 *  - LAST_3_MONTHS: (이번달-2) 1일 ~ 이번 달 말일
 *  - THIS_YEAR:     1월 1일 ~ 12월 31일
 */
public enum ExportPeriod {
    THIS_MONTH,
    LAST_MONTH,
    LAST_3_MONTHS,
    THIS_YEAR,
    CUSTOM
}
