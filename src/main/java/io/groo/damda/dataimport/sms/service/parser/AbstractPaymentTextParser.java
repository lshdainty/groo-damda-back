package io.groo.damda.dataimport.sms.service.parser;

import io.groo.damda.dataimport.sms.service.SmsCardIssuer;
import io.groo.damda.dataimport.sms.service.SmsConfidence;
import io.groo.damda.dataimport.sms.service.SmsParsed;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 필드별 추출을 담당하는 공통 파서. 소스별 구현이 이걸 상속해 쓴다.
 *
 * <p><b>왜 카드사별 통짜 정규식이 아닌가</b> — 같은 카드사도 상품·발송 시스템에 따라
 * 줄 구성이 바뀐다(승인 문구가 앞에 오기도 뒤에 오기도, 이름이 있기도 없기도).
 * 포맷마다 전체 매칭 정규식을 두면 줄 하나만 달라져도 통째로 실패한다.
 * 그래서 <b>필드별로 독립 추출</b>한다 — 금액은 금액대로, 일시는 일시대로 찾는다.
 * 한 필드가 안 잡혀도 나머지는 살아남고, 신뢰도만 내려가 사용자 확인을 거친다.
 * 이 성질 덕에 같은 파서를 문자·카드사 앱·은행 앱 알림에 그대로 쓴다.
 *
 * <p>연도는 문자에 없다(대부분 {@code MM/DD}). 현재 날짜 기준으로 유추하되,
 * 유추 결과가 미래면 작년으로 내린다 — 12/30 결제 문자를 1/2 에 붙여넣는 경우다.
 *
 * <p>시각은 <b>KST 벽시계 그대로</b> 둔다. 타임존 변환을 태우면 자정 근처 결제의
 * 날짜가 하루 밀린다.
 *
 * <p>추출 메서드는 {@code protected} 다 — 특정 기관만 규칙이 다르면 그 기관 전용
 * 파서에서 해당 메서드 하나만 재정의하면 된다(나머지는 여기 것을 그대로 쓴다).
 */
public abstract class AbstractPaymentTextParser implements PaymentTextParser {

    /** 소스 유형별 일반 파서의 기본 순위. 기관 전용 파서는 이보다 낮게 준다. */
    protected static final int DEFAULT_PRIORITY = 100;

    /** 금액 — "5,500원". 소수점은 원화에 없다. */
    protected static final Pattern AMOUNT = Pattern.compile("([0-9][0-9,]{0,15})\\s*원");

    /**
     * 금액 앞에 붙으면 결제액이 아닌 말들.
     * "누적 123,456원", "잔액 1,000원" 을 결제액으로 읽으면 엉뚱한 지출이 생긴다.
     */
    protected static final List<String> AMOUNT_NOISE =
        List.of("누적", "잔액", "한도", "합계", "사용가능", "가용", "잔여", "총");

    /** 할부 — "3개월", "무이자 6개월". */
    protected static final Pattern INSTALLMENT = Pattern.compile("([0-9]{1,2})\\s*개월");

    /** 일시 — "08/13 13:22", "08.13 13:22", "08-13 13:22". 초는 버린다. */
    protected static final Pattern DATE_TIME = Pattern.compile(
        "([0-9]{1,2})[/.\\-]([0-9]{1,2})\\s+([0-9]{1,2}):([0-9]{2})(?::[0-9]{2})?");

    /** 일시(시각 없음) — "08/13". 시각이 안 붙는 짧은 포맷용. */
    protected static final Pattern DATE_ONLY =
        Pattern.compile("([0-9]{1,2})[/.]([0-9]{1,2})(?![0-9:/.])");

    /** 카드 끝 4자리 — 괄호 안. "신한카드(1234)승인". */
    protected static final Pattern LAST4_PAREN = Pattern.compile("\\(([0-9]{4})\\)");

    /** 카드 끝 4자리 — 마스킹 뒤. "****1234", "**** 1234". */
    protected static final Pattern LAST4_MASKED = Pattern.compile("\\*+\\s*([0-9]{4})");

