package io.groo.damda.common.config.database;

import io.groo.core.security.AbstractAuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 담다의 작성자·수정자 기록 — 공통 판단은 core {@link AbstractAuditorAware}, 담다만 다른 두 가지를 여기 둔다.
 * <ul>
 *   <li>로그인 없이 일어난 쓰기(스케줄러·배치)는 {@code "system"} 으로 남긴다.</li>
 *   <li>주체가 {@code AuditorPrincipal} 이 아니면 인증 이름을 그대로 쓴다.</li>
 * </ul>
 * 이음·sso 는 둘 다 비워 둔다(core 기본값). 기존 데이터와 같게 하려고 담다만 다르다.
 */
@Component
public class LoginUserAuditorAware extends AbstractAuditorAware {

    @Override
    protected Optional<String> anonymousAuditor() {
        return Optional.of("system");
    }

    @Override
    protected Optional<String> fallbackAuditor(Authentication authentication) {
        return Optional.of(authentication.getName());
    }
}
