package io.groo.damda.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 알림 문구를 <b>알림을 저장하는 시점의 로케일</b>로 렌더한다(QA 2026-09-04 #76).
 *
 * <p>알림은 응답과 다르다. 응답은 요청마다 새로 만들어지므로 그때그때의 {@code Accept-Language} 로
 * 렌더하면 되지만, <b>알림은 문자열 그대로 DB 에 굳는다</b>. 굳은 뒤에는 사용자가 앱 언어를 바꿔도
 * 다시 렌더할 방법이 없다. 그래서 "언제 어느 언어로 굳히느냐" 가 이 클래스의 전부다.
 *
 * <p><b>서버가 사용자 언어를 아는 곳은 요청 헤더뿐이다</b>(2026-09-04 실측). {@code users} 테이블에
 * 언어 컬럼이 없고({@code timezone} 은 있다), {@code UserClock} 같은 사용자별 값 제공 장치도
 * 타임존만 다룬다. 즉 언어의 출처는 {@code LocaleResolver} → {@link LocaleContextHolder} 하나다.
 *
 * <p>그래서 두 갈래가 된다.
 * <ul>
 *   <li><b>요청 경로</b>(거래 저장 → 예산 임계 알림): 요청 스레드에 로케일이 붙어 있다 —
 *       그 사용자의 언어로 굳는다. 이게 "저장 시점의 사용자 언어" 다.</li>
 *   <li><b>스케줄러 경로</b>({@code NotificationTriggerScheduler}): 요청이 없으니 헤더도 없다.
 *       {@link #FALLBACK} 으로 굳는다.</li>
 * </ul>
 *
 * <p>폴백을 {@code Locale.getDefault()} 에 맡기지 않고 한국어로 <b>못 박는</b> 이유가 있다.
 * 컨테이너의 JVM 기본 로케일은 {@code LANG} 환경변수 하나로 바뀌는데, 그러면 배포 환경 설정이
 * 조용히 알림 언어를 뒤집는다 — 아무도 안 보는 사이 한국어 사용자의 알림이 영어로 굳는다.
 * 지금까지 이 문구들이 코드에 한국어로 박혀 있었으므로 한국어가 <b>기존 동작</b>이기도 하다.
 *
 * <p>사용자별 언어를 제대로 쓰려면 {@code users} 에 언어 컬럼이 필요하다 — 스키마 변경이라
 * 이 PR 범위 밖이다. 그 컬럼이 생기면 바꿀 자리는 {@link #locale()} 하나다.
 */
@Component
@RequiredArgsConstructor
public class NotificationMessages {

    /** 요청이 없는 자리(스케줄러)에서 알림을 굳힐 로케일. 위 클래스 주석 참고. */
    static final Locale FALLBACK = Locale.KOREAN;

    private final MessageSource messageSource;

    // ── 예산 알림 ─────────────────────────────────────────────────────────────
    // 임계 돌파(거래 저장 직후)와 일 배치(매일 09:00)가 같은 상황을 알린다.
    // 종전에는 두 곳이 각자 문장을 만들어 말투까지 갈렸다 —
    //   배치  "식비 예산 초과 경고"  / "식비 카테고리 예산의 90%를 사용했습니다."
    //   거래  "식비 예산 90% 사용"  / "식비 예산의 90%를 사용했어요 (9,000 / 10,000원)."
    // 이제 두 곳 모두 아래 메서드를 부른다. 문장을 고치려면 번들 한 줄만 고치면 된다.

    public String budgetOverTitle(String categoryName) {
        return get("notification.budget.over.title", categoryName);
    }

    public String budgetOverMessage(String categoryName, long limit, long spent) {
        return get("notification.budget.over.message", categoryName, amount(limit), amount(spent));
    }

    public String budgetWarnTitle(String categoryName, int percent) {
        return get("notification.budget.warn.title", categoryName, percent);
    }

    public String budgetWarnMessage(String categoryName, int percent, long spent, long limit) {
        return get("notification.budget.warn.message", categoryName, percent, amount(spent), amount(limit));
    }

    /** 카테고리를 지정하지 않은(=월 전체) 예산의 이름. */
    public String budgetCategoryAll() {
        return get("notification.budget.category.all");
    }

    // ── 할일·일정 알림 ────────────────────────────────────────────────────────

    public String todoDueMessage(boolean dueToday) {
        return get(dueToday ? "notification.todo.due.today" : "notification.todo.due.tomorrow");
    }

    public String eventReminderTitle(String eventTitle) {
        return get("notification.event.reminder.title", eventTitle);
    }

    public String eventReminderMessage(int minutesBefore) {
        return get("notification.event.reminder.message", minutesBefore);
    }

    // ── 공통 ─────────────────────────────────────────────────────────────────

    /**
     * WARN 알림에 <b>표시할</b> 사용률(%). 초과(한도보다 큼)는 OVER 분기가 맡으므로 한도 미만은 99 에서 자른다(한도와 같으면 100) —
     * 안 자르면 99.5% 가 반올림돼 "100% 사용" 으로 나가 초과와 구분이 안 된다.
     *
     * <p>거래 저장 경로와 배치가 같은 상황에서 같은 숫자를 말하도록 계산도 여기서 한 벌만 둔다
     * (종전 배치는 내림, 거래는 반올림이라 8,999/10,000 에서 89% 와 90% 로 갈렸다).
     */
    public static int usagePercent(long spent, long limit) {
        if (limit <= 0) return 0;
        // 한도와 정확히 같을 땐 초과가 아니라 WARN 이 받는다 — 99 로 자르면 "99% 사용 (10,000 / 10,000원)" 이 된다.
        if (spent == limit) return 100;
        // 정수 반올림: (a*100 + limit/2) / limit == round(a/limit*100)
        return (int) Math.min(99L, (spent * 100L + limit / 2) / limit);
    }

    private String get(String key, Object... args) {
        // 키를 못 찾으면 키 문자열이 그대로 나온다 — MessageResolver 와 같은 규칙.
        return messageSource.getMessage(key, args.length == 0 ? null : args, key, locale());
    }

    /**
     * 지금 이 스레드가 아는 사용자 언어. 요청 스레드면 {@code Accept-Language},
     * 아니면 {@link #FALLBACK}.
     *
     * <p>{@code LocaleContextHolder.getLocale()} 을 쓰지 않는 이유 — 그 메서드는 바인딩된 컨텍스트가
     * 없을 때 조용히 {@code Locale.getDefault()} 를 준다. 그러면 폴백이 JVM 설정에 끌려간다.
     */
    private static Locale locale() {
        LocaleContext context = LocaleContextHolder.getLocaleContext();
        Locale bound = context != null ? context.getLocale() : null;
        return bound != null ? bound : FALLBACK;
    }

    /** 금액 표기(3자리 구분). 알림 문구 안에서만 쓴다. */
    private static String amount(long value) {
        return String.format("%,d", value);
    }
}
