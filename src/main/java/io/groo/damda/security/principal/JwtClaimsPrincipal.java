package io.groo.damda.security.principal;

public record JwtClaimsPrincipal(
    String userId,
    String userName,
    String userEmail,
    Long userRowId,
    /**
     * 토큰 유형. "access"(기본 로그인) · "embed"(60초 임베드 차트 등) · "api"(프로그램용 API 토큰).
     * 갱신 필터는 embed 갱신 skip. "api" 는 JWT 가 아니다 — {@code ApiTokenAuthenticationFilter} 가
     * 토큰 주인으로 이 값을 직접 만든다.
     */
    String tokenType,
    /**
     * 세션 아이디(jti) — 이 토큰이 어느 기기의 세션인지. 만료 후 조용히 재발급할 때 이 값으로
     * 세션을 찾는다. 세션 도입 前에 발급된 토큰에는 없어서 {@code null} 일 수 있고, 임베드
     * 토큰에도 없다(세션에 속하지 않는 단명 토큰이라). API 토큰에도 없다(기기 세션이 아니다).
     */
    String sessionId,
    /**
     * 이 세션을 만든 SSO 로그인 번호({@code sso_sid} 클레임 = SSO access token 의 {@code sid}).
     * 로그아웃할 때 "담다에서 로그아웃한 SSO 로그인" 으로 기억하는 값이다(보안 점검 CLI-6).
     * SSO 가 sid 를 싣기 전에 발급된 토큰에서 온 세션, 임베드·API 토큰에는 없어서 {@code null} 이다.
     */
    String ssoSid
) {
    public boolean isEmbed() { return "embed".equals(tokenType); }

    public boolean hasSession() { return sessionId != null && !sessionId.isBlank(); }

    public boolean hasSsoSid() { return ssoSid != null && !ssoSid.isBlank(); }
}
