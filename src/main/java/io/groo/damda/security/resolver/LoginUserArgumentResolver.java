package io.groo.damda.security.resolver;

import io.groo.core.security.web.AbstractLoginUserArgumentResolver;
import io.groo.damda.security.principal.JwtUserPrincipal;
import io.groo.damda.security.principal.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * {@code @LoginUser UserPrincipal} 을 채운다 — 공통 판단은 core 뼈대, 담다는 JWT 주체를 {@link UserPrincipal} 로
 * 옮기는 법만 정한다(core 3.2.0).
 *
 * <p>로그인 사용자가 없으면 core 기본(401) 대신 {@code null} 을 넘긴다 — 예전 담다 동작 그대로다. 보호된 API 는
 * 시큐리티가 컨트롤러 앞에서 401 로 끊으므로 실제로 null 이 오는 자리는 공개 경로뿐이다.
 */
@Component
public class LoginUserArgumentResolver extends AbstractLoginUserArgumentResolver<UserPrincipal> {

    public LoginUserArgumentResolver() {
        super(UserPrincipal.class);
    }

    @Override
    protected UserPrincipal toLoginUser(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof JwtUserPrincipal principal)) {
            return null;
        }
        return UserPrincipal.builder()
                .rowId(principal.getUserRowId())
                .userId(principal.getUserId())
                .userName(principal.getUserName())
                .userEmail(principal.getUserEmail())
                .sessionId(principal.getSessionId())
                .build();
    }

    @Override
    protected UserPrincipal onMissing() {
        return null;
    }
}
