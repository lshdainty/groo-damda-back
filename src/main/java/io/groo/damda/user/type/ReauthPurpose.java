package io.groo.damda.user.type;

import io.groo.core.exception.InvalidValueException;
import io.groo.damda.common.exception.DeskErrorCode;

/**
 * 재인증 티켓의 용도 — 담다에서 본인 확인을 요구하는 조작 목록.
 *
 * <p><b>열린 문자열이 아니라 닫힌 목록</b>이다. 클라이언트가 아무 용도나 고르게 하면 티켓 검증 쪽의 용도 대조가
 * 의미를 잃는다. 새 조작이 재인증을 요구하면 여기 한 줄을 더한다. {@code wire} 는 SSO 티켓의 {@code purpose}
 * 클레임 값이고, 검증하는 쪽(담다 {@code ReauthTicketVerifier}·SSO 연동 시작)이 같은 값을 본다.
 */
public enum ReauthPurpose {
    /** 담다 이용 해지 — 담다가 직접 검증한다. */
    WITHDRAW("withdraw"),
    /** 소셜 계정 연동 시작 — SSO 가 검증한다(보안 점검 S-2). */
    OAUTH_LINK("oauth-link"),
    /**
     * 소셜 계정 연동 해제 — SSO 가 검증한다(보안 점검 S-2 잔여). 연동 시작과 용도를 나눈다: 같은 값이면
     * 해제하려고 받은 티켓으로 연동을 시작할 수 있고, 그 반대도 된다.
     */
    OAUTH_UNLINK("oauth-unlink");

    private final String wire;

    ReauthPurpose(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /**
     * 요청 본문의 용도. 비어 있으면 해지 — 이 값이 생기기 전 클라이언트(옛 앱 빌드)는 해지에만 재인증을 썼다.
     *
     * @throws InvalidValueException 목록에 없는 값
     */
    public static ReauthPurpose fromRequest(String value) {
        if (value == null || value.isBlank()) {
            return WITHDRAW;
        }
        for (ReauthPurpose p : values()) {
            if (p.wire.equals(value)) {
                return p;
            }
        }
        throw new InvalidValueException(DeskErrorCode.INVALID_INPUT);
    }
}
