package io.groo.damda.calendar.domain;

import io.groo.core.type.YNType;
import io.groo.damda.calendar.type.CalendarRole;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 공유 캘린더 멤버. 캘린더(calendar_row_id)에 참여한 사용자(user_row_id)의 권한(permission)을 담는다.
 * 소유자도 permission=OWNER 행 1개로 표현해 멤버 조회를 단일화한다 (UserGroupMember 패턴 미러).
 */
@Entity
@Table(name = "user_calendar_member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCalendarMember extends AuditingFieldsWithIp {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "calendar_row_id", nullable = false)
    private UserCalendar calendar;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_row_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 20)
    private CalendarRole permission;

    /** [UTC] 시스템 기록 시각 — 저장·비교 UTC, 표시할 때만 사용자 타임존 변환 */
    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    /**
     * 내 화면에 이 캘린더를 보이는가 — <b>멤버마다 따로</b> 갖는 값.
     *
     * <p>종전엔 캘린더 하나에 한 값({@code user_calendar.is_visible})이라 누가 끄면 모두의 화면에서 숨겨졌고,
     * 그래서 읽기 전용 멤버가 소유자 화면을 바꿀 수 있었다(보안 점검 DAM-8). 이제 각자 자기 화면만 바꾼다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "is_visible", nullable = false, length = 1)
    private YNType isVisible;

    @Enumerated(EnumType.STRING)
    @Column(name = "is_deleted", nullable = false, length = 1)
    private YNType isDeleted;

    public static UserCalendarMember create(UserCalendar calendar, User user, CalendarRole permission) {
        UserCalendarMember member = new UserCalendarMember();
        member.calendar = calendar;
        member.user = user;
        member.permission = permission;
        member.joinedAt = LocalDateTime.now();
        member.isVisible = YNType.Y;
        member.isDeleted = YNType.N;
        return member;
    }

    public void changePermission(CalendarRole permission) {
        this.permission = permission;
    }

    /** 내 화면에서 보이기/숨기기 — 다른 멤버 화면은 그대로다. */
    public void toggleVisibility() {
        this.isVisible = this.isVisible == YNType.N ? YNType.Y : YNType.N;
    }

    public void removeMember() {
        this.isDeleted = YNType.Y;
    }
}
