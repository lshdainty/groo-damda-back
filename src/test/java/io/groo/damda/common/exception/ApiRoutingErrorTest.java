package io.groo.damda.common.exception;

import io.groo.damda.security.controller.TokenExchangeController;
import io.groo.damda.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA #64 — 잘못된 경로·메서드가 5xx 로 새지 않는지, 앱을 통째로 띄워 확인한다.
 *
 * <p>슬라이스로는 못 잡는다. 이 결함은 <b>DispatcherServlet 이 고른 예외 + core advice 순서 +
 * 시큐리티 필터가 먼저 서는 구조</b>의 조합에서 났고, QA 가 본 것도 그 조합이다. 그래서 진짜
 * 톰캣·진짜 필터 체인으로 못 박는다.
 *
 * <p>같이 지키는 것: 이 변경이 <b>actuator · swagger 를 깨지 않는다</b>. 라우팅 404 를 만드는 흔한
 * 방법인 {@code spring.mvc.throw-exception-if-no-handler-found} + 정적 매핑 해제를 쓰지 않은 이유가
 * 그것이라, 그 유혹이 되살아나면 아래 두 테스트가 먼저 빨개진다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiRoutingErrorTest {

    @LocalServerPort int port;

    @Autowired private JwtTokenProvider jwtTokenProvider;

    private record Res(int status, String allow, String body) {}

    private String login() {
        return jwtTokenProvider.createAccessToken(
                "qa-user", "QA", "qa@porest.com", 4242L, UUID.randomUUID().toString(), null);
    }

    private Res post(String path, String token) {
        RestClient.RequestBodySpec spec = RestClient.create().post()
                .uri("http://localhost:" + port + path)
                .contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec = spec.header(HttpHeaders.COOKIE, TokenExchangeController.ACCESS_TOKEN_COOKIE + "=" + token);
        }
        return spec.body("{}").exchange((req, res) -> new Res(
                res.getStatusCode().value(),
                res.getHeaders().getFirst(HttpHeaders.ALLOW),
                new String(res.getBody().readAllBytes())));
    }

    private int getStatus(String path) {
        return RestClient.create().get()
                .uri("http://localhost:" + port + path)
                .exchange((req, res) -> res.getStatusCode().value());
    }

    @Test
    @DisplayName("경로는 있고 메서드만 없다 → 405 + Allow (QA 가 본 자리, 종전 500)")
    void methodMismatchIsMethodNotAllowed() {
        String token = login();

        // /api/v1/expense/budgets · /api/v1/saving-goals 는 GET 만 있다.
        Res budgets = post("/api/v1/expense/budgets", token);
        assertThat(budgets.status()).isEqualTo(405);
        assertThat(budgets.body()).contains("COMMON_405").doesNotContain("COMMON_500");
        assertThat(budgets.allow()).contains("GET");

        Res savingGoals = post("/api/v1/saving-goals", token);
        assertThat(savingGoals.status()).isEqualTo(405);
        assertThat(savingGoals.body()).contains("COMMON_405");
    }

    @Test
    @DisplayName("매핑이 아예 없는 경로 → 404")
    void unmappedPathIsNotFound() {
        Res res = post("/api/v1/no-such-path", login());

        assertThat(res.status()).isEqualTo(404);
        assertThat(res.body()).contains("COMMON_404");
    }

    @Test
    @DisplayName("미인증이면 라우팅까지 못 간다 → 401 (시큐리티 필터가 DispatcherServlet 앞에 선다)")
    void unauthenticatedNeverReachesRouting() {
        assertThat(post("/api/v1/expense/budgets", null).status()).isEqualTo(401);
        assertThat(post("/api/v1/no-such-path", null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("swagger 는 그대로 뜬다 — 정적 리소스 매핑을 끄지 않았다")
    void swaggerStillServed() {
        assertThat(getStatus("/v3/api-docs")).isEqualTo(200);
        assertThat(getStatus("/swagger-ui/index.html")).isNotIn(404, 405);
    }

    @Test
    @DisplayName("actuator 도 그대로 뜬다")
    void actuatorStillServed() {
        // 테스트 프로파일엔 Redis 가 더미라 health 는 DOWN(503) 일 수 있다 — 도달 여부만 본다.
        assertThat(getStatus("/actuator/health")).isNotIn(404, 405);
        assertThat(getStatus("/actuator/prometheus")).isEqualTo(200);
    }
}
