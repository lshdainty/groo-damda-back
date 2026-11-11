package io.groo.damda.common.config.security;

import io.groo.core.security.jwt.SsoJwksKeyLocator;
import io.groo.core.security.web.JsonAccessDeniedHandler;
import io.groo.core.security.web.JsonAuthenticationEntryPoint;
import io.groo.core.security.web.SecurityErrorResponder;
import io.groo.damda.common.config.properties.JwtProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import tools.jackson.databind.ObjectMapper;

/**
 * core 3.2.0 보안 공통 모듈의 빈. core 는 빈을 만들지 않으므로(서비스가 core 패키지를 통째로 스캔한다) 여기서 만든다.
 * 필터는 빈으로 만들지 않고 {@code SecurityConfig} 에서 직접 생성한다 — 빈이면 서블릿 필터로도 한 번 더 등록된다.
 */
@Configuration
public class SecurityWebConfig {

    /** 필터·인터셉터가 컨트롤러와 같은 봉투·같은 다국어 문구로 오류를 쓰게 한다. */
    @Bean
    public SecurityErrorResponder securityErrorResponder(ObjectMapper objectMapper, MessageSource messageSource,
                                                         LocaleResolver localeResolver) {
        return new SecurityErrorResponder(objectMapper, messageSource, localeResolver);
    }

    /** SSO 토큰 서명 검증용 공개키. JWKS 주소는 설정값으로만 받는다(토큰 헤더의 jku 는 보지 않는다). */
    @Bean
    public SsoJwksKeyLocator ssoSigningKeyLocator(JwtProperties jwtProperties) {
        return new SsoJwksKeyLocator(jwtProperties.getSsoJwksUri());
    }

    @Bean
    public JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint(SecurityErrorResponder responder) {
        return new JsonAuthenticationEntryPoint(responder);
    }

    @Bean
    public JsonAccessDeniedHandler jsonAccessDeniedHandler(SecurityErrorResponder responder) {
        return new JsonAccessDeniedHandler(responder);
    }
}
