package io.groo.damda.calendar.domain;

import io.groo.core.type.YNType;
import io.groo.damda.common.domain.AuditingFieldsWithIp;
import io.groo.damda.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.security.SecureRandom;
import java.util.HexFormat;

@Entity
@Table(name = "user_calendar")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCalendar extends AuditingFieldsWithIp {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // 캘린더 소유자(생성자). 공유 시에도 owner 역할.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_row_id")
    private User user;

    @Column(name = "calendar_name", nullable = false, length = 50)
    private String calendarName;

    @Column(name = "color", nullable = false, length = 20)
    private String color;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "is_default", nullable = false, length = 1)
    private YNType isDefault;

    @Enumerated(EnumType.STRING)
    @Column(name = "is_visible", nullable = false, length = 1)
    private YNType isVisible;

    /**
     * 공유용 초대 코드 — 소유자가 공유를 켤 때만 있다(꺼져 있으면 null). 받은 사람이 붙여 넣어 참여한다.
     *
     * <p>종전엔 모든 캘린더(개인 기본 캘린더 포함)에 만들 때 자동으로 8자리 16진수(32비트)를 발급했고
     * 참여 시도에 한도가 없어, 무작위 대입으로 아무나 한 명의 캘린더에 편집 멤버로 들어갈 수 있었다
     * (보안 점검 DAM-4). 이제 128비트, 공유를 켤 때만 발급한다.
     */
    @Column(name = "invite_code", length = INVITE_CODE_LENGTH, unique = true)
    private String inviteCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "is_deleted", nullable = false, length = 1)
    private YNType isDeleted;

    public static UserCalendar createCalendar(User user, String calendarName, String color, Integer sortOrder, boolean isDefault) {
        UserCalendar calendar = new UserCalendar();
        calendar.user = user;
        calendar.calendarName = calendarName;
        calendar.color = color != null ? color : "#2c70bf";
        calendar.sortOrder = sortOrder != null ? sortOrder : 0;
        calendar.isDefault = isDefault ? YNType.Y : YNType.N;
        calendar.isVisible = YNType.Y;
        // 초대 코드는 만들지 않는다 — 공유를 켤 때(issueInviteCode) 발급한다.
        calendar.isDeleted = YNType.N;
        return calendar;
    }

    public void updateCalendar(String calendarName, String color) {
        if (calendarName != null) {
            this.calendarName = calendarName;
        }
        if (color != null) {
            this.color = color;
        }
    }

    public void toggleVisibility() {
        this.isVisible = this.isVisible == YNType.Y ? YNType.N : YNType.Y;
    }

    /** 공유를 켠다 — 새 초대 코드를 발급한다. 이미 있으면 갈아 끼워 옛 코드는 더 이상 쓸 수 없다. */
    public String issueInviteCode() {
        this.inviteCode = generateInviteCode();
        return this.inviteCode;
    }

    /** 공유를 끈다 — 새로 참여할 수 없게 코드를 지운다. 이미 참여한 멤버는 그대로다. */
    public void clearInviteCode() {
        this.inviteCode = null;
    }

    public void deleteCalendar() {
        this.isDeleted = YNType.Y;
    }

    /** 초대 코드 길이 — 16진수 32자 = 128비트. */
    public static final int INVITE_CODE_LENGTH = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 16바이트 난수를 대문자 16진수 32자로. 대소문자를 가리지 않는 형식이라 웹·앱이 붙여 넣은 값을 대문자로
     * 맞춰도 그대로 맞는다(base64url 은 대소문자를 가려 그 정규화가 코드를 깨뜨린다).
     */
    private static String generateInviteCode() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }
}
