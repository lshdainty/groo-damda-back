package io.groo.damda.common.logging;

import io.groo.core.logging.UpstreamErrorLog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 업스트림 오류를 로그로 옮길 때의 가림막.
 *
 * <p>여기서 고정하는 건 "마스킹을 호출했다" 가 아니라 <b>결과 문자열에 원래 값이 없다</b> 는 것이다.
 */
class UpstreamErrorLogTest {

    private static final String ACCT_NO = "11111111103";
    private static final String CUST_NO = "9876543210";

    @Nested
    @DisplayName("safe — 업스트림 문자열")
    class Safe {

        @Test
        @DisplayName("JSON 본문의 계좌·고객번호는 core 마스커가 잡는다")
        void masksJsonKeys() {
            String masked = UpstreamErrorLog.safe(
                "{\"rsp_cd\":\"40010\",\"cust_no\":\"" + CUST_NO + "\",\"acct_no\":\"" + ACCT_NO + "\"}");

            assertThat(masked).doesNotContain(ACCT_NO).doesNotContain(CUST_NO);
            assertThat(masked).contains("40010"); // 코드는 남는다 — 진단에 필요하다
        }

        @Test
        @DisplayName("이름 없이 문장에 섞여 온 계좌번호는 뒤 4자리만 남는다 — 마스커가 못 보는 자리")
        void masksBareIdentifierInSentence() {
            String masked = UpstreamErrorLog.safe("계좌번호 " + ACCT_NO + " 를 확인하세요");

            assertThat(masked).doesNotContain(ACCT_NO);
            assertThat(masked).isEqualTo("계좌번호 ****1103 를 확인하세요");
        }

        @Test
        @DisplayName("짧은 숫자는 그대로 둔다 — 금액·수량·코드까지 가리면 로그를 못 쓴다")
        void keepsShortNumbers() {
            assertThat(UpstreamErrorLog.safe("주문수량 100 주, 단가 70000 원"))
                .isEqualTo("주문수량 100 주, 단가 70000 원");
        }

        @Test
        @DisplayName("null·공백은 (없음)")
        void emptyInput() {
            assertThat(UpstreamErrorLog.safe(null)).isEqualTo("(없음)");
            assertThat(UpstreamErrorLog.safe("  ")).isEqualTo("(없음)");
        }
    }

    @Nested
    @DisplayName("redact — cause 로 달아도 되는 예외")
    class Redact {

        @Test
        @DisplayName("Spring 이 예외 메시지에 실어 온 응답 본문을 가린다")
        void hidesResponseBodyCarriedInMessage() {
            // DefaultResponseErrorHandler 가 만드는 모양 그대로 — 상태줄 뒤에 응답 본문이 붙는다.
            String body = "{\"rsp_cd\":\"40010\",\"cust_no\":\"" + CUST_NO + "\"}";
            HttpClientErrorException original = HttpClientErrorException.create(
                "400 Bad Request on POST request for \"https://api.nhplug.com:8443/n2/acctinfo\": \"" + body + "\"",
                HttpStatus.BAD_REQUEST, "Bad Request", null,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            // 전제 확인 — 원본 메시지에는 본문이 들어 있다. 이게 사실이 아니면 이 클래스가 필요 없다.
            assertThat(original.getMessage()).contains(CUST_NO);

            RestClientException safe = UpstreamErrorLog.redact(original);

            assertThat(safe.getMessage()).doesNotContain(CUST_NO);
            assertThat(safe.getMessage()).contains("BadRequest"); // 무엇이 터졌는지는 남는다
            assertThat(safe.getCause()).isNull();                 // 원본을 cause 로 달지 않는다
        }

        @Test
        @DisplayName("I/O 원인은 그대로 물려 진단 정보를 잃지 않는다")
        void keepsIoCause() {
            SocketTimeoutException timeout = new SocketTimeoutException("Read timed out");

            RestClientException safe = UpstreamErrorLog.redact(
                new ResourceAccessException("I/O error on POST request", timeout));

            assertThat(safe.getCause()).isSameAs(timeout);
            assertThat(safe.getMessage()).contains("ResourceAccessException");
        }
    }
}
