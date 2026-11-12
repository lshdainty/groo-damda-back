package io.groo.damda.dataimport.sms.service.parser;

import io.groo.damda.dataimport.sms.service.SmsParsed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 보안 점검 DAM-3 — 긴 입력 하나가 파서의 CPU 를 붙잡지 못하게 한다.
 *
 * <p>시간 한도(1초)는 넉넉하게 잡았다. 고치기 전에는 아래 입력이 수십 초 이상 걸렸다.
 */
class PaymentTextInputLimitTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);
    private static final Duration LIMIT = Duration.ofSeconds(1);

    private final SmsPaymentParser parser = new SmsPaymentParser();

    /** 결제 게이트를 통과하는 머리말 + 통화 코드 뒤에 숫자가 없는 긴 숫자열. */
    private static String hostile(int digits) {
        return "신한카드 승인 10,000원\n" + "1".repeat(digits) + "X USD";
    }

    @Nested
    @DisplayName("본문 길이 상한")
    class TextLength {

        @Test
        @DisplayName("2,000자를 넘는 본문은 정규식에 닿기 전에 결제 아님으로 본다")
        void overLimitIsNoMatch() {
            String text = hostile(20_000);

            SmsParsed p = assertTimeoutPreemptively(LIMIT, () -> PaymentTextParsers.parse(text, TODAY));

            assertThat(p.matched()).isFalse();
            assertThat(PaymentTextParsers.looksLikePayment(text)).isFalse();
        }

        @Test
        @DisplayName("상한까지는 그대로 읽는다 — 결제 문자 뒤에 공백이 길게 붙어도 2,000자 안이면 통과")
        void atLimitStillParses() {
            String head = "신한카드(1234)승인 홍*동 5,500원 일시불 08/13 13:22 스타벅스";
            String text = head + " ".repeat(PaymentTextParsers.MAX_TEXT_LENGTH - head.length());
            assertThat(text).hasSize(PaymentTextParsers.MAX_TEXT_LENGTH);

            SmsParsed p = PaymentTextParsers.parse(text, TODAY);

            assertThat(p.matched()).isTrue();
            assertThat(p.amount()).isEqualTo(5_500L);
        }
    }

    @Nested
    @DisplayName("외화 금액 정규식 자릿수 상한")
    class ForeignAmount {

        @Test
        @DisplayName("상한 안의 긴 숫자열(1,900자리)도 금방 끝난다 — 종전엔 자릿수의 세제곱으로 돌았다")
        void longDigitRunFinishesFast() {
            String text = hostile(1_900);
            assertThat(text.length()).isLessThanOrEqualTo(PaymentTextParsers.MAX_TEXT_LENGTH);

            BigDecimal amount = assertTimeoutPreemptively(LIMIT,
                () -> parser.findForeignAmount(text, "USD"));

            assertThat(amount).isNull();
        }

        @Test
        @DisplayName("전체 파싱도 같은 입력에서 금방 끝나고 외화 정보는 버린다")
        void fullParseFinishesFast() {
            String text = hostile(1_900);

            SmsParsed p = assertTimeoutPreemptively(LIMIT, () -> PaymentTextParsers.parse(text, TODAY));

            assertThat(p.originalCurrency()).isNull();
            assertThat(p.originalAmount()).isNull();
        }

        @Test
        @DisplayName("상한보다 긴 숫자는 꼬리만 잘라 읽지 않는다")
        void tooLongNumberIsNotAnAmount() {
            assertThat(parser.findForeignAmount("12345678901234567890 USD", "USD")).isNull();
            assertThat(parser.findForeignAmount("USD 12345678901234567890", "USD")).isNull();
        }

        @Test
        @DisplayName("실제 포맷 — 코드 앞·뒤, 쉼표, 소수, 붙여 쓰기, 문장 끝 마침표")
        void realFormats() {
            assertThat(parser.findForeignAmount("USD 12.34 (16,500원)", "USD"))
                .isEqualByComparingTo("12.34");
            assertThat(parser.findForeignAmount("12.34 USD", "USD")).isEqualByComparingTo("12.34");
            assertThat(parser.findForeignAmount("1,234.56USD", "USD")).isEqualByComparingTo("1234.56");
            assertThat(parser.findForeignAmount("JPY 1,200", "JPY")).isEqualByComparingTo("1200");
            assertThat(parser.findForeignAmount("결제 USD 9.99.", "USD")).isEqualByComparingTo("9.99");
            assertThat(parser.findForeignAmount("VND 1,250,000,000", "VND"))
                .isEqualByComparingTo("1250000000");
        }
    }
}
