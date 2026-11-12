package io.groo.damda.user.controller.dto;

import io.groo.core.type.YNType;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.type.SupportedCurrency;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class UserApiDto {

    @Getter
    @NoArgsConstructor
    public static class ChangePasswordReq {

        @NotBlank(message = "현재 비밀번호를 입력해주세요")
        private String currentPassword;

        // 길이·문자 규칙은 SSO 가 소유한다. 여기에 복제하면 SSO 정책 변경 때 조용히 어긋난다.
        @NotBlank(message = "새 비밀번호를 입력해주세요")
        private String newPassword;

        @NotBlank(message = "새 비밀번호 확인을 입력해주세요")
        private String confirmPassword;
    }

    @Getter
    @NoArgsConstructor
    public static class VerifyPasswordReq {

        @NotBlank(message = "비밀번호를 입력해주세요")
        private String password;
    }

    /**
     * 환경설정 응답 — boolean 항목은 true/false 로 직렬화.
     *
     * <p>알림 말고 <b>표시 설정</b>도 여기로 나간다({@code timezone} · {@code defaultCurrency}).
     * 엔드포인트를 나누지 않은 이유는 부르는 시점이 같기 때문이다 — 둘 다 설정 화면에서만
     * 읽고 쓴다. (앱을 열 때마다 필요한 금액 가리기만 {@code /me/hide-cards} 로 따로 있다.)
     */
    public record PreferencesResponse(
        Boolean pushEnabled,
        Boolean notifyPayment,
        Boolean notifyBudget,
        Boolean notifyAutoRecord,
        Boolean notifyDutchPay,
        Boolean notifyCalendar,
        Boolean notifyWeeklyReport,
        Boolean notifyMonthlyReport,
        Integer budgetAlertThreshold,
        Boolean quietHoursEnabled,
        String quietHoursStart,
        String quietHoursEnd,
        String notificationSound,
        Boolean vibrationEnabled,
        Boolean emailEnabled,
        String emailFrequency,
        /** 표시 기준 지역(IANA 타임존 ID) */
        String timezone,
        /** 새 자산·거래에 미리 골라 둘 통화({@link SupportedCurrency}) */
        String defaultCurrency
    ) {
        private static Boolean bool(YNType v) {
            return v == null ? null : v.toBoolean();
        }

        public static PreferencesResponse from(User u) {
            return new PreferencesResponse(
                bool(u.getPushEnabled()),
                bool(u.getNotifyPayment()),
                bool(u.getNotifyBudget()),
                bool(u.getNotifyAutoRecord()),
                bool(u.getNotifyDutchPay()),
                bool(u.getNotifyCalendar()),
                bool(u.getNotifyWeeklyReport()),
                bool(u.getNotifyMonthlyReport()),
                u.getBudgetAlertThreshold(),
                bool(u.getQuietHoursEnabled()),
                u.getQuietHoursStart(),
                u.getQuietHoursEnd(),
                u.getNotificationSound(),
                bool(u.getVibrationEnabled()),
                bool(u.getEmailEnabled()),
                u.getEmailFrequency(),
                u.getTimezone(),
                u.getDefaultCurrency()
            );
        }
    }

    /**
     * 알림 환경설정 부분 수정 (PATCH). 모든 필드 선택적 — 보낸 항목만 반영.
     * threshold·시각·enum 은 값이 있을 때만 범위/형식 검증.
     */
    @Getter
    @NoArgsConstructor
    public static class UpdatePreferencesReq {
        private Boolean pushEnabled;
        private Boolean notifyPayment;
        private Boolean notifyBudget;
        private Boolean notifyAutoRecord;
        private Boolean notifyDutchPay;
        private Boolean notifyCalendar;
        private Boolean notifyWeeklyReport;
        private Boolean notifyMonthlyReport;

        @Min(value = 50, message = "예산 알림 임계값은 50% 이상이어야 해요")
        @Max(value = 150, message = "예산 알림 임계값은 150% 이하여야 해요")
        private Integer budgetAlertThreshold;

        private Boolean quietHoursEnabled;

        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "시간 형식은 HH:mm 이어야 해요")
        private String quietHoursStart;

        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "시간 형식은 HH:mm 이어야 해요")
        private String quietHoursEnd;

        @Pattern(regexp = "CHIME|DEFAULT|NONE", message = "알림음은 CHIME·DEFAULT·NONE 중 하나여야 해요")
        private String notificationSound;

        private Boolean vibrationEnabled;
        private Boolean emailEnabled;

        @Pattern(regexp = "DAILY|WEEKLY|MONTHLY", message = "발송 주기는 DAILY·WEEKLY·MONTHLY 중 하나여야 해요")
        private String emailFrequency;

        // 표시 기준 지역(IANA 타임존 ID). null = 무변경(부분 수정).
        // 값 형식은 ZoneId 로만 판단 가능해 서비스에서 검증한다.
        private String timezone;

        /**
         * 새 자산·거래 기본 통화. null = 무변경(부분 수정).
         *
         * <p>목록을 좁히는 자리가 <b>둘</b>인 이유: 여기 {@code @Pattern} 은 잘못 고른 값을
         * 그 자리에서 400 으로 끊어 문구를 보여 주고, 서비스의 검사는 이 애노테이션이
         * 지워지거나 다른 호출자가 생겼을 때를 위한 것이다. 알림음·발송 주기와 달리 이 값은
         * <b>새 자산·거래로 번져 나가고</b> 환율 조회({@code getFxRate})에까지 실려 나가므로
         * 한 겹으로 두지 않는다.
         */
        @Pattern(regexp = SupportedCurrency.PATTERN,
                message = "통화는 KRW·USD·EUR·JPY 중에서 골라주세요")
        private String defaultCurrency;
    }

    /**
     * 금액 가리기 목록.
     *
     * @param hideCards 가려 둔 카드 키들. <b>{@code null} 이면 "아직 한 번도 올린 적 없음"</b> 이고
     *                  빈 배열은 "사용자가 아무것도 안 가림을 골랐음" 이다 — 클라이언트는 이
     *                  둘을 반드시 구분해야 한다. {@code null} 을 빈 목록으로 받아 로컬을 덮으면
     *                  가려 뒀던 금액이 드러난다
     */
    public record HideCardsResponse(java.util.List<String> hideCards) {}

    /** @param hideCards 교체할 카드 키 전체. 부분 갱신이 아니라 통째로 바꾼다 */
    public record UpdateHideCardsReq(
            @jakarta.validation.constraints.NotNull(message = "가릴 카드 목록이 필요해요")
            @jakarta.validation.constraints.Size(max = 200, message = "가릴 수 있는 카드는 200개까지예요")
            java.util.List<
                    @jakarta.validation.constraints.Size(max = 64, message = "카드 키가 너무 길어요")
                    String> hideCards
    ) {}

    /** 해지 요청 — 사유는 선택이다. 적기 싫은 사람을 막지 않는다. */
    @Getter
    @NoArgsConstructor
    public static class WithdrawReq {
        @Size(max = 200)
        private String reason;
    }

    /** 메일로 받은 재인증 코드. 자릿수는 SSO 가 정한 6자리다. */
    @Getter
    @NoArgsConstructor
    public static class ReauthEmailCodeReq {
        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "숫자 6자리를 입력해주세요")
        private String code;
        /** 무엇을 하려고 확인하는지({@code withdraw}·{@code oauth-link}·{@code oauth-unlink}). 없으면 해지. */
        private String purpose;
    }

    /** 재인증용 비밀번호. 정책은 SSO 소유라 여기서는 비었는지만 본다. */
    @Getter
    @NoArgsConstructor
    public static class ReauthPasswordReq {
        @NotBlank
        private String password;
        /** 무엇을 하려고 확인하는지({@code withdraw}·{@code oauth-link}·{@code oauth-unlink}). 없으면 해지. */
        private String purpose;
    }

    /**
     * 재인증 티켓. 10분·단회짜리라 저장하지 말고 곧장 다음 호출의
     * {@code X-Reauth-Token} 헤더에 실어야 한다.
     */
    public record ReauthTicketResp(String reauthToken) {}
}
