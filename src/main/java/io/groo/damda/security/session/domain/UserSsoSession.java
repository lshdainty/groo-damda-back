package io.groo.damda.security.session.domain;

import io.groo.core.type.YNType;
import io.groo.damda.common.domain.AuditingFieldsWithIp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 기기별 SSO 세션 — desk access token 이 만료됐을 때 조용히 재발급하는 데 쓰는 refresh token 보관.
 *
 * <p>{@code sessionId} 는 desk access token 의 {@code jti} 와 같다. 만료된 토큰에서도 jti 는
 * 읽을 수 있으므로(서명은 그대로 검증된다) "지금 이 기기" 를 찾는 열쇠가 된다.
 *
 * <p>refresh token 은 해시가 아니라 <b>암호문</b>으로 둔다 — SSO 에 재발급을 요청하려면 원본이
 * 필요하다. SSO 쪽 {@code refresh_tokens} 가 해시만 두는 것과 목적이 다르다(그쪽은 대조만 하면 된다).
 *
 * <p>사용자당 여러 행이다. 하나로 묶으면 폰에서 로그인할 때 웹 세션이 밀려난다.
 */
@Entity
@Table(name = "user_sso_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSsoSession extends AuditingFieldsWithIp {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "user_row_id", nullable = false)
    private Long userRowId;

    /** desk access token 의 jti. 만료된 토큰에서도 읽어 이 세션을 찾는다. */
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** SSO refresh token (AES-GCM 암호문). 재발급 요청에 원본이 필요해 복호화 가능하게 둔다. */
    @Column(name = "refresh_token_enc", nullable = false, length = 2048)
    private String refreshTokenEnc;

    /** [UTC] refresh token 만료 예상 시각. 지나면 SSO 가 거부하므로 부르기 전에 끊는다. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** 기기 표시명 (User-Agent 요약). "로그인된 기기" 목록용. */
    @Column(name = "device_label", length = 200)
    private String deviceLabel;

    /** [UTC] 마지막으로 이 세션이 재발급에 쓰인 시각. 동시 요청 판별에도 쓴다. */
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "is_deleted", nullable = false, length = 1)
    private YNType isDeleted;

    private UserSsoSession(Long userRowId, String sessionId, String refreshTokenEnc,
                           LocalDateTime expiresAt, String deviceLabel) {
        this.userRowId = userRowId;
        this.sessionId = sessionId;
        this.refreshTokenEnc = refreshTokenEnc;
        this.expiresAt = expiresAt;
        this.deviceLabel = deviceLabel;
        this.isDeleted = YNType.N;
    }

    /** 로그인 직후 새 세션. */
    public static UserSsoSession issue(Long userRowId, String sessionId, String refreshTokenEnc,
                                       LocalDateTime expiresAt, String deviceLabel) {
        return new UserSsoSession(userRowId, sessionId, refreshTokenEnc, expiresAt, deviceLabel);
    }

    /**
     * 재발급 성공 — 새 refresh token 으로 갈아끼우고 만료를 미룬다(sliding).
     *
     * <p>SSO 가 rotation 으로 매번 새 토큰을 주므로 옛 암호문을 남겨 둘 이유가 없다.
     * 남겨 두면 이미 폐기된 토큰으로 다음 재발급을 시도하게 된다.
     */
    public void rotate(String refreshTokenEnc, LocalDateTime expiresAt, LocalDateTime now) {
        this.refreshTokenEnc = refreshTokenEnc;
        this.expiresAt = expiresAt;
        this.lastUsedAt = now;
    }

    /** 로그아웃·재발급 거부 — 소프트 삭제. */
    public void revoke() {
        this.isDeleted = YNType.Y;
    }

    public boolean isActive() {
        return isDeleted == YNType.N;
    }

    /**
     * 로그인부터 절대 수명이 지났는가. 로그인 시각은 이 행의 {@code create_at}[UTC] — 로그인 때 한 번 쓰고
     * 재발급(rotate)으로는 바뀌지 않는다. 그래서 무음 재인증을 아무리 이어도 이 시각은 그대로다.
     *
     * <p>시각을 모르면(저장 전 행) 지난 것으로 본다 — 근거 없이 연장하지 않는다.
     */
    public boolean isPastAbsoluteLifetime(LocalDateTime now, long lifetimeSeconds) {
        LocalDateTime loginAt = getCreateAt();
        return loginAt == null || !loginAt.plusSeconds(lifetimeSeconds).isAfter(now);
    }

    /** 만료된 세션으로는 SSO 를 부르지 않는다 — 어차피 거부당한다. */
    public boolean isExpired(LocalDateTime now) {
        return expiresAt.isBefore(now);
    }

    /**
     * 방금 다른 요청이 재발급했는지.
     *
     * <p>토큰이 만료된 순간 여러 요청이 동시에 들어오면 각자 SSO 재발급을 시도한다. rotation 때문에
     * 먼저 도착한 하나만 성공하고 나머지는 이미 폐기된 토큰을 들고 가 거부당한다 — 사용자는
     * 멀쩡한 세션인데도 로그아웃된다. 락으로 줄을 세운 뒤 이 검사로 뒤따라온 요청은 SSO 를
     * 다시 부르지 않고, 앞선 요청이 갱신해 둔 세션 위에서 desk 토큰만 새로 받아 간다.
     */
    public boolean refreshedWithin(LocalDateTime now, long graceSeconds) {
        return lastUsedAt != null && lastUsedAt.isAfter(now.minusSeconds(graceSeconds));
    }
}
