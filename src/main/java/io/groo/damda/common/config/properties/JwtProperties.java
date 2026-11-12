package io.groo.damda.common.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {
    private String secret;
    private long accessTokenExpiration;
    /**
     * 세션 수명(ms). access token 이 만료돼도 이 기간 안이면 조용히 재발급된다.
     *
     * <p>SSO refresh token 의 수명과 맞춰야 한다 — 여기가 더 길면 SSO 가 이미 거부하는 세션을
     * 붙들고 있게 되고, 더 짧으면 쓸 수 있는 기간을 스스로 깎는다. SSO 는 7일이다.
     *
     * <p>access token 쿠키의 maxAge 도 이 값을 쓴다. 쿠키가 토큰과 같이 1시간 만에 사라지면
     * 만료된 토큰이 서버에 도달조차 못 해 재발급할 세션을 찾을 수 없다.
     */
    private long sessionExpiration = 604_800_000L;
    /**
     * 세션 절대 수명(ms) — 로그인한 순간부터 센다. 사용 중이어도 이 기간이 지나면 다시 로그인한다.
     *
     * <p>{@link #sessionExpiration} 은 쓸 때마다 밀리는 미끄럼 수명이라, 훔친 쿠키로 주기적으로 요청만
     * 보내면 끝없이 이어졌다(보안 점검 DAM-5). 이 값이 그 상한이다. 30일(사용자 결정 2026-11-12).
     * 로그인 시각은 세션 행의 {@code create_at}(로그인 때 한 번 쓰고 바뀌지 않는다)이다.
     */
    private long absoluteSessionLifetime = 2_592_000_000L;
    /** SSO RS256 토큰 검증용 JWKS URI ({ssoBaseUrl}/.well-known/jwks.json). */
    private String ssoJwksUri;
}
