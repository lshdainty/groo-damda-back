package io.groo.damda.support.security;

import io.groo.damda.security.principal.JwtClaimsPrincipal;
import io.groo.damda.security.principal.JwtUserPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.util.Collections;

/**
 * {@link WithLoginUser} → SecurityContext 생성.
 *
 * <p>JwtAuthenticationFilter 와 동일하게 {@link JwtUserPrincipal} 을 principal 로 하는
 * {@link UsernamePasswordAuthenticationToken}(authorities 비어 있음)을 세팅한다.
 */
public class WithLoginUserSecurityContextFactory
        implements WithSecurityContextFactory<WithLoginUser> {

    @Override
    public SecurityContext createSecurityContext(WithLoginUser annotation) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        JwtUserPrincipal principal = new JwtUserPrincipal(new JwtClaimsPrincipal(
                annotation.userId(),
                annotation.userName(),
                annotation.userEmail(),
                annotation.rowId(),
                "access",
                "test-session",
                null
        ));
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList());
        context.setAuthentication(authentication);
        return context;
    }
}