    /** 카드 끝 4자리 — "승인" 바로 앞. "KB국민카드1234승인". */
    protected static final Pattern LAST4_BEFORE_APPROVE = Pattern.compile("([0-9]{4})\\s*승인");

    /** 카드 끝 4자리 — "카드" 바로 뒤. "카드 1234". */
    protected static final Pattern LAST4_AFTER_CARD =
        Pattern.compile("카드\\s*([0-9]{4})(?![0-9])");

    /** 지원 통화 — 해외 결제 문자에 붙는 코드. 원화는 여기 없다(기본 통화라서). */
    protected static final List<String> CURRENCIES =
        List.of("USD", "JPY", "EUR", "CNY", "GBP", "HKD", "SGD", "THB", "VND", "AUD", "CAD");

    /** 가맹점 자리에서 잘라내야 할 꼬리말. 이 뒤는 가맹점명이 아니다. */
    protected static final List<String> MERCHANT_TAIL_MARKERS =
        List.of("누적", "잔액", "한도", "합계", "사용가능", "가용", "잔여", "총",
            "출금가능", "출금가능액", "http", "www.");

    /** 줄 안에 시각·카드번호가 있으면 가맹점 줄이 아니다("카드(6678) | 08/14 19:09"). */
    protected static final Pattern TIME_IN_LINE = Pattern.compile("\\d{1,2}[:/]\\d{2}");
    protected static final Pattern CARD_NO_IN_LINE = Pattern.compile("카드\\s*\\(?\\d{4}");

    /** 가맹점으로 보면 안 되는 줄 — 카드사·사람 이름·상태어만 있는 줄. */
    protected static final List<String> MERCHANT_NOISE_TOKENS =
        List.of("승인", "취소", "일시불", "체크", "신용", "결제", "님", "web발신", "[web발신]");

    @Override
    public int priority() {
        return DEFAULT_PRIORITY;
    }

    /**
     * 필드를 하나씩 뽑아 결과로 묶는다.
     *
     * <p>여기는 소스가 달라도 같다 — 소스별로 다른 것은 "이 텍스트를 결제로 볼 것인가"
     * 라는 진입 판정({@link #accepts})뿐이다.
     */
    @Override
    public SmsParsed parse(String text, LocalDate today) {
        if (!accepts(text)) {
            return SmsParsed.noMatch();
        }
        String normalized = stripWebPrefix(text);

        Long amount = findAmount(normalized);
        if (amount == null) {
            return SmsParsed.noMatch();
        }

        SmsCardIssuer issuer = SmsCardIssuer.detect(normalized);
        String last4 = findLast4(normalized, issuer);
        boolean cancel = isCancel(normalized);
        Integer installment = findInstallment(normalized);
        LocalDateTime occurredAt = findDateTime(normalized, today);
        String merchant = findMerchant(normalized);
        BigDecimal originalAmount = null;
        String originalCurrency = findCurrency(normalized);
        if (originalCurrency != null) {
            originalAmount = findForeignAmount(normalized, originalCurrency);
            // 통화 코드만 있고 금액을 못 읽으면 외화 정보가 반쪽이다 —
            // 셋이 함께 있어야 의미가 있으므로 버린다.
            if (originalAmount == null) originalCurrency = null;
        }

        SmsConfidence confidence = judge(issuer, occurredAt, merchant, originalCurrency);
        return new SmsParsed(true, confidence, issuer, last4, cancel, amount,
            installment, occurredAt, merchant, originalAmount, originalCurrency);
    }

    // ── 필드별 추출 (기관별로 다르면 이 메서드만 재정의) ──────────

    /**
     * 결제 금액 — "누적/잔액" 류 앞말이 붙은 금액은 건너뛰고 첫 유효 금액을 쓴다.
     *
     * <p>결제액은 거의 항상 본문에서 가장 먼저 나온다. 뒤에 오는 누적·잔액을
     * 먼저 잡으면 실제 결제와 무관한 큰 금액이 지출로 들어간다.
     */
    protected Long findAmount(String text) {
        if (text == null) return null;
        Matcher m = AMOUNT.matcher(text);
        while (m.find()) {
            if (hasNoiseBefore(text, m.start())) continue;
            String digits = m.group(1).replace(",", "");
            if (digits.isEmpty()) continue;
            try {
                long value = Long.parseLong(digits);
                if (value > 0) return value;
            } catch (NumberFormatException ignored) {
                // 자릿수가 long 을 넘는 건 결제액이 아니다 — 다음 후보로.
            }
        }
        return null;
    }

