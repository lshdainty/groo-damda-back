package io.groo.damda.common.config.security;

import io.groo.damda.security.controller.TokenExchangeController;

import io.groo.core.security.web.PrometheusAccessFilter;

import io.groo.core.security.web.OriginCheckFilter;

import io.groo.core.security.web.MaliciousPathBlockFilter;

import io.groo.damda.apitoken.service.ApiTokenRateLimiter;
import io.groo.damda.apitoken.service.ApiTokenService;
import io.groo.damda.common.config.properties.AppProperties;
import io.groo.damda.security.filter.ApiTokenAuthenticationFilter;
import io.groo.damda.security.filter.JwtAuthenticationFilter;
import io.groo.core.security.web.SecurityErrorResponder;
import io.groo.core.security.web.JsonAccessDeniedHandler;
import io.groo.core.security.web.JsonAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.DispatcherType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    // ApiTokenAuthenticationFilter 의 재료 — 필터 자체는 빈이 아니라 아래에서 직접 만든다(이유는 그 클래스 주석).
    private final ObjectProvider<ApiTokenService> apiTokenServiceProvider;
    private final ApiTokenRateLimiter apiTokenRateLimiter;
    private final SecurityErrorResponder apiErrorResponder;
    private final JsonAuthenticationEntryPoint customAuthenticationEntryPoint;
    private final JsonAccessDeniedHandler customAccessDeniedHandler;
    private final AppProperties appProperties;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 401·403 을 둘 다 우리 핸들러로 보낸다. accessDeniedHandler 를 빼면 403 만 시큐리티
            // 기본 경로(sendError → /error)로 새어 부트 기본 에러 본문이 나간다.
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint(customAuthenticationEntryPoint)
                .accessDeniedHandler(customAccessDeniedHandler))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                .requestMatchers(
                    "/api/v1/auth/exchange-code",
                    "/api/v1/auth/logout",
                    "/actuator/health",
                    "/actuator/prometheus",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html"
                ).permitAll()
                .anyRequest().authenticated()
            )
            // 보호 장치(core 3.2.0) — 담다에 없던 것들. 필터는 빈이 아니라 여기서 만든다(빈이면 서블릿 필터로 중복 등록).
            // 스캐너 경로 차단 → 지표 접근 제한(내부망 직접 요청만) → 오리진 검사(쿠키 로그인의 CSRF 방어)
            .addFilterBefore(new MaliciousPathBlockFilter(apiErrorResponder), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new PrometheusAccessFilter(apiErrorResponder), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new OriginCheckFilter(allowedOrigins(), TokenExchangeController.ACCESS_TOKEN_COOKIE,
                apiErrorResponder), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // 프로그램용 API 토큰은 JWT 보다 먼저 본다 — 같은 Authorization 자리에 오는데 JWT 가
            // 아니라서, JWT 필터가 먼저 집으면 읽지 못하고 경고만 남긴다. 순서를 JWT 필터 기준으로
            // 못 박아 둔다(둘 다 같은 기준점 앞에 두면 순서가 등록 순서에 맡겨진다).
            .addFilterBefore(
                new ApiTokenAuthenticationFilter(apiTokenServiceProvider, apiTokenRateLimiter, apiErrorResponder),
                JwtAuthenticationFilter.class);

        return http.build();
    }

    /** CORS 와 오리진 검사가 같은 목록을 쓴다(쉼표 구분 설정값). */
    private List<String> allowedOrigins() {
        return Arrays.stream(appProperties.getCors().getAllowedOrigins().split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
