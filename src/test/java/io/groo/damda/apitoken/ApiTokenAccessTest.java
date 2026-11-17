package io.groo.damda.apitoken;

import io.groo.core.exception.ForbiddenException;
import io.groo.damda.apitoken.service.ApiTokenService;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.security.controller.TokenExchangeController;
import io.groo.damda.security.jwt.JwtTokenProvider;
import io.groo.damda.securities.service.SecuritiesCandleProvider;
import io.groo.damda.securities.service.SecuritiesCandleProviders;
import io.groo.damda.securities.service.dto.CandlePage;
import io.groo.damda.securities.service.dto.SecuritiesCandle;
import io.groo.damda.subscription.service.SubscriptionEntitlementService;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 프로그램용 API 토큰을 <b>앱을 통째로 띄워 실제 HTTP 로</b> 확인한다.
 *
 * <p>단위 테스트로는 안 지켜지는 것들이다. 토큰 필터가 JWT 필터 <b>앞</b>에 서는지는 시큐리티 체인이
 * 조립돼야 드러나고, 토큰으로 세운 사용자를 {@code @LoginUser} 와 구독 게이트가 알아보는지는
 * MVC 까지 가 봐야 안다. 필터가 직접 쓰는 401·403 이 공통 봉투로 나가는지도 마찬가지다.
 *
 * <p>여기서 지키는 약속은 하나다 — <b>토큰은 주인의 증권 조회만 연다.</b> 주인이 아닌 사람의 데이터도,
 * 주인의 다른 데이터(가계부·설정·잔고)도 안 열린다.
 *
 * <p>증권사는 부르지 않는다(캔들 제공자를 목으로 바꾼다). 구독도 목이다 — 기본은 통과,
 * 끊긴 경우만 그 테스트에서 던지게 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiTokenAccessTest {

    private static final String CANDLES = "/api/v1/securities/candles?symbol=005930&interval=1d";

    @LocalServerPort int port;

    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired ApiTokenService apiTokenService;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean SubscriptionEntitlementService entitlementService;
    @MockitoBean SecuritiesCandleProviders candleProviders;

    private User owner;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        owner = newUser();
        ownerToken = apiTokenService.issue(owner.getRowId(), "통합 테스트").token();
        stubCandles(owner, "70200");
    }

    private User newUser() {
        String id = "u" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> userRepository.save(User.createUser(null, id, "테스터", id + "@porest.com")));
    }

    /** 이 사용자의 증권사 키로 조회하면 이 종가가 나온다고 해 둔다 — 누구 키로 조회했는지 응답으로 가른다. */
    private SecuritiesCandleProvider stubCandles(User user, String closePrice) {
        SecuritiesCandleProvider provider = mock(SecuritiesCandleProvider.class);
        given(candleProviders.forUser(user.getRowId())).willReturn(provider);
        given(provider.getCandles(eq(user.getRowId()), any())).willReturn(new CandlePage(List.of(
            new SecuritiesCandle("2026-09-30T00:00:00+09:00", "70000", "70500", "69900", closePrice, "12345", "KRW")),
            null));
        return provider;
    }

    private String login(User user) {
        return jwtTokenProvider.createAccessToken(user.getUserId(), user.getUserName(), user.getUserEmail(),
            user.getRowId(), UUID.randomUUID().toString(), null);
    }

    private record Res(int status, String body) {}

    private Res call(HttpMethod method, String path, String bearer, String cookieJwt, String body) {
        RestClient.RequestBodySpec spec = RestClient.create().method(method)
            .uri("http://localhost:" + port + path)
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.ACCEPT_LANGUAGE, "ko");
        if (bearer != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        if (cookieJwt != null) {
            spec = spec.header(HttpHeaders.COOKIE, TokenExchangeController.ACCESS_TOKEN_COOKIE + "=" + cookieJwt);
        }
        if (body != null) {
            spec = spec.body(body);
        }
        return spec.exchange((req, res) -> new Res(
            res.getStatusCode().value(),
            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)), false);
    }

    private Res get(String path, String bearer) {
        return call(HttpMethod.GET, path, bearer, null, null);
    }

    private JsonNode json(Res res) {
        return objectMapper.readTree(res.body());
    }

    private void assertError(Res res, int status, DeskErrorCode code) {
        assertThat(res.status()).as(res.body()).isEqualTo(status);
        JsonNode json = json(res);
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("code").asString()).isEqualTo(code.getCode());
        assertThat(json.path("message").asString()).isNotBlank().doesNotContain("error.");
    }

    @Test
    @DisplayName("토큰으로 캔들을 조회하면 토큰 주인의 증권사 키로 조회한 값이 나온다")
    void readsCandlesAsOwner() {
        Res res = get(CANDLES, ownerToken);

        assertThat(res.status()).as(res.body()).isEqualTo(200);
        assertThat(json(res).path("data").path("content").get(0).path("closePrice").asString()).isEqualTo("70200");
        verify(candleProviders).forUser(owner.getRowId());
    }

    @Test
    @DisplayName("토큰마다 자기 주인의 데이터만 나온다 — 남의 토큰으로 내 값이 나오지 않는다")
    void eachTokenSeesOnlyItsOwner() {
        User other = newUser();
        String otherToken = apiTokenService.issue(other.getRowId(), "다른 사람").token();
        stubCandles(other, "12345");

        assertThat(json(get(CANDLES, otherToken)).path("data").path("content").get(0).path("closePrice").asString())
            .isEqualTo("12345");
        assertThat(json(get(CANDLES, ownerToken)).path("data").path("content").get(0).path("closePrice").asString())
            .isEqualTo("70200");
    }

    @ParameterizedTest
    @CsvSource({
        "GET,  /api/v1/expenses",
        "GET,  /api/v1/users/me",
        "GET,  /api/v1/users/me/api-tokens",
        "POST, /api/v1/users/me/api-tokens",
        "GET,  /api/v1/users/me/securities-credentials",
        "GET,  /api/v1/toss/holdings",
        "GET,  /api/v1/toss/candles?symbol=005930&interval=1d",
        "GET,  /api/v1/namu/holdings",
        "POST, /api/v1/securities/candles"
    })
    @DisplayName("토큰으로는 증권 조회 밖의 자리를 못 연다 — 가계부·설정·잔고·토큰 발급 전부 403")
    void cannotOpenAnythingElse(String method, String path) {
        Res res = call(HttpMethod.valueOf(method), path, ownerToken, null,
            "POST".equals(method) ? "{\"name\":\"x\"}" : null);

        assertError(res, 403, DeskErrorCode.API_TOKEN_SCOPE_DENIED);
        assertThat(json(res).path("message").asString()).isEqualTo("API 토큰으로는 시세·차트·환율 조회만 할 수 있어요");
    }

    @Test
    @DisplayName("없는 토큰은 401")
    void unknownToken() {
        assertError(get(CANDLES, "pdk_" + "x".repeat(43)), 401, DeskErrorCode.API_TOKEN_INVALID);
        verify(candleProviders, never()).forUser(any());
    }

    @Test
    @DisplayName("폐기한 토큰은 그 즉시 401")
    void revokedToken() {
        Long tokenRowId = apiTokenService.getTokens(owner.getRowId()).get(0).rowId();
        apiTokenService.revoke(owner.getRowId(), tokenRowId);

        assertError(get(CANDLES, ownerToken), 401, DeskErrorCode.API_TOKEN_INVALID);
    }

    @Test
    @DisplayName("주인이 해지하면 토큰이 남아 있어도 401")
    void ownerWithdrawn() {
        tx.executeWithoutResult(s -> userRepository.findById(owner.getRowId()).orElseThrow().withdraw(null));

        assertError(get(CANDLES, ownerToken), 401, DeskErrorCode.API_TOKEN_INVALID);
    }

    @Test
    @DisplayName("구독이 끊기면 토큰이 살아 있어도 403 — 로그인한 사람과 같은 게이트를 탄다")
    void subscriptionLapsed() {
        willThrow(new ForbiddenException(DeskErrorCode.SUBSCRIPTION_REQUIRED))
            .given(entitlementService).requireFeature(owner.getRowId(), "SECURITIES");

        assertError(get(CANDLES, ownerToken), 403, DeskErrorCode.SUBSCRIPTION_REQUIRED);
        verify(candleProviders, never()).forUser(any());
    }

    @Test
    @DisplayName("쓰면 마지막 사용 시각이 남는다")
    void recordsLastUsed() {
        assertThat(apiTokenService.getTokens(owner.getRowId()).get(0).lastUsedAt()).isNull();

        get(CANDLES, ownerToken);

        assertThat(apiTokenService.getTokens(owner.getRowId()).get(0).lastUsedAt()).isNotNull();
    }

    @Test
    @DisplayName("토큰 없이 부르면 예전과 같은 401 이다 — 브라우저·앱 로그인 경로는 그대로다")
    void noCredentialsUnchanged() {
        Res res = get(CANDLES, null);

        assertThat(res.status()).isEqualTo(401);
        assertThat(json(res).path("code").asString()).isEqualTo("COMMON_411");
    }

    @Test
    @DisplayName("로그인한 본인이 발급 → 목록 → 사용 → 폐기까지 HTTP 로 한 바퀴")
    void issueListUseRevokeOverHttp() {
        String jwt = login(owner);

        Res issued = call(HttpMethod.POST, "/api/v1/users/me/api-tokens", null, jwt, "{\"name\":\"보고서 차트\"}");
        assertThat(issued.status()).as(issued.body()).isEqualTo(200);
        String token = json(issued).path("data").path("token").asString();
        long tokenRowId = json(issued).path("data").path("rowId").asLong();
        assertThat(token).matches("pdk_[A-Za-z0-9_-]{43}");

        Res list = call(HttpMethod.GET, "/api/v1/users/me/api-tokens", null, jwt, null);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body()).as("목록에 원문이 실리면 안 된다").doesNotContain(token);
        assertThat(json(list).path("data").get(0).path("rowId").asLong()).isEqualTo(tokenRowId);
        assertThat(json(list).path("data").get(0).path("tokenPrefix").asString()).isEqualTo(token.substring(0, 12));

        assertThat(get(CANDLES, token).status()).isEqualTo(200);

        Res revoked = call(HttpMethod.DELETE, "/api/v1/users/me/api-tokens/" + tokenRowId, null, jwt, null);
        assertThat(revoked.status()).as(revoked.body()).isEqualTo(200);

        assertError(get(CANDLES, token), 401, DeskErrorCode.API_TOKEN_INVALID);
    }

    @Test
    @DisplayName("남의 토큰 번호로는 폐기할 수 없다 — 404, 그 토큰은 계속 산다")
    void cannotRevokeSomeoneElsesToken() {
        User other = newUser();
        Long ownerTokenRowId = apiTokenService.getTokens(owner.getRowId()).get(0).rowId();

        Res res = call(HttpMethod.DELETE, "/api/v1/users/me/api-tokens/" + ownerTokenRowId, null, login(other), null);

        assertError(res, 404, DeskErrorCode.API_TOKEN_NOT_FOUND);
        assertThat(get(CANDLES, ownerToken).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("구독이 없는 사람은 토큰을 발급받지 못한다")
    void issueRequiresSubscription() {
        willThrow(new ForbiddenException(DeskErrorCode.SUBSCRIPTION_REQUIRED))
            .given(entitlementService).requireFeature(owner.getRowId(), "SECURITIES");

        Res res = call(HttpMethod.POST, "/api/v1/users/me/api-tokens", null, login(owner), "{\"name\":\"x\"}");

        assertError(res, 403, DeskErrorCode.SUBSCRIPTION_REQUIRED);
    }
}
