package io.groo.damda.notification.service;

import io.groo.damda.support.message.TestMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림 문구를 <b>어느 언어로 굳히는가</b>를 고정한다(QA 2026-09-04 #76).
 *
 * <p>서버가 사용자 언어를 아는 곳은 요청 헤더뿐이다 — {@code users} 테이블에 언어 컬럼이 없다.
 * 그래서 요청 경로는 요청 로케일로, 요청이 없는 스케줄러 경로는 폴백(한국어)으로 굳는다.
 */
class NotificationMessagesTest {

    private final NotificationMessages sut = TestMessages.notificationMessages();

    @BeforeEach
    @AfterEach
    void clearBoundLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("요청 로케일이 붙어 있으면 그 언어로 굳는다")
    void usesTheBoundRequestLocale() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        assertThat(sut.budgetOverTitle("Food")).isEqualTo("Food budget exceeded");
        assertThat(sut.todoDueMessage(true)).isEqualTo("A todo is due today");
    }

    @Test
    @DisplayName("요청이 없으면(스케줄러) 폴백 한국어로 굳는다 — JVM 기본 로케일에 끌려가지 않는다")
    void fallsBackToKoreanWithoutARequest() {
        // 폴백을 Locale.getDefault() 에 맡기면 컨테이너의 LANG 하나로 알림 언어가 뒤집힌다.
        // 그게 실제로 안 일어나는지 보려면 기본 로케일을 뒤집어 놓고 확인해야 한다.
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            assertThat(sut.budgetOverTitle("식비")).isEqualTo("식비 예산 초과");
            assertThat(sut.todoDueMessage(false)).isEqualTo("내일 마감인 할 일이 있어요");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("금액·퍼센트 표기 — 3자리 구분 그대로")
    void rendersAmountsAndPercents() {
        assertThat(sut.budgetOverMessage("식비", 10_000L, 12_345L))
                .isEqualTo("식비 예산 10,000원을 초과했어요 (현재 12,345원)");
        assertThat(sut.budgetWarnTitle("식비", 90)).isEqualTo("식비 예산 90% 사용");
        assertThat(sut.budgetWarnMessage("식비", 90, 9_000L, 10_000L))
                .isEqualTo("식비 예산의 90%를 사용했어요 (9,000 / 10,000원)");
        assertThat(sut.budgetCategoryAll()).isEqualTo("전체");
        assertThat(sut.eventReminderTitle("치과 예약")).isEqualTo("치과 예약 알림");
        assertThat(sut.eventReminderMessage(30)).isEqualTo("30분 전 알림");
    }

    @Test
    @DisplayName("카테고리 이름에 중괄호·따옴표가 있어도 문구가 안 깨진다 — 인자는 패턴이 아니다")
    void userSuppliedNamesAreNotTreatedAsPatterns() {
        // 카테고리 이름은 사용자가 짓는다. MessageFormat 은 인자가 아니라 패턴만 해석하므로
        // 이 문자열이 그대로 실려야 한다(패턴 쪽에 따옴표를 쓰지 말라는 번들 주석의 짝이다).
        assertThat(sut.budgetOverTitle("it's {0} 카테고리"))
                .isEqualTo("it's {0} 카테고리 예산 초과");
    }

    @Test
    @DisplayName("표시 사용률 — 반올림하되 99 에서 자른다(초과는 OVER 분기가 맡는다)")
    void usagePercentRoundsAndCapsAt99() {
        assertThat(NotificationMessages.usagePercent(8_500L, 10_000L)).isEqualTo(85);
        // 종전 스케줄러는 내림이라 89, 거래 저장 경로는 반올림이라 90 이었다 — 이제 한 벌이다.
        assertThat(NotificationMessages.usagePercent(8_999L, 10_000L)).isEqualTo(90);
        assertThat(NotificationMessages.usagePercent(9_950L, 10_000L)).isEqualTo(99);
        assertThat(NotificationMessages.usagePercent(9_999L, 10_000L)).isEqualTo(99);
        // 0 나눗셈 방어 — 예산 0 은 호출부에서 걸러지지만 여기서도 죽지 않는다.
        assertThat(NotificationMessages.usagePercent(1_000L, 0L)).isZero();
    }
}