    /** 금액 바로 앞(공백·조사 포함 8글자)에 누적·잔액 같은 말이 있는가. */
    protected boolean hasNoiseBefore(String text, int amountStart) {
        int from = Math.max(0, amountStart - 8);
        String before = text.substring(from, amountStart);
        return AMOUNT_NOISE.stream().anyMatch(before::contains);
    }

    /**
     * 취소 문자인가 — "취소" 가 있으면 취소로 본다.
     *
     * <p>"승인취소" 는 승인과 취소가 함께 있으므로 취소를 우선한다.
     * 반대로 두면 모든 취소가 승인으로 읽혀 환불이 지출로 또 쌓인다.
     */
    protected boolean isCancel(String text) {
        return text.contains("취소");
    }

    /**
     * 할부 개월 — "일시불" 이 있으면 무조건 일시불이다.
     *
     * <p>"일시불" 과 "N개월" 이 한 문자에 같이 있는 경우가 있다
     * (예: 안내 문구에 "3개월 무이자 행사"). 일시불 표기를 우선한다.
     */
    protected Integer findInstallment(String text) {
        if (text.contains("일시불")) return null;
        Matcher m = INSTALLMENT.matcher(text);
        if (!m.find()) return null;
        int months = Integer.parseInt(m.group(1));
        return months > 1 ? months : null;
    }

    /** 결제 일시 — 시각까지 있는 포맷을 먼저 보고, 없으면 날짜만 잡아 00:00 으로 둔다. */
    protected LocalDateTime findDateTime(String text, LocalDate today) {
        Matcher m = DATE_TIME.matcher(text);
        if (m.find()) {
            return buildDateTime(intOf(m, 1), intOf(m, 2), intOf(m, 3), intOf(m, 4), today);
        }
        Matcher d = DATE_ONLY.matcher(text);
        while (d.find()) {
            LocalDateTime built = buildDateTime(intOf(d, 1), intOf(d, 2), 0, 0, today);
            if (built != null) return built;
        }
        return null;
    }

    protected static int intOf(Matcher m, int group) {
        return Integer.parseInt(m.group(group));
    }

