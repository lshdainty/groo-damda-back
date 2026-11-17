package io.groo.damda.security;

import io.groo.damda.security.controller.TokenExchangeController;
import io.groo.damda.security.jwt.JwtTokenProvider;
import io.groo.damda.security.session.store.InMemorySessionRevocationStore;
import io.groo.damda.security.session.store.SessionRevocationStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA #44 — 로그아웃한 뒤 <b>같은 토큰으로</b> 요청하면 401 인지, 앱 전체를 띄워 확인한다.
 *
 * <p>단위 테스트는 조각만 본다. 필터가 인증을 안 세운다는 것과 그게 실제로 401 이 되어 나간다는
 * 것은 다른 말이고, 그 사이에 {@code SecurityConfig} 의 permitAll 목록·엔트리포인트·필터 순서가
 * 끼어 있다. QA 가 본 증상("탭 A 에서 로그아웃했는데 탭 B 가 200")은 그 조합에서 났으므로
 * 조합째로 못 박는다 — 진짜 톰캣, 진짜 필터 체인, 진짜 쿠키.
 *
 * <p>세션 행은 만들지 않는다. 테스트 프로파일엔 {@code app.security.encryption-key} 가 없어
 * {@code SsoSessionService.create} 가 행을 저장하지 않기 때문인데, 오히려 이쪽이 중요한 경로다 —
 * 행이 없어도 로그아웃이 토큰을 죽여야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LogoutRevokesAccessTokenTest {

    @LocalServerPort int port;

    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private SessionRevocationStore revocationStore;

    /** 로그인 한 번 = 기기 한 대. 컨텍스트가 테스트 클래스 사이에 공유되므로 세션 id 를 매번 새로 뽑는다. */
    private String loginOnSomeDevice() {
        String sessionId = UUID.randomUUID().toString();
        return jwtTokenProvider.createAccessToken("qa-user", "QA", "qa@porest.com", 4242L, sessionId, null);
    }

    private int get(String path, String token) {
        return RestClient.create().get()
                .uri("http://localhost:" + port + path)
                .header(HttpHeaders.COOKIE, TokenExchangeController.ACCESS_TOKEN_COOKIE + "=" + token)
                .exchange((req, res) -> res.getStatusCode().value());
    }

    private int logout(String token) {
        return RestClient.create().post()
                .uri("http://localhost:" + port + "/api/v1/auth/logout")
                .header(HttpHeaders.COOKIE, TokenExchangeController.ACCESS_TOKEN_COOKIE + "=" + token)
                .exchange((req, res) -> res.getStatusCode().value());
    }

    @Test
    @DisplayName("로그아웃한 뒤 같은 토큰으로 요청하면 401 — QA 가 본 '60분 창'이 닫힌다")
    void sameTokenIs401AfterLogout() {
        String token = loginOnSomeDevice();
        assertThat(get("/api/v1/auth/check", token)).as("로그아웃 전에는 통해야 한다").isEqualTo(200);

        assertThat(logout(token)).isEqualTo(200);

        assertThat(get("/api/v1/auth/check", token)).as("로그아웃 후 같은 토큰").isEqualTo(401);
    }

    @Test
    @DisplayName("auth/check 만이 아니다 — 인증이 필요한 다른 API 도 같이 막힌다")
    void everyAuthenticatedApiIsBlocked() {
        String token = loginOnSomeDevice();
        assertThat(get("/api/v1/users/me/sessions", token)).isEqualTo(200);

        logout(token);

        assertThat(get("/api/v1/users/me/sessions", token)).isEqualTo(401);
    }

    @Test
    @DisplayName("다른 기기는 살아 있다 — 로그아웃이 계정 전체를 끊으면 안 된다")
    void otherDevicesSurvive() {
        String phone = loginOnSomeDevice();
        String laptop = loginOnSomeDevice();

        logout(phone);

        assertThat(get("/api/v1/auth/check", phone)).as("로그아웃한 기기").isEqualTo(401);
        assertThat(get("/api/v1/auth/check", laptop)).as("다른 기기").isEqualTo(200);
    }

    @Test
    @DisplayName("테스트 컨텍스트는 메모리 표식을 쓴다 — Redis 로 떨어지면 전 테스트가 조용히 fail-open 된다")
    void testContextUsesInMemoryStore() {
        // Redis 구현이 뽑히면 조회가 예외 → fail-open(통과) 이라 위 테스트들이 "로그아웃이 안 먹는다"
        // 로 깨진다. 원인을 여기서 한 줄로 알려 준다.
        assertThat(revocationStore).isInstanceOf(InMemorySessionRevocationStore.class);
    }
}
