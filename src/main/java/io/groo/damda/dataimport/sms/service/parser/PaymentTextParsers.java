package io.groo.damda.dataimport.sms.service.parser;

import io.groo.damda.dataimport.sms.service.SmsParsed;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * 등록된 파서 중 맞는 것을 골라 파싱을 넘기는 진입점.
 *
 * <p>{@link PaymentTextParser#accepts} 가 참인 파서 중 {@link PaymentTextParser#priority}
 * 가 가장 낮은 하나가 맡는다. 특수한 규칙(은행 앱·기관 전용)이 일반 규칙을 이기게
 * 하려는 순서다.
 *
 * <p><b>파서를 늘리는 법</b> — 새 구현을 만들고 아래 {@link #PARSERS} 에 한 줄 더한다.
 * 특정 카드사·은행만 포맷이 유별나면 그 기관 전용 파서를 낮은 priority 로 넣으면
 * 되고, 기존 파서 파일은 손대지 않는다.
 */
public final class PaymentTextParsers {

    private PaymentTextParsers() {
    }

    /**
     * 받아들이는 본문 최대 길이(글자 수). 이보다 길면 결제 문자가 아니라고 본다.
     *
     * <p>근거 — 국내 장문 문자(LMS)의 본문 상한이 2,000바이트(EUC-KR, 한글 1,000자)이고,
     * 앱이 보내는 카드사·은행 알림은 제목 + 펼친 본문 한 통이라 그보다 짧다. 글자 수로 2,000 이면
     * 영문·숫자로만 꽉 채운 LMS 도 들어간다. 종전엔 상한이 없어 20KB 본문 한 통이 정규식에서
     * 코어 하나를 오래 붙잡았다(보안 점검 DAM-3).
     */
    public static final int MAX_TEXT_LENGTH = 2_000;

    /**
     * 우선순위 오름차순으로 정렬해 둔 파서 목록.
     *
     * <p>상태가 없는 순수 파서라 인스턴스를 공유해도 안전하다.
     */
    private static final List<PaymentTextParser> PARSERS = List.<PaymentTextParser>of(
            new BankAppPaymentParser(),   // 50  — 은행 알림(이체 배제)
            new SmsPaymentParser()        // 100 — 문자·카드사 앱
        ).stream()
        .sorted(Comparator.comparingInt(PaymentTextParser::priority))
        .toList();

    /**
     * 결제 알림으로 볼 수 있는가 — 어느 파서든 맡겠다고 하면 참.
     *
     * <p>클라이언트(앱·웹)의 로컬 프리필터와 같은 판정이다. 이 게이트가 있어야
     * 클립보드나 수신함의 아무 텍스트가 서버로 흘러가지 않는다.
     */
    public static boolean looksLikePayment(String text) {
        return find(text) != null;
    }

    /**
     * JVM 기본 타임존의 오늘로 파싱. 컨테이너(UTC)에서는 KST 새벽·연말연시에 연도 유추가
     * 어긋난다 — 사용자 맥락에서는 {@link #parse(String, LocalDate)} 에 사용자 기준 오늘을 넘겨라.
     */
    public static SmsParsed parse(String text) {
        return parse(text, LocalDate.now());
    }

    /**
     * 맞는 파서에게 넘긴다. 맡을 파서가 없으면 "결제 아님" 으로 돌려준다.
     *
     * <p>{@code today} 는 연도 유추 기준일이다 — 문자에 연도가 없어 유추해야 하는데,
     * 시계를 직접 읽으면 테스트가 연말·연초에만 깨진다.
     */
    public static SmsParsed parse(String text, LocalDate today) {
        PaymentTextParser parser = find(text);
        if (parser == null) return SmsParsed.noMatch();
        return parser.parse(text, today);
    }

    /**
     * 맡을 파서를 고른다.
     *
     * <p>전담({@code claims})을 선언한 파서를 만나면 거기서 판정이 끝난다 — 결제로
     * 인정하면 그 파서가 맡고, 아니면 <b>뒤로 넘기지 않고</b> 결제 아님으로 본다.
     * 은행 알림의 계좌이체가 뒤의 느슨한 게이트로 새는 걸 막는 장치다.
     */
    private static PaymentTextParser find(String text) {
        if (text == null || text.isBlank()) return null;
        // 어느 파서의 정규식에도 닿기 전에 자른다 — 결제 문자가 이만큼 길 일은 없다.
        if (text.length() > MAX_TEXT_LENGTH) return null;
        for (PaymentTextParser parser : PARSERS) {
            if (parser.claims(text)) {
                return parser.accepts(text) ? parser : null;
            }
            if (parser.accepts(text)) return parser;
        }
        return null;
    }
}