    /**
     * 월·일에 연도를 붙인다 — 미래로 나오면 작년이다.
     *
     * <p>기준일보다 하루 넘게 앞서면 작년으로 본다. 하루 여유를 두는 이유는
     * 서버·기기 시계가 조금씩 어긋나기 때문이다(자정 직후 결제가 내일로 계산되는 것 방지).
     */
    protected LocalDateTime buildDateTime(int month, int day, int hour, int minute, LocalDate today) {
        if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59) return null;
        try {
            LocalDateTime candidate = LocalDateTime.of(today.getYear(), month, day, hour, minute);
            if (candidate.toLocalDate().isAfter(today.plusDays(1))) {
                candidate = candidate.minusYears(1);
            }
            return candidate;
        } catch (DateTimeException e) {
            // 2/30 같은 없는 날짜 — 날짜 아닌 숫자를 날짜로 오인한 것이다.
            return null;
        }
    }

    /**
     * 카드 끝 4자리 — 확실한 자리(괄호·마스킹·"승인" 앞·"카드" 뒤)에서만 읽는다.
     *
     * <p>본문 아무 데서나 4자리 숫자를 주우면 금액("5,500원" 의 5500)이나
     * 시각을 카드번호로 오인한다. 그래서 문맥이 붙은 패턴만 인정하고,
     * 못 읽으면 null 로 둔다 — 카드사만으로도 매핑은 기억할 수 있다.
     */
    protected String findLast4(String text, SmsCardIssuer issuer) {
        for (Pattern p : List.of(LAST4_PAREN, LAST4_MASKED, LAST4_BEFORE_APPROVE, LAST4_AFTER_CARD)) {
            Matcher m = p.matcher(text);
            if (m.find()) return m.group(1);
        }
        // 카드사 이름 바로 뒤에 붙는 경우 — "KB국민카드1234" 처럼 별칭이 "카드" 로 끝나지 않을 때.
        if (issuer != null) {
            for (String alias : issuer.aliases()) {
                Matcher m = Pattern.compile(Pattern.quote(alias) + "\\s*([0-9]{4})(?![0-9])").matcher(text);
                if (m.find()) return m.group(1);
            }
        }
        return null;
    }

    /**
     * 가맹점 — 일시 뒤에 오는 텍스트가 가맹점명인 포맷이 압도적으로 많다.
     *
     * <p>일시를 못 찾았거나 그 뒤가 비었으면(케이뱅크처럼 가맹점이 일시보다 앞에
     * 오는 포맷) 뒤에서부터 훑는다. 어느 쪽이든 누적·잔액 같은 꼬리말은 잘라내고,
     * 금액·시각이 든 줄과 카드사·상태어만 남은 줄은 가맹점으로 인정하지 않는다.
     */
    protected String findMerchant(String text) {
        String after = textAfterDateTime(text);
        String candidate = firstMeaningfulLine(after);
        if (candidate != null) return candidate;

        String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String cleaned = cleanMerchant(lines[i]);
            if (cleaned != null) return cleaned;
        }
        return null;
    }

    /** 일시 패턴 뒤의 텍스트 — 일시를 못 찾으면 빈 문자열. */
    protected String textAfterDateTime(String text) {
        Matcher m = DATE_TIME.matcher(text);
        if (m.find()) return text.substring(m.end());
        Matcher d = DATE_ONLY.matcher(text);
        if (d.find()) return text.substring(d.end());
        return "";
    }

    /** 여러 줄 중 가맹점으로 쓸 만한 첫 줄. */
    protected String firstMeaningfulLine(String text) {
        if (text == null || text.isBlank()) return null;
        for (String line : text.split("\\R")) {
            String cleaned = cleanMerchant(line);
            if (cleaned != null) return cleaned;
        }
        return null;
    }

    /**
     * 가맹점 후보 다듬기 — 꼬리말 절단 + 노이즈 판정. 쓸 수 없으면 null.
     */
    protected String cleanMerchant(String raw) {
        if (raw == null) return null;
        String line = raw.trim();
        if (line.isEmpty()) return null;

        // 꼬리말 앞에서 자른다 — "스타벅스 누적123,456원" → "스타벅스".
        for (String marker : MERCHANT_TAIL_MARKERS) {
            int idx = line.toLowerCase(Locale.ROOT).indexOf(marker);
            if (idx > 0) line = line.substring(0, idx).trim();
            else if (idx == 0) return null;
        }
        if (line.isEmpty()) return null;

        // 파이프 뒤가 가맹점인 포맷("SHJ 카드 | 요기요_위대한상상") — 카드 부분을 떼어 낸다.
        // 케이뱅크 "카드(6678) | 08/14 19:09" 처럼 뒤가 시각이면 아래 시각 체크에서 걸린다.
        int pipe = line.lastIndexOf('|');
        if (pipe >= 0 && pipe < line.length() - 1) {
            String tail = line.substring(pipe + 1).trim();
            if (!tail.isEmpty()) line = tail;
        }

        // 숫자·기호만 남은 줄은 가맹점이 아니다(금액·시각 잔여물).
        if (!line.matches(".*[가-힣A-Za-z].*")) return null;

        // 금액이 든 줄은 가맹점이 아니다 — 승인 문구("…승인 2,500원 일시불…")나
        // 잔액 줄("출금가능액 19,510원")이 통째로 가맹점으로 딸려 들어가는 걸 막는다.
        // 가맹점명과 금액은 카드사 문자에서 늘 다른 줄에 온다.
        if (AMOUNT.matcher(line).find()) return null;
        // 시각·카드번호 줄("카드(6678) | 08/14 19:09")도 가맹점이 아니다.
        if (TIME_IN_LINE.matcher(line).find()) return null;
        if (CARD_NO_IN_LINE.matcher(line).find()) return null;

        String lower = line.toLowerCase(Locale.ROOT);
        boolean onlyNoise = MERCHANT_NOISE_TOKENS.stream().anyMatch(lower::equals);
        if (onlyNoise) return null;
        // 카드사 이름만 있는 줄도 가맹점이 아니다.
        for (SmsCardIssuer issuer : SmsCardIssuer.values()) {
            for (String alias : issuer.aliases()) {
                if (lower.equals(alias.toLowerCase(Locale.ROOT))) return null;
            }
        }
        return line.length() > 100 ? line.substring(0, 100) : line;
    }

    /** 외화 통화 코드 — 본문에 코드가 그대로 박히는 포맷만 인정한다. */
    protected String findCurrency(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        for (String code : CURRENCIES) {
            if (upper.contains(code)) return code;
        }
        return null;
    }

    /**
     * 외화 금액 숫자 — 정수부 최대 16자(쉼표 포함, 원화 {@link #AMOUNT} 와 같은 폭), 소수부 최대 4자리.
     *
     * <p>종전 {@code [0-9][0-9,]*\\.?[0-9]*} 는 정수부와 소수부가 둘 다 숫자를 끝없이 먹을 수 있어,
     * 통화 코드가 뒤따르지 않는 긴 숫자열에서 시작점마다 나눌 자리를 전부 다시 시도했다
     * (숫자 길이의 세제곱 — 2,000자리에 수십 초, 보안 점검 DAM-3). 자릿수를 묶으면 시작점마다
     * 시도가 상수로 끝난다. 앞뒤 둘레(숫자 안쪽에서 시작·끝나지 않기)는 상한보다 긴 숫자를
     * 꼬리만 잘라 엉뚱한 금액으로 읽지 않게 한다 — 그런 숫자는 금액이 아니라고 본다.
     */
    private static final String FOREIGN_NUMBER =
        "(?<![0-9,])([0-9][0-9,]{0,15}(?:\\.[0-9]{0,4})?)(?![0-9])";

    /** 외화 금액 — 통화 코드 앞뒤 어느 쪽에 붙어도 읽는다. */
    protected BigDecimal findForeignAmount(String text, String currency) {
        String upper = text.toUpperCase(Locale.ROOT);
        Matcher after = Pattern.compile(currency + "\\s*" + FOREIGN_NUMBER).matcher(upper);
        if (after.find()) return toDecimal(after.group(1));
        Matcher before = Pattern.compile(FOREIGN_NUMBER + "\\s*" + currency).matcher(upper);
        if (before.find()) return toDecimal(before.group(1));
        return null;
    }

    /** 문자열 → BigDecimal. double 을 거치면 소수 자리가 흔들리므로 문자열 생성자만 쓴다. */
    protected BigDecimal toDecimal(String raw) {
        String digits = raw.replace(",", "");
        if (digits.isEmpty() || digits.equals(".")) return null;
        try {
            BigDecimal value = new BigDecimal(digits);
            return value.signum() > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 신뢰도 판정.
     *
     * <p>외화는 원화 청구액이 카드사 환율로 확정되기 전이라 항상 낮게 둔다 —
     * 문자의 원화 금액은 잠정치이고, 청구서에서 달라진다.
     */
    protected SmsConfidence judge(SmsCardIssuer issuer, LocalDateTime occurredAt,
                                  String merchant, String originalCurrency) {
        if (originalCurrency != null) return SmsConfidence.LOW;
        if (issuer == null) return SmsConfidence.LOW;
        if (occurredAt != null && merchant != null) return SmsConfidence.HIGH;
        return SmsConfidence.MEDIUM;
    }

    /** "[Web발신]" 접두 제거 — 통신사가 붙이는 꼬리표라 내용과 무관하다. */
    protected String stripWebPrefix(String text) {
        return text.replace("[Web발신]", "").replace("[web발신]", "").trim();
    }
}
