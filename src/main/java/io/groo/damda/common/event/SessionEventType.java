package io.groo.damda.common.event;

/** SSO 세션 이벤트 종류. 발행 쪽(porest-sso-back)의 같은 이름 enum 과 값을 맞춘다. */
public enum SessionEventType {
    /** 이 사용자의 모든 세션이 끊겼다. desk 도 자기 세션을 전부 지운다. */
    SESSION_REVOKED_ALL,

    /**
     * SSO 로그인 한 건(SSO access 토큰의 {@code sid})이 끊겼다 — 그루 계정 로그아웃 · 기기 끊기 · 관리자 세션 종료 ·
     * refresh 재사용 감지(보안 점검 CLI-6). 그 로그인에서 나온 담다 세션만 끊는다. 같은 사람의 다른 기기는 둔다.
     */
    SESSION_REVOKED
}
