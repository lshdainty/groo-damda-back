package io.groo.damda.security.session.support;

/**
 * User-Agent 를 "로그인된 기기" 목록에 쓸 짧은 이름으로 줄인다.
 *
 * <p>예전에는 UA 원문을 200자로 자르기만 했다. 사용자에게
 * {@code Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 ...} 를
 * 보여주는 셈이라, 내 기기인지 남의 기기인지 알아볼 수가 없었다.
 *
 * <p>결과는 {@code 기기 · 브라우저} 형태다 — {@code iPhone · Safari}, {@code Windows · Chrome}.
 * 둘 중 하나만 알아내면 그것만 쓴다.
 *
 * <p><b>라이브러리를 쓰지 않는 이유.</b> ua-parser 류는 정규식 수백 개를 들고 오는데,
 * 우리가 필요한 건 우리 사용자가 실제로 쓰는 몇 가지뿐이다. 못 알아본 UA 는
 * {@code null} 로 두고 화면에서 "알 수 없는 기기" 로 표시하면 되므로, 커버리지를
 * 넓히려고 의존성을 늘릴 이유가 없다.
 *
 * <p><b>순서가 중요하다.</b> UA 문자열은 호환성 때문에 서로를 포함한다 —
 * Edge 는 {@code Chrome} 을, Chrome 은 {@code Safari} 를 UA 에 함께 넣는다.
 * 좁은 것부터 본다.
 */
public final class UserAgentParser {

    private UserAgentParser() {
    }

    /** DB 컬럼(device_label)이 200이지만 이 형식은 훨씬 짧다. 방어적으로만 둔다. */
    private static final int MAX = 60;

    /** 기기와 클라이언트를 잇는 구분자. 만드는 쪽({@link #parse})과 되짚는 쪽({@link #kindOf})이 함께 본다. */
    private static final String SEPARATOR = " · ";

    /**
     * @param userAgent 요청 헤더 원문. {@code null} · 공백 허용
     * @return {@code iPhone · Safari} 같은 짧은 이름, 못 알아보면 {@code null}
     */
    public static String parse(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        // 한 번만 소문자로 만들어 두고 그걸로 본다 — 매 비교마다 만들면 낭비다.
        String ua = userAgent.trim().toLowerCase();

        String device = device(ua);
        String client = client(ua);

        String label;
        if (device != null && client != null) {
            label = device + SEPARATOR + client;
        } else if (device != null) {
            label = device;
        } else {
            label = client;
        }
        if (label == null) {
            return null;
        }
        return label.length() > MAX ? label.substring(0, MAX) : label;
    }

    /**
     * 기기의 형태 — 화면이 아이콘을 고르는 데만 쓴다.
     *
     * <p>이름({@code iPhone · Safari})만으로도 사람은 폰인지 알지만, 화면이 아이콘을
     * 고르려면 문자열을 다시 뜯어야 한다. 그 뜯는 일을 앱·웹에서 각자 하면 같은 표가
     * 두 언어로 복제되고, {@link #device} 를 고칠 때 둘 다 따라오지 않는다.
     */
    public enum DeviceKind {
        MOBILE, TABLET, DESKTOP, UNKNOWN
    }

    /**
     * 저장해 둔 기기 이름에서 형태를 되짚는다.
     *
     * <p>UA 원문은 보관하지 않는다(개인정보를 필요 이상으로 들고 있지 않는다) — 그래서
     * 목록을 낼 때는 {@code device_label} 밖에 없다. 되짚기가 성립하는 건 그 앞부분이
     * {@link #device} 가 만든 <b>닫힌 집합</b>이기 때문이다. 임의 문자열 파싱이 아니라
     * 우리가 쓴 단어를 우리가 다시 읽는 것이라 컬럼을 새로 만들 이유가 없다.
     *
     * <p>기기를 못 알아봤던 세션은 이름이 브라우저만({@code Chrome}) 이거나 {@code null}
     * 이다 — 둘 다 {@link DeviceKind#UNKNOWN}.
     */
    public static DeviceKind kindOf(String deviceLabel) {
        if (deviceLabel == null || deviceLabel.isBlank()) {
            return DeviceKind.UNKNOWN;
        }
        int sep = deviceLabel.indexOf(SEPARATOR);
        String device = (sep < 0 ? deviceLabel : deviceLabel.substring(0, sep)).trim();
        return switch (device) {
            // Android 태블릿도 UA 로는 폰과 구분되지 않는다 — 다수인 폰으로 둔다.
            case "iPhone", "Android", "iOS" -> DeviceKind.MOBILE;
            case "iPad" -> DeviceKind.TABLET;
            case "Windows", "Mac", "ChromeOS", "Linux" -> DeviceKind.DESKTOP;
            default -> DeviceKind.UNKNOWN;
        };
    }

    /** 기기·운영체제. 인자는 소문자로 정규화된 UA 다. */
    private static String device(String ua) {
        // iPad 가 iPhone 보다 앞이어야 한다 — iPadOS UA 가 둘 다 담는 경우가 있다.
        if (ua.contains("ipad")) return "iPad";
        if (ua.contains("iphone")) return "iPhone";
        if (ua.contains("android")) return "Android";
        if (ua.contains("windows")) return "Windows";
        // "mac os x" 는 iOS UA 에도 들어간다 — 위에서 iPhone·iPad 를 먼저 걸러야 한다.
        if (ua.contains("macintosh") || ua.contains("mac os x")) return "Mac";
        if (ua.contains("cros")) return "ChromeOS";
        if (ua.contains("linux")) return "Linux";
        // 앱이 보내는 "Porest/1.2.3 (iOS 17.5)" 형태. iPhone·iPad 구분은 못 하므로 iOS 로 둔다.
        // 괄호까지 함께 본다 — "CriOS"(iOS 크롬)·"FxiOS"(iOS 파이어폭스) 에 걸리지 않게.
        if (ua.contains("(ios")) return "iOS";
        return null;
    }

    /**
     * 브라우저 또는 앱.
     *
     * <p>앱은 {@code Porest/1.2.3 (Android 14)} 형태로 보낸다. UA 를 안 보내던 시절의
     * {@code dart:io} 기본값도 계속 본다 — 그때 만들어진 세션이 남아 있다.
     */
    private static String client(String ua) {
        // 앱이 보내는 형태가 먼저다 — "Porest/1.2.3 (Android 14)".
        // 옛 앱은 "Porest/", 새 앱은 "Groo/" 로 보낸다 — 둘 다 읽는다.
        if (ua.contains("porest/") || ua.contains("groo/")) return "그루 담다 앱";
        // 앱이 UA 를 안 보내던 시절의 dart:io 기본값. 옛 세션이 남아 있어 계속 본다.
        if (ua.contains("dart/") || ua.contains("dart:io")) return "그루 담다 앱";
        if (ua.contains("edg/") || ua.contains("edge/")) return "Edge";
        if (ua.contains("whale/")) return "Whale";
        if (ua.contains("samsungbrowser/")) return "Samsung Internet";
        if (ua.contains("opr/") || ua.contains("opera")) return "Opera";
        if (ua.contains("fxios/") || ua.contains("firefox/")) return "Firefox";
        // Chrome UA 는 Safari 를 함께 담지만 그 반대는 아니다. Chrome 을 먼저 본다.
        if (ua.contains("crios/") || ua.contains("chrome/")) return "Chrome";
        if (ua.contains("safari/")) return "Safari";
        return null;
    }
}
