package io.groo.damda.dataimport.sms.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카드사별 결제 문자 픽스처 회귀 테스트.
 *
 * <p>포맷은 카드사가 언제든 바꾼다. 실제 문자를 그대로 박아 두고 값 하나하나를 못 박아야
 * 파서를 고칠 때 다른 카드사가 깨지는 걸 즉시 안다.
 *
 * <p>연도는 문자에 없으므로 기준일을 고정 주입한다 — 실제 시계로 돌리면
 * 연말·연초에만 실패하는 테스트가 된다.
 */
class SmsParserTest {

    /** 기준일 고정 — 2026-08-14. */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);

    @Nested
    @DisplayName("카드사별 승인 문자")
    class Issuers {

        @Test
        @DisplayName("KB국민 — 카드번호가 카드사명에 붙는 포맷")
        void kb() {
            String sms = """
                [Web발신]
                KB국민카드1234승인
                홍*동
                5,500원 일시불
                08/13 13:22
                스타벅스강남""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.matched()).isTrue();
            assertThat(p.issuer()).isEqualTo(SmsCardIssuer.KB);
            assertThat(p.cardLast4()).isEqualTo("1234");
            assertThat(p.amount()).isEqualTo(5500L);
            assertThat(p.installmentMonths()).isNull();
            assertThat(p.occurredAt()).isEqualTo(LocalDateTime.of(2026, 8, 13, 13, 22));
            assertThat(p.merchant()).isEqualTo("스타벅스강남");
            assertThat(p.cancel()).isFalse();
            assertThat(p.confidence()).isEqualTo(SmsConfidence.HIGH);
        }

        @Test
        @DisplayName("신한 — 카드번호가 괄호에 들어가는 포맷")
        void shinhan() {
            String sms = """
                [Web발신]
                신한카드(1234)승인
                홍*동
                12,000원 일시불
                08/13 19:05
                김밥천국""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.issuer()).isEqualTo(SmsCardIssuer.SHINHAN);
            assertThat(p.cardLast4()).isEqualTo("1234");
            assertThat(p.amount()).isEqualTo(12_000L);
            assertThat(p.merchant()).isEqualTo("김밥천국");
        }

        @Test
        @DisplayName("삼성 — 뒤에 붙는 '누적' 금액을 결제액으로 읽지 않는다")
        void samsung() {
            String sms = """
                [Web발신]
                삼성카드 승인 홍*동
                33,000원 일시불
                08/13 20:11
                이마트성수
                누적 1,234,567원""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.issuer()).isEqualTo(SmsCardIssuer.SAMSUNG);
            assertThat(p.amount()).isEqualTo(33_000L);
            assertThat(p.merchant()).isEqualTo("이마트성수");
        }

        @Test
        @DisplayName("현대 — 이름에 '님'이 붙고 할부가 섞인 포맷")
        void hyundai() {
            String sms = """
                [Web발신]
                현대카드 승인
                홍*동님 7,900원 3개월
                08/13 09:30
                쿠팡""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.issuer()).isEqualTo(SmsCardIssuer.HYUNDAI);
            assertThat(p.amount()).isEqualTo(7_900L);
            assertThat(p.installmentMonths()).isEqualTo(3);
            assertThat(p.merchant()).isEqualTo("쿠팡");
        }

        @Test
        @DisplayName("롯데 — 금액이 승인보다 앞에 오는 포맷")
        void lotte() {
            String sms = """
                [Web발신]
                롯데카드 4,500원 일시불 승인
                홍*동
                08/13 12:00
                GS25강남점""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.issuer()).isEqualTo(SmsCardIssuer.LOTTE);
            assertThat(p.amount()).isEqualTo(4_500L);
            assertThat(p.merchant()).isEqualTo("GS25강남점");
        }

        @Test
        @DisplayName("인터넷은행 체크카드 — 은행 이름으로 온다")
        void internetBank() {
            // 체크카드는 발급 주체가 은행이라 승인 알림이 카드사가 아니라 은행 이름으로 온다.
            String sms = """
                케이뱅크
                체크카드 승인
                5,500원
                08/13 13:22
                스타벅스강남""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.issuer()).isEqualTo(SmsCardIssuer.KBANK);
            assertThat(p.amount()).isEqualTo(5_500L);
            assertThat(p.merchant()).isEqualTo("스타벅스강남");
            assertThat(p.confidence()).isEqualTo(SmsConfidence.HIGH);
        }

        @Test
        @DisplayName("시중은행 체크카드 — 은행 이름을 카드사로 이어 준다")
        void retailBank() {
            // 자산의 institution 이 "국민은행" 인 체크카드를 찾으려면 이 이름이 잡혀야 한다.
            String sms = """
                [Web발신]
                국민은행 체크카드 승인
                12,000원
                08/13 19:05
                김밥천국""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.issuer()).isEqualTo(SmsCardIssuer.KB);
            assertThat(p.amount()).isEqualTo(12_000L);
            assertThat(p.merchant()).isEqualTo("김밥천국");
        }

        @Test
        @DisplayName("체크카드 — 할부 표기가 아예 없다")
        void checkCard() {
            String sms = """
                [Web발신]
                신한체크카드(5678)승인
                홍*동
                3,000원
                08/13 08:15
                투썸플레이스""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.issuer()).isEqualTo(SmsCardIssuer.SHINHAN);
            assertThat(p.cardLast4()).isEqualTo("5678");
            assertThat(p.amount()).isEqualTo(3_000L);
            assertThat(p.installmentMonths()).isNull();
            assertThat(p.merchant()).isEqualTo("투썸플레이스");
        }
    }

    @Nested
    @DisplayName("변형")
    class Variants {

        @Test
        @DisplayName("한 줄로 붙어 온 문자 — 줄바꿈 없이도 필드를 뽑는다")
        void singleLine() {
            String sms = "[Web발신] KB국민카드1234승인 홍*동 5,500원 일시불 08/13 13:22 스타벅스강남";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.amount()).isEqualTo(5_500L);
            assertThat(p.occurredAt()).isEqualTo(LocalDateTime.of(2026, 8, 13, 13, 22));
            assertThat(p.merchant()).isEqualTo("스타벅스강남");
        }

        @Test
        @DisplayName("Web발신 접두가 없어도 동일하게 읽는다")
        void withoutWebPrefix() {
            String sms = """
                KB국민카드1234승인
                5,500원 일시불
                08/13 13:22
                스타벅스강남""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.matched()).isTrue();
            assertThat(p.amount()).isEqualTo(5_500L);
            assertThat(p.merchant()).isEqualTo("스타벅스강남");
        }

        @Test
        @DisplayName("카드번호가 마스킹되면 끝자리는 비우고 카드사만 남긴다")
        void maskedCardNumber() {
            String sms = "[Web발신] KB국민카드1*3*승인 홍*동 5,500원 일시불 08/13 13:22 스타벅스강남";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.cardLast4()).isNull();
            assertThat(p.issuer()).isEqualTo(SmsCardIssuer.KB);
            assertThat(p.cardHint()).isEqualTo("KB국민카드|");
        }

        @Test
        @DisplayName("승인취소 — 취소로 판정한다(승인 글자가 함께 있어도)")
        void cancel() {
            String sms = """
                [Web발신]
                KB국민카드1234승인취소
                홍*동
                5,500원
                08/13 14:00
                스타벅스강남""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.cancel()).isTrue();
            assertThat(p.amount()).isEqualTo(5_500L);
        }

        @Test
        @DisplayName("해외 결제 — 원 통화를 함께 담고 신뢰도를 낮춘다")
        void foreignCurrency() {
            String sms = """
                [Web발신]
                KB국민카드1234승인
                홍*동
                USD 12.34 (16,500원)
                08/13 03:22
                AMAZON.COM""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.amount()).isEqualTo(16_500L);
            assertThat(p.originalCurrency()).isEqualTo("USD");
            assertThat(p.originalAmount()).isEqualByComparingTo(new BigDecimal("12.34"));
            assertThat(p.confidence()).isEqualTo(SmsConfidence.LOW);
        }

        @Test
        @DisplayName("연말 문자를 새해에 읽으면 작년으로 내린다")
        void yearRollback() {
            String sms = """
                KB국민카드1234승인
                5,500원 일시불
                12/30 23:50
                스타벅스강남""";

            SmsParsed p = SmsParser.parse(sms, LocalDate.of(2026, 1, 2));

            assertThat(p.occurredAt()).isEqualTo(LocalDateTime.of(2025, 12, 30, 23, 50));
        }

        @Test
        @DisplayName("가맹점 뒤에 잔액 안내가 붙어도 가맹점만 남긴다")
        void trailingBalance() {
            String sms = """
                신한체크카드(5678)승인
                3,000원
                08/13 08:15
                투썸플레이스 잔액 120,000원""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.merchant()).isEqualTo("투썸플레이스");
            assertThat(p.amount()).isEqualTo(3_000L);
        }

        @Test
        @DisplayName("일시가 없으면 비우고 신뢰도를 내린다 — 나머지는 살린다")
        void withoutDateTime() {
            String sms = """
                KB국민카드1234승인
                5,500원 일시불
                스타벅스강남""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.matched()).isTrue();
            assertThat(p.occurredAt()).isNull();
            assertThat(p.merchant()).isEqualTo("스타벅스강남");
            assertThat(p.confidence()).isEqualTo(SmsConfidence.MEDIUM);
        }

        @Test
        @DisplayName("케이뱅크 앱 알림 — 가맹점이 일시 앞에 오고 뒤에 잔액이 붙는다")
        void kbankAppFormat() {
            // 실측(2026-08). 가맹점("씨유")이 일시보다 앞에 있고, 뒤에는 "출금가능액"
            // 잔액 줄이 붙는다 — 그 잔액을 가맹점으로 잡으면 안 된다.
            String sms = """
                케이뱅크
                승인 4,000원
                씨유(CU) 샤로수길점
                카드(6678) | 08/14 19:09
                출금가능액 19,510원""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.amount()).isEqualTo(4_000L);
            assertThat(p.merchant()).isEqualTo("씨유(CU) 샤로수길점");
            assertThat(p.issuer()).isEqualTo(SmsCardIssuer.KBANK);
            assertThat(p.occurredAt()).isEqualTo(LocalDateTime.of(2026, 8, 14, 19, 9));
        }

        @Test
        @DisplayName("현대카드 앱 알림 — 승인 문구 줄을 가맹점으로 잡지 않는다")
        void hyundaiAppFormat() {
            // 실측(2026-08). 금액·일시가 승인 문구 한 줄에 섞여 있고, 가맹점은 그 다음 줄,
            // 마지막에 "누적" 잔액이 온다. 승인 문구 줄이 통째로 가맹점으로 들어가면 안 된다.
            String sms = """
                현대카드
                이상혁 님, 네이버 현대카드 승인 2,500원 일시불, 8/17 17:05
                제로스토어샤로수길점
                누적333,731원""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.amount()).isEqualTo(2_500L);
            assertThat(p.merchant()).isEqualTo("제로스토어샤로수길점");
            assertThat(p.issuer()).isEqualTo(SmsCardIssuer.HYUNDAI);
        }

        @Test
        @DisplayName("승인 문구가 두 번 겹쳐 와도 가맹점을 옳게 집는다")
        void duplicatedApprovalLine() {
            // 안드로이드 알림의 text·bigText 가 겹쳐 승인 문구가 두 줄로 들어오는 경우.
            String sms = """
                현대카드
                이상혁 님, 네이버 현대카드 승인 2,500원 일시불, 8/17 17:05
                이상혁 님, 네이버 현대카드 승인 2,500원 일시불, 8/17 17:05
                제로스토어샤로수길점
                누적333,731원""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.amount()).isEqualTo(2_500L);
            assertThat(p.merchant()).isEqualTo("제로스토어샤로수길점");
        }

        @Test
        @DisplayName("토스 앱 알림 — 파이프 뒤가 가맹점(카드 이름은 뗀다)")
        void tossAppFormat() {
            String sms = """
                토스
                25,760원 결제
                SHJ 카드 | 요기요_위대한상상""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.amount()).isEqualTo(25_760L);
            assertThat(p.merchant()).isEqualTo("요기요_위대한상상");
            assertThat(p.issuer()).isEqualTo(SmsCardIssuer.TOSS);
        }

        @Test
        @DisplayName("모르는 카드사 — 파싱은 되지만 신뢰도가 낮다")
        void unknownIssuer() {
            String sms = """
                우체국체크카드 승인
                5,500원 일시불
                08/13 13:22
                스타벅스강남""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.matched()).isTrue();
            assertThat(p.issuer()).isNull();
            assertThat(p.confidence()).isEqualTo(SmsConfidence.LOW);
            assertThat(p.amount()).isEqualTo(5_500L);
        }
    }

    @Nested
    @DisplayName("카드사 + 상태어 줄은 거래처가 아니다")
    class IssuerStatusLine {

        @Test
        @DisplayName("'신한카드 승인' 이 따로 한 줄이면 거래처를 비운다")
        void issuerAndStatusLine() {
            // 줄 전체가 "승인" 이나 "신한카드" 와 완전히 같지 않아 예전 판정을 빠져나가
            // 거래처 = "신한카드 승인" 으로 들어갔다.
            String sms = """
                신한카드 승인
                12,000원""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.matched()).isTrue();
            assertThat(p.issuer()).isEqualTo(SmsCardIssuer.SHINHAN);
            assertThat(p.amount()).isEqualTo(12_000L);
            assertThat(p.merchant()).isNull();
        }

        @Test
        @DisplayName("[Web발신] 머리가 붙어도 같다")
        void withWebPrefix() {
            String sms = """
                [Web발신]
                신한카드 승인
                12,000원""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.matched()).isTrue();
            assertThat(p.merchant()).isNull();
        }

        @Test
        @DisplayName("다른 카드사 + 상태어 조합도 거래처가 아니다")
        void otherIssuersAndStatuses() {
            assertThat(SmsParser.parse("KB국민카드 결제\n33,000원", TODAY).merchant()).isNull();
            assertThat(SmsParser.parse("삼성카드 체크 승인\n8,000원", TODAY).merchant()).isNull();
            assertThat(SmsParser.parse("현대카드 승인취소\n8,000원", TODAY).merchant()).isNull();
        }

        @Test
        @DisplayName("은행 앱 알림의 '케이뱅크 체크카드 승인' 줄도 거래처가 아니다")
        void bankIssuerStatusLine() {
            SmsParsed p = SmsParser.parse("케이뱅크 체크카드 승인\n4,000원", TODAY);

            assertThat(p.matched()).isTrue();
            assertThat(p.issuer()).isEqualTo(SmsCardIssuer.KBANK);
            assertThat(p.merchant()).isNull();
        }

        @Test
        @DisplayName("실제 가맹점 줄은 그대로 나온다 — 카드사 줄 뒤에 와도")
        void realMerchantStillParsed() {
            String sms = """
                신한카드 승인
                12,000원
                08/13 19:05
                김밥천국""";

            SmsParsed p = SmsParser.parse(sms, TODAY);

            assertThat(p.merchant()).isEqualTo("김밥천국");
            assertThat(p.confidence()).isEqualTo(SmsConfidence.HIGH);
        }

        @Test
        @DisplayName("가맹점 이름에 카드사 별칭이 섞여 있어도 지우지 않는다")
        void merchantContainingIssuerAlias() {
            // "현대"·"롯데"·"하나" 는 카드사 별칭이지만 가맹점 이름의 일부이기도 하다.
            // 지운 뒤 글자가 남으므로 가맹점으로 인정하고, 값은 원래 줄 그대로 돌려준다.
            assertThat(SmsParser.parse("현대카드 승인\n9,000원\n현대백화점", TODAY).merchant())
                .isEqualTo("현대백화점");
            assertThat(SmsParser.parse("삼성카드 승인\n7,000원\n08/13 12:00\n롯데리아 강남점", TODAY).merchant())
                .isEqualTo("롯데리아 강남점");
            assertThat(SmsParser.parse("하나카드 결제\n15,000원\n하나로마트", TODAY).merchant())
                .isEqualTo("하나로마트");
        }
    }

    @Nested
    @DisplayName("결제 문자가 아닌 입력")
    class NotPayment {

        @Test
        @DisplayName("아무 텍스트는 걸러낸다 — 클립보드가 통째로 서버로 가지 않도록")
        void plainText() {
            assertThat(SmsParser.looksLikePayment("오늘 저녁에 만나자")).isFalse();
            assertThat(SmsParser.parse("오늘 저녁에 만나자", TODAY).matched()).isFalse();
        }

        @Test
        @DisplayName("금액만 있고 결제 키워드가 없으면 결제 문자가 아니다")
        void amountWithoutKeyword() {
            assertThat(SmsParser.looksLikePayment("회비 30,000원 입금 부탁드립니다")).isFalse();
        }

        @Test
        @DisplayName("빈 입력·null 은 조용히 걸러낸다")
        void blank() {
            assertThat(SmsParser.looksLikePayment(null)).isFalse();
            assertThat(SmsParser.looksLikePayment("")).isFalse();
            assertThat(SmsParser.parse("", TODAY).matched()).isFalse();
        }
    }
}
