package io.groo.damda.common.validation;

/**
 * 할부 개월 상한. 금액의 {@link AmountLimits} 와 같은 자리에 둔다.
 *
 * <p>종전엔 상한이 없어 {@code Integer.MAX_VALUE} 개월이 그대로 저장·계산됐고, 회차를 도는
 * {@code CardCycleMath.duesByCycle} 의 {@code seq <= installmentMonths} 반복이 넘쳐 끝나지 않았다
 * (요청 한 건이 스레드 하나를 영원히 붙잡는다 — 보안 점검 DAM-9).
 *
 * <p><b>60개월인 이유</b> — 국내 신용카드 일반 할부는 카드사 공통으로 2~12 · 18 · 24개월이고(웹
 * {@code INSTALLMENT_MONTHS} · 앱 {@code kTxInstallmentMonths} 가 이 목록이다), 일부 카드사의 장기 할부·
 * 자동차 카드 할부가 36 · 48 · 60개월까지 있다. 결제 문자({@code SmsPaymentParser})로 들어오는 그런 할부를
 * 막지 않도록 60개월로 둔다. 회차 계산은 이 값만큼만 돈다.
 */
public interface InstallmentLimits {

    /** 할부 개월 최대값. */
    int MAX_MONTHS = 60;
}
