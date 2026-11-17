package io.groo.damda.security.principal;

import io.groo.core.security.AuditorPrincipal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class JwtUserPrincipal implements AuditorPrincipal {
    private final JwtClaimsPrincipal claims;

    @Override
    public String getUserId() { return claims.userId(); }
    public String getUserName() { return claims.userName(); }
    public String getUserEmail() { return claims.userEmail(); }
    public Long getUserRowId() { return claims.userRowId(); }
    /** 이 토큰이 속한 기기 세션(jti). 로그아웃할 때 그 기기 세션만 끊는 데 쓴다. */
    public String getSessionId() { return claims.sessionId(); }
    /** 이 세션을 만든 SSO 로그인 번호. 로그아웃할 때 이 SSO 로그인을 기억해 두는 데 쓴다. 없으면 null. */
    public String getSsoSid() { return claims.ssoSid(); }
}
