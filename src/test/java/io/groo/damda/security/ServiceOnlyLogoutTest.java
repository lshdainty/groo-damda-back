package io.groo.damda.security;

import io.groo.core.security.jwt.SsoJwksKeyLocator;
import io.groo.core.type.YNType;
import io.groo.damda.security.client.SsoOAuth2Client;
import io.groo.damda.security.controller.TokenExchangeController;
import io.groo.damda.security.jwt.JwtTokenProvider;
import io.groo.damda.security.principal.JwtClaimsPrincipal;
import io.groo.damda.security.service.TokenExchangeService;
import io.groo.damda.security.session.repository.UserSsoSessionRepository;
import io.groo.damda.security.session.store.InMemorySsoLoginLogoutStore;
import io.groo.damda.security.session.store.SsoLoginLogoutStore;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.verify;

/**
 * 보안 점검 CLI-6 — <b>"서비스에서 로그아웃하면 그 서비스만 끝난다"</b> 를 앱 전체를 띄워 확인한다.
 *
 * <p>담다에서 로그아웃해도 그루 계정(SSO) 로그인과 다른 서비스는 그대로다. 대신 담다가 자기 로그아웃을
 * 기억했다가, 같은 SSO 로그인으로 돌아온 교환을 거절한다. 조각마다 단위 테스트가 있지만, 사용자가 겪는 것은
 * 그 조합이다 — 교환이 SSO 토큰의 sid 를 desk 토큰에 싣고, 로그아웃이 그 토큰에서 꺼내 기억하고, 다음
 * 교환이 그것을 본다. 한 군데만 끊겨도 "로그아웃했는데 로그인 버튼 한 번에 다시 들어온다" 가 된다. 그래서
 * 진짜 톰캣 · 진짜 필터 체인 · 진짜 쿠키 · 진짜 서명(SSO 토큰은 시험용 RSA 키로 서명한다)으로 본다.
 *
 * <p>SSO 는 띄우지 않는다. 인가코드 교환 응답만 흉내 내고({@link SsoOAuth2Client}), JWKS 조회는 시험용
 * 공개키로 갈음한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 세션 행(저장해 둔 SSO refresh)이 실제로 만들어지고 지워지는 것까지 보려면 암호화 키가 있어야 한다
        properties = "app.security.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("test")
class ServiceOnlyLogoutTest {

    private static final KeyPair SSO_KEY = Jwts.SIG.RS256.keyPair().build();

    @LocalServerPort int port;

    @MockitoBean private SsoOAuth2Client ssoOAuth2Client;
    @MockitoBean private SsoJwksKeyLocator ssoSigningKeyLocator;

    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private TokenExchangeService tokenExchangeService;
    @Autowired private SsoLoginLogoutStore ssoLoginLogoutStore;
    @Autowired private UserSsoSessionRepository sessionRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ObjectMapper objectMapper;

    /** 테스트마다 다른 사용자 — 컨텍스트와 H2 가 테스트 클래스 사이에 공유된다. */
    private String userId;
    private long ssoUserNo;

    @BeforeEach
    void setUp() {
        // user_id 컬럼이 20자다
        userId = "c6" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ssoUserNo = ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE / 2);
        given(ssoSigningKeyLocator.locate(any(Header.class))).willReturn(SSO_KEY.getPublic());
    }

    private record Res(int status, String cookieToken, String body) {}

    /** SSO 가 발급한 access token — {@code sid} 는 SSO 로그인 한 번을 가리킨다. 옛 토큰에는 없다(null). */
    private String ssoAccessToken(String sid) {
        JwtBuilder builder = Jwts.builder()
                .subject(userId)
                .claim("services", List.of("desk"))
                .claim("name", "테스터")
                .claim("email", userId + "@porest.com")
                .claim("userNo", ssoUserNo)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000L));
        if (sid != null) {
            builder.claim("sid", sid);
        }
        return builder.signWith(SSO_KEY.getPrivate(), Jwts.SIG.RS256).compact();
    }

    /**
     * 로그인 — SSO 가 이 인가코드에 {@code sid} 가 실린 토큰을 내준 것으로 치고 담다에 교환을 요청한다.
     * 살아 있는 SSO 로그인으로 조용히 돌아오면 같은 sid, 비밀번호로 다시 로그인하면 새 sid 다.
     */
    private Res login(String sid, String refreshToken, String acceptLanguage) {
        String code = "code-" + UUID.randomUUID();
        given(ssoOAuth2Client.exchangeCodeForToken(eq(code), anyString(), anyString()))
                .willReturn(new SsoOAuth2Client.TokenPair(ssoAccessToken(sid), refreshToken));

        RestClient.RequestBodySpec spec = RestClient.create().post()
                .uri("http://localhost:" + port + "/api/v1/auth/exchange-code")
                .contentType(MediaType.APPLICATION_JSON);
        if (acceptLanguage != null) {
            spec = spec.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
        }
        return spec.body("{\"code\":\"" + code + "\",\"codeVerifier\":\"verifier\","
                        + "\"redirectUri\":\"https://damda/callback\"}")
                .exchange((req, res) -> new Res(
                        res.getStatusCode().value(),
                        accessTokenCookie(res.getHeaders().get(HttpHeaders.SET_COOKIE)),
                        new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)), false);
    }

    private Res login(String sid) {
        return login(sid, "refresh-" + UUID.randomUUID(), null);
    }

    private static String accessTokenCookie(List<String> setCookies) {
        if (setCookies == null) {
            return null;
        }
        String prefix = TokenExchangeController.ACCESS_TOKEN_COOKIE + "=";
        return setCookies.stream()
                .filter(c -> c.startsWith(prefix))
                .map(c -> c.substring(prefix.length(), c.indexOf(';')))
                .findFirst().orElse(null);
    }

    private int check(String token) {
        return RestClient.create().get()
                .uri("http://localhost:" + port + "/api/v1/auth/check")
                .header(HttpHeaders.COOKIE, TokenExchangeController.ACCESS_TOKEN_COOKIE + "=" + token)
                .exchange((req, res) -> res.getStatusCode().value());
    }

    private int checkWithBearer(String token) {
        return RestClient.create().get()
                .uri("http://localhost:" + port + "/api/v1/auth/check")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange((req, res) -> res.getStatusCode().value());
    }

    private int logout(String token) {
        return RestClient.create().post()
                .uri("http://localhost:" + port + "/api/v1/auth/logout")
                .header(HttpHeaders.COOKIE, TokenExchangeController.ACCESS_TOKEN_COOKIE + "=" + token)
                .exchange((req, res) -> res.getStatusCode().value());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(Res res) {
        return objectMapper.readValue(res.body(), Map.class);
    }

    private boolean sessionRowAlive(String sessionId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(s ->
                sessionRepository.findBySessionIdAndIsDeleted(sessionId, YNType.N).isPresent()));
    }

    @Test
    @DisplayName("담다에서 로그아웃한 뒤 같은 SSO 로그인으로 돌아오면 401 AUTH_030 — 비밀번호로 다시 로그인(새 sid)하면 들어온다")
    void sameSsoLoginIsRejectedAfterLogout_newSsoLoginPasses() {
        String sid = "sso-login-" + UUID.randomUUID();
        Res first = login(sid);
        assertThat(first.status()).as("첫 로그인: %s", first.body()).isEqualTo(200);
        JwtClaimsPrincipal claims = jwtTokenProvider.validateAndGetClaims(first.cookieToken());
        assertThat(claims.ssoSid()).as("desk 토큰에 SSO 로그인 번호가 실린다").isEqualTo(sid);
        assertThat(sessionRowAlive(claims.sessionId())).isTrue();
        assertThat(check(first.cookieToken())).isEqualTo(200);

        assertThat(logout(first.cookieToken())).isEqualTo(200);

        // 담다 세션은 끝났다 — 토큰도, 저장해 둔 SSO refresh(세션 행)도
        assertThat(check(first.cookieToken())).as("로그아웃한 토큰").isEqualTo(401);
        assertThat(sessionRowAlive(claims.sessionId())).as("세션 행").isFalse();

        // SSO 로그인은 살아 있어 비밀번호 없이 인가코드가 다시 나온다(같은 sid) — 담다가 거절한다
        Res again = login(sid);
        assertThat(again.status()).as("같은 SSO 로그인으로 돌아온 교환: %s", again.body()).isEqualTo(401);
        assertThat(json(again).get("code")).isEqualTo("AUTH_030");
        assertThat(json(again).get("success")).isEqualTo(false);
        assertThat(again.cookieToken()).as("거절한 교환은 쿠키를 주지 않는다").isNull();

        // 비밀번호로 다시 로그인하면 SSO 가 새 sid 를 준다 — 통과한다
        Res relogin = login("sso-login-" + UUID.randomUUID());
        assertThat(relogin.status()).as("새 SSO 로그인: %s", relogin.body()).isEqualTo(200);
        assertThat(check(relogin.cookieToken())).isEqualTo(200);
    }

    @Test
    @DisplayName("로그아웃은 SSO 로그인 계열을 폐기하지 않는다 — 같은 브라우저의 다른 그루 서비스가 끊기지 않는다")
    void logoutDoesNotRevokeSsoLogin() {
        Res first = login("sso-login-" + UUID.randomUUID());
        assertThat(first.status()).isEqualTo(200);

        assertThat(logout(first.cookieToken())).isEqualTo(200);

        // 예전엔 커밋 뒤 다른 스레드에서 SSO /oauth2/revoke 를 불렀다 — 늦게 오는 호출까지 기다렸다가 본다
        verify(ssoOAuth2Client, after(500).never()).revokeRefreshToken(anyString());
        verify(ssoOAuth2Client, after(0).never()).revokeAllSessions(anyString());
    }

    @Test
    @DisplayName("거절 문구는 요청 언어를 따른다")
    void rejectionMessageIsLocalized() {
        String sid = "sso-login-" + UUID.randomUUID();
        logout(login(sid).cookieToken());

        assertThat(json(login(sid, "refresh-ko", "ko")).get("message")).isEqualTo("로그아웃했어요. 다시 로그인해주세요");
        assertThat(json(login(sid, "refresh-en", "en")).get("message"))
                .isEqualTo("You have logged out. Please sign in again");
    }

    @Test
    @DisplayName("sid 가 없는 옛 SSO 토큰 — desk 토큰에 싣지 않고, 로그아웃해도 기억할 것이 없어 다시 들어온다")
    void ssoTokenWithoutSid_isNeverRejected() {
        Res first = login(null);
        assertThat(first.status()).as("%s", first.body()).isEqualTo(200);
        assertThat(jwtTokenProvider.validateAndGetClaims(first.cookieToken()).hasSsoSid()).isFalse();

        assertThat(logout(first.cookieToken())).isEqualTo(200);
        assertThat(check(first.cookieToken())).isEqualTo(401);

        assertThat(login(null).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("같은 SSO 로그인으로 만든 다른 담다 세션은 살아 있고, 조용히 연장되며 sso_sid 를 유지한다")
    void otherSessionOfSameSsoLogin_survivesAndRenews() {
        String sid = "sso-login-" + UUID.randomUUID();
        Res web = login(sid);
        Res app = login(sid, "refresh-app", null); // 같은 브라우저의 SSO 로그인으로 앱도 로그인했다
        assertThat(app.status()).as("%s", app.body()).isEqualTo(200);

        assertThat(logout(web.cookieToken())).isEqualTo(200);

        assertThat(check(web.cookieToken())).as("로그아웃한 세션").isEqualTo(401);
        assertThat(check(app.cookieToken())).as("로그아웃하지 않은 세션").isEqualTo(200);

        // 앱 세션의 조용한 연장 — SSO refresh 로 받은 새 SSO 토큰에도 같은 sid 가 실려 온다.
        // 이 경로는 로그아웃 목록을 보지 않는다. 보면 웹에서 로그아웃했다고 앱까지 끊긴다.
        given(ssoOAuth2Client.refreshTokens("refresh-app"))
                .willReturn(new SsoOAuth2Client.TokenPair(ssoAccessToken(sid), "refresh-app-2"));
        String renewed = tokenExchangeService.reauthenticate(
                jwtTokenProvider.validateAndGetClaims(app.cookieToken()));

        assertThat(renewed).as("연장된 토큰").isNotNull();
        assertThat(jwtTokenProvider.validateAndGetClaims(renewed).ssoSid()).isEqualTo(sid);
        assertThat(check(renewed)).isEqualTo(200);

        // 그 뒤 앱에서도 로그아웃하면 — 연장된 토큰이 들고 있던 번호로 같은 SSO 로그인이 기억된 채다
        assertThat(logout(renewed)).isEqualTo(200);
        assertThat(login(sid).status()).isEqualTo(401);
    }

    /**
     * 로그아웃 기록을 보는 자리는 인가코드 교환 하나다. 그러니 <b>교환을 거치지 않고 인증되는 길이 있으면
     * 그 길이 곧 우회로다</b> — 이음에는 SSO access token 을 Bearer 로 직접 받아 주는 옛 경로가 남아 있어
     * 로그아웃 기록을 보지 않고 통과됐다. 담다의 필터는 담다가 서명한 토큰(HMAC · iss · typ)만 받는다.
     * 그 사실을 서명이 멀쩡한 진짜 SSO 토큰으로 못 박는다 — 누가 하위 호환 분기를 되살리면 여기서 깨진다.
     */
    @Test
    @DisplayName("SSO access token 을 Bearer 나 쿠키로 직접 보내면 인증되지 않는다 — 교환을 거치지 않는 길이 없다")
    void ssoAccessTokenIsNotAcceptedDirectly() {
        String sid = "sso-login-" + UUID.randomUUID();
        Res first = login(sid);
        assertThat(first.status()).as("%s", first.body()).isEqualTo(200);
        assertThat(logout(first.cookieToken())).isEqualTo(200);

        // 담다에서 로그아웃한 SSO 로그인의 access token — 서명은 SSO 것 그대로 유효하다
        String loggedOut = ssoAccessToken(sid);
        assertThat(checkWithBearer(loggedOut)).as("Bearer 로 직접").isEqualTo(401);
        assertThat(check(loggedOut)).as("쿠키 자리에 직접").isEqualTo(401);

        // 로그아웃한 적 없는 SSO 로그인의 토큰도 마찬가지다 — 막힌 이유가 기록이 아니라 '길이 없어서' 다
        String fresh = ssoAccessToken("sso-login-" + UUID.randomUUID());
        assertThat(checkWithBearer(fresh)).as("Bearer 로 직접(새 로그인)").isEqualTo(401);
        assertThat(check(fresh)).as("쿠키 자리에 직접(새 로그인)").isEqualTo(401);
    }

    @Test
    @DisplayName("테스트 컨텍스트는 메모리 목록을 쓴다 — Redis 로 떨어지면 조회가 fail-open 이라 위 거절이 조용히 통과로 바뀐다")
    void testContextUsesInMemoryStore() {
        assertThat(ssoLoginLogoutStore).isInstanceOf(InMemorySsoLoginLogoutStore.class);
    }
}
