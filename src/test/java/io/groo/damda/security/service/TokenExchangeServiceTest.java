package io.groo.damda.security.service;

import io.groo.core.exception.UnauthorizedException;
import io.groo.damda.calendar.service.UserCalendarService;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.security.client.SsoOAuth2Client;
import io.groo.damda.security.controller.dto.TokenExchangeDto;
import io.groo.damda.security.jwt.JwtTokenProvider;
import io.groo.damda.security.principal.JwtClaimsPrincipal;
import io.groo.damda.security.session.service.SsoSessionService;
import io.groo.damda.security.session.store.SsoLoginLogoutStore;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * SSO 토큰 교환(최초 프로비저닝) 회귀 방지 — 신규 사용자는 기본 캘린더를 즉시 부여하고,
 * 기존 사용자는 부여하지 않는다(중복 생성 방지).
 */
@ExtendWith(MockitoExtension.class)
class TokenExchangeServiceTest {

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private UserRepository userRepository;
    @Mock private UserCalendarService userCalendarService;
    @Mock private io.groo.damda.expense.service.ExpenseCategoryService expenseCategoryService;
    @Mock private io.groo.damda.security.client.SsoOAuth2Client ssoOAuth2Client;
    @Mock private io.groo.damda.security.session.service.SsoSessionService ssoSessionService;
    @Mock private SsoLoginLogoutStore ssoLoginLogoutStore;

    @InjectMocks private TokenExchangeService sut;

    private Claims deskClaims() {
        // 느슨하게 둔다 — 교환은 sid 를 먼저 읽고(없으면 null) 연장은 나중에 읽는다. 엄격 모드는 스텁한
        // get(...) 을 다 쓰기 전에 다른 인자로 부르면 테스트를 깨는데, 그 순서는 여기서 보려는 것이 아니다.
        Claims c = mock(Claims.class, org.mockito.Mockito.withSettings()
                .strictness(org.mockito.quality.Strictness.LENIENT));
        given(c.get("services", List.class)).willReturn(List.of("desk"));
        given(c.getSubject()).willReturn("tester");
        given(c.get("name", String.class)).willReturn("테스터");
        given(c.get("email", String.class)).willReturn("tester@porest.com");
        given(c.get("userNo", Long.class)).willReturn(100L);
        return c;
    }

    /** desk 접근이 꺼진 토큰 — 해지하면 SSO 가 services 에서 desk 를 뺀다. */
    private Claims noDeskClaims() {
        Claims c = mock(Claims.class, org.mockito.Mockito.withSettings()
                .strictness(org.mockito.quality.Strictness.LENIENT));
        given(c.get("services", List.class)).willReturn(List.of("hr"));
        given(c.getSubject()).willReturn("tester");
        return c;
    }

    /**
     * 로그인 한 번 — SSO 가 인가코드에 이 claims 의 토큰을 내준 것으로 치고 교환한다.
     * 새 로그인을 만드는 입구는 {@code exchangeCode} 하나다.
     */
    private TokenExchangeDto.Response login(Claims ssoClaims) {
        givenCodeExchange(ssoClaims);
        given(jwtTokenProvider.newSessionId()).willReturn("sid");
        return exchangeCode();
    }

    private void givenCodeExchange(Claims ssoClaims) {
        given(ssoOAuth2Client.exchangeCodeForToken("authcode", "verifier", "https://desk/auth/callback"))
                .willReturn(new SsoOAuth2Client.TokenPair("sso", "refresh"));
        given(jwtTokenProvider.validateSsoToken("sso")).willReturn(ssoClaims);
    }

    private TokenExchangeDto.Response exchangeCode() {
        return sut.exchangeCode("authcode", "verifier", "https://desk/auth/callback", "JUnit/1.0");
    }

    private User withdrawn() {
        User u = user(7L);
        u.withdraw(null);
        return u;
    }

    private User user(long rowId) {
        User u = User.createUser(100L, "tester", "테스터", "tester@porest.com");
        ReflectionTestUtils.setField(u, "rowId", rowId);
        return u;
    }

    @Test
    @DisplayName("최초 로그인(신규 사용자) — 기본 캘린더를 즉시 프로비저닝한다")
    void provisionsDefaultCalendarForNewUser() {
        Claims claims = deskClaims();
        given(userRepository.findByUserId("tester")).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willReturn(user(7L));
        given(jwtTokenProvider.createAccessToken(anyString(), anyString(), anyString(), anyLong(), anyString(), any()))
                .willReturn("access");

        login(claims);

        verify(userCalendarService).getOrCreateDefault(7L);
        verify(expenseCategoryService).seedDefaults(7L);
    }

    @Test
    @DisplayName("기존 사용자 재로그인 — 기본 캘린더를 다시 만들지 않는다")
    void doesNotProvisionForExistingUser() {
        Claims claims = deskClaims();
        given(userRepository.findByUserId("tester")).willReturn(Optional.of(user(7L)));
        given(jwtTokenProvider.createAccessToken(anyString(), anyString(), anyString(), anyLong(), anyString(), any()))
                .willReturn("access");

        login(claims);

        verify(userCalendarService, never()).getOrCreateDefault(eq(7L));
        verify(expenseCategoryService, never()).seedDefaults(eq(7L));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("exchangeCode — SSO 에 code 교환으로 ssoToken 받아 desk 토큰을 발급한다")
    void exchangeCode_exchangesViaSsoThenIssuesDeskToken() {
        given(ssoOAuth2Client.exchangeCodeForToken("authcode", "verifier", "https://desk/auth/callback"))
                .willReturn(new io.groo.damda.security.client.SsoOAuth2Client.TokenPair("sso", "refresh"));
        given(jwtTokenProvider.newSessionId()).willReturn("sid");
        Claims claims = deskClaims();
        given(jwtTokenProvider.validateSsoToken("sso")).willReturn(claims);
        given(userRepository.findByUserId("tester")).willReturn(Optional.of(user(7L)));
        given(jwtTokenProvider.createAccessToken(anyString(), anyString(), anyString(), anyLong(), anyString(), any()))
                .willReturn("desk-access");

        var resp = sut.exchangeCode("authcode", "verifier", "https://desk/auth/callback", "JUnit/1.0");

        org.assertj.core.api.Assertions.assertThat(resp.accessToken()).isEqualTo("desk-access");
        verify(ssoOAuth2Client).exchangeCodeForToken("authcode", "verifier", "https://desk/auth/callback");
        // 받은 refresh 는 세션에 남아야 한다 — 이게 빠지면 만료 후 조용히 재발급할 방법이 없다
        verify(ssoSessionService).create(7L, "sid", "refresh", "JUnit/1.0");
    }

    /**
     * 해지한 계정으로 다시 들어오려 할 때 <b>무엇으로 막히는가.</b>
     *
     * <p>해지하면 SSO 쪽 desk 접근이 꺼져 {@code services} 에서 desk 가 빠진다. 그래서
     * 해지자는 권한 검사에 먼저 걸려 <b>AUTH_003(권한 없음)</b> 으로 나갔고, 화면은 그걸
     * 보통의 로그인 실패로 읽어 안내 화면에 닿지 못했다 — 사용자는 왜 안 되는지 모른 채
     * 계속 눌러 보게 된다(2026-09-17 QA).
     */
    @Test
    @DisplayName("해지자는 '권한 없음' 이 아니라 '해지한 계정' 으로 막힌다 — 화면이 이유를 말할 수 있게")
    void withdrawnUserGetsWithdrawnCode() {
        // claims 를 먼저 만든다 — `given(...)` 안에서 또 다른 mock 을 스텁하면
        // Mockito 가 중첩 스터빙으로 보고 죽는다.
        Claims claims = noDeskClaims();
        User gone = withdrawn();
        given(userRepository.findByUserIdIncludingWithdrawn("tester")).willReturn(Optional.of(gone));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> login(claims))
                .isInstanceOf(io.groo.core.exception.ForbiddenException.class)
                .hasMessage(io.groo.damda.common.exception.DeskErrorCode.USER_WITHDRAWN.getMessageKey());
    }

    @Test
    @DisplayName("해지자가 아니면서 desk 권한이 없으면 종전대로 '권한 없음' 이다")
    void nonWithdrawnWithoutDeskStaysAccessDenied() {
        Claims claims = noDeskClaims();
        given(userRepository.findByUserIdIncludingWithdrawn("tester")).willReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> login(claims))
                .isInstanceOf(io.groo.core.exception.ForbiddenException.class)
                .hasMessage(
                        io.groo.damda.common.exception.DeskErrorCode.AUTH_ACCESS_DENIED.getMessageKey());
    }

    @Test
    @DisplayName("정상 로그인은 해지 여부를 묻지 않는다 — 질의를 늘리지 않는다")
    void happyPathDoesNotQueryWithdrawn() {
        Claims claims = deskClaims();
        given(userRepository.findByUserId("tester")).willReturn(Optional.of(user(7L)));
        given(jwtTokenProvider.createAccessToken(anyString(), anyString(), anyString(), anyLong(), anyString(), any()))
                .willReturn("access");

        login(claims);

        verify(userRepository, never()).findByUserIdIncludingWithdrawn(anyString());
    }

    // ── 서비스만 로그아웃 (보안 점검 CLI-6) ────────────────────────────────
    //
    // 로그아웃은 담다 세션만 끝내고 SSO 로그인은 그대로 둔다. 그래서 SSO 는 비밀번호 없이 인가코드를 다시
    // 내주는데, 담다가 로그아웃한 SSO 로그인(sid)을 기억했다가 그 교환을 거절한다.

    /** desk 권한이 있고 SSO 로그인 번호(sid)가 실린 SSO 토큰. */
    private Claims deskClaims(String ssoSid) {
        Claims c = deskClaims();
        given(c.get("sid", String.class)).willReturn(ssoSid);
        return c;
    }

    @Test
    @DisplayName("CLI-6: 담다에서 로그아웃한 SSO 로그인으로 돌아온 교환은 AUTH_030 으로 거절한다 — 토큰도 세션도 만들지 않는다")
    void exchangeCode_loggedOutSsoLogin_rejected() {
        // 거절은 사용자 조회보다 앞선다 — sid 와 로그용 subject 말고는 읽지 않는다
        Claims claims = mock(Claims.class);
        given(claims.get("sid", String.class)).willReturn("sso-login-1");
        given(claims.getSubject()).willReturn("tester");
        givenCodeExchange(claims);
        given(ssoLoginLogoutStore.isLoggedOut("sso-login-1")).willReturn(true);

        assertThatThrownBy(this::exchangeCode)
                .isInstanceOf(UnauthorizedException.class)
                .satisfies(e -> assertThat(((UnauthorizedException) e).getErrorCode())
                        .isEqualTo(DeskErrorCode.SSO_LOGIN_LOGGED_OUT));

        verify(jwtTokenProvider, never()).createAccessToken(any(), any(), any(), any(), any(), any());
        verify(jwtTokenProvider, never()).newSessionId();
        verify(ssoSessionService, never()).create(any(), any(), any(), any());
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("CLI-6: 로그아웃한 적 없는 SSO 로그인(새 sid)은 통과하고, 그 번호가 desk 토큰에 실린다")
    void exchangeCode_freshSsoLogin_passesAndCarriesSsoSid() {
        Claims claims = deskClaims("sso-login-2");
        given(ssoLoginLogoutStore.isLoggedOut("sso-login-2")).willReturn(false);
        given(userRepository.findByUserId("tester")).willReturn(Optional.of(user(7L)));
        given(jwtTokenProvider.createAccessToken("tester", "테스터", "tester@porest.com", 7L, "sid", "sso-login-2"))
                .willReturn("desk-access");

        assertThat(login(claims).accessToken()).isEqualTo("desk-access");

        verify(ssoSessionService).create(7L, "sid", "refresh", "JUnit/1.0");
    }

    @Test
    @DisplayName("CLI-6: sid 가 없는 옛 SSO 토큰은 검사 없이 통과한다 — desk 토큰에도 싣지 않는다")
    void exchangeCode_ssoTokenWithoutSid_skipsCheck() {
        Claims claims = deskClaims(null);
        given(userRepository.findByUserId("tester")).willReturn(Optional.of(user(7L)));
        given(jwtTokenProvider.createAccessToken("tester", "테스터", "tester@porest.com", 7L, "sid", null))
                .willReturn("desk-access");

        assertThat(login(claims).accessToken()).isEqualTo("desk-access");

        verifyNoInteractions(ssoLoginLogoutStore);
    }

    @Test
    @DisplayName("CLI-6: 빈 sid 도 없는 것으로 본다")
    void exchangeCode_blankSid_treatedAsMissing() {
        Claims claims = deskClaims(" ");
        given(userRepository.findByUserId("tester")).willReturn(Optional.of(user(7L)));
        given(jwtTokenProvider.createAccessToken("tester", "테스터", "tester@porest.com", 7L, "sid", null))
                .willReturn("desk-access");

        assertThat(login(claims).accessToken()).isEqualTo("desk-access");

        verifyNoInteractions(ssoLoginLogoutStore);
    }

    @Test
    @DisplayName("CLI-6: Redis 가 죽어 로그아웃 목록을 못 읽어도 로그인은 된다 — 닫으면 그동안 아무도 못 들어온다")
    void exchangeCode_redisDown_stillLogsIn() {
        org.springframework.data.redis.core.StringRedisTemplate deadRedis =
                mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        given(deadRedis.hasKey(anyString()))
                .willThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        // 운영 구현(Redis)을 그대로 끼운다 — 예외를 삼키고 통과시키는 것까지가 이 테스트의 대상이다
        TokenExchangeService withDeadRedis = new TokenExchangeService(jwtTokenProvider, userRepository,
                userCalendarService, expenseCategoryService, ssoOAuth2Client, ssoSessionService,
                new io.groo.damda.security.session.store.RedisSsoLoginLogoutStore(deadRedis));
        Claims claims = deskClaims("sso-login-1");
        givenCodeExchange(claims);
        given(jwtTokenProvider.newSessionId()).willReturn("sid");
        given(userRepository.findByUserId("tester")).willReturn(Optional.of(user(7L)));
        given(jwtTokenProvider.createAccessToken("tester", "테스터", "tester@porest.com", 7L, "sid", "sso-login-1"))
                .willReturn("desk-access");

        TokenExchangeDto.Response resp =
                withDeadRedis.exchangeCode("authcode", "verifier", "https://desk/auth/callback", "JUnit/1.0");

        assertThat(resp.accessToken()).isEqualTo("desk-access");
        verify(deadRedis).hasKey("logged-out-sid:sso-login-1");
    }

    private static JwtClaimsPrincipal expiredToken(String ssoSid) {
        return new JwtClaimsPrincipal("tester", "테스터", "tester@porest.com", 7L, "access", "sid", ssoSid);
    }

    @Test
    @DisplayName("CLI-6: 조용한 연장(SSO 를 부른 경우) — 새 SSO 토큰의 sid 가 다시 발급되는 desk 토큰에 유지된다")
    void reauthenticate_viaSso_keepsSsoSid() {
        Claims claims = deskClaims("sso-login-1");
        given(jwtTokenProvider.validateSsoToken("sso-new")).willReturn(claims);
        given(userRepository.findByUserId("tester")).willReturn(Optional.of(user(7L)));
        given(jwtTokenProvider.createAccessToken("tester", "테스터", "tester@porest.com", 7L, "sid", "sso-login-1"))
                .willReturn("renewed");
        given(ssoSessionService.refresh(eq("sid"), any())).willAnswer(inv -> {
            Consumer<String> beforeCommit = inv.getArgument(1);
            beforeCommit.accept("sso-new");
            return new SsoSessionService.RefreshResult(true, "sso-new");
        });

        assertThat(sut.reauthenticate(expiredToken("sso-login-1"))).isEqualTo("renewed");
    }

    @Test
    @DisplayName("CLI-6: 조용한 연장은 로그아웃 목록을 보지 않는다 — 같은 SSO 로그인으로 만든 다른 세션은 로그아웃하지 않았다")
    void reauthenticate_doesNotConsultLogoutList() {
        Claims claims = deskClaims("sso-login-1");
        given(jwtTokenProvider.validateSsoToken("sso-new")).willReturn(claims);
        given(userRepository.findByUserId("tester")).willReturn(Optional.of(user(7L)));
        given(jwtTokenProvider.createAccessToken(anyString(), anyString(), anyString(), anyLong(), anyString(), any()))
                .willReturn("renewed");
        given(ssoSessionService.refresh(eq("sid"), any())).willAnswer(inv -> {
            Consumer<String> beforeCommit = inv.getArgument(1);
            beforeCommit.accept("sso-new");
            return new SsoSessionService.RefreshResult(true, "sso-new");
        });

        // 웹에서 로그아웃해 이 SSO 로그인이 목록에 들어가 있어도, 살아 있는 다른 세션(앱)의 연장은 된다
        assertThat(sut.reauthenticate(expiredToken("sso-login-1"))).isEqualTo("renewed");

        verifyNoInteractions(ssoLoginLogoutStore);
    }

    @Test
    @DisplayName("CLI-6: 조용한 연장(앞선 요청이 방금 갱신) — SSO 를 부르지 않아도 만료 토큰의 sso_sid 를 그대로 싣는다")
    void reauthenticate_reusedExisting_keepsSsoSid() {
        given(ssoSessionService.refresh(eq("sid"), any()))
                .willReturn(new SsoSessionService.RefreshResult(true, null));
        given(jwtTokenProvider.createAccessToken("tester", "테스터", "tester@porest.com", 7L, "sid", "sso-login-1"))
                .willReturn("renewed");

        assertThat(sut.reauthenticate(expiredToken("sso-login-1"))).isEqualTo("renewed");

        verify(jwtTokenProvider, never()).validateSsoToken(anyString());
    }

    // ── SSO 가 끊은 로그인(CLI-6) — 재발급 경로 ─────────────────────────────

    private static JwtClaimsPrincipal sessionOfSsoLogin(String ssoSid) {
        return new JwtClaimsPrincipal("tester", "테스터", "tester@porest.com", 7L, "access", "sid", ssoSid);
    }

    @Test
    @DisplayName("CLI-6: SSO 가 끊은 로그인의 세션은 재발급하지 않고, 그 세션 행을 지운다 — SSO 를 부르지도 않는다")
    void reauthenticate_revokedSsoLogin_isDeniedAndSessionRemoved() {
        given(ssoSessionService.isSsoLoginRevoked("sso-login-1")).willReturn(true);

        String reissued = sut.reauthenticate(sessionOfSsoLogin("sso-login-1"));

        assertThat(reissued).isNull();
        // 더 쓸 수 없는 세션이다 — "로그인된 기기" 목록에서도 사라지게 행을 지운다
        verify(ssoSessionService).revoke("sid");
        // 앞선 요청이 방금 갱신해 둔 유예 구간이어도 같은 sso_sid 로 다시 서명하지 않는다
        verify(ssoSessionService, never()).refresh(anyString(), any());
        verify(jwtTokenProvider, never()).createAccessToken(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("CLI-6: 끊기지 않은 SSO 로그인의 세션은 전처럼 재발급 경로를 탄다")
    void reauthenticate_liveSsoLogin_goesThroughRefresh() {
        given(ssoSessionService.isSsoLoginRevoked("sso-login-1")).willReturn(false);
        given(ssoSessionService.refresh(eq("sid"), any()))
                .willReturn(new io.groo.damda.security.session.service.SsoSessionService.RefreshResult(false, null));

        assertThat(sut.reauthenticate(sessionOfSsoLogin("sso-login-1"))).isNull();

        verify(ssoSessionService).refresh(eq("sid"), any());
        verify(ssoSessionService, never()).revoke(anyString());
    }

    @Test
    @DisplayName("CLI-6: SSO 로그인 번호가 없는 옛 세션은 그 표식을 보지 않는다")
    void reauthenticate_withoutSsoSid_skipsLookup() {
        given(ssoSessionService.refresh(eq("sid"), any()))
                .willReturn(new io.groo.damda.security.session.service.SsoSessionService.RefreshResult(false, null));

        sut.reauthenticate(sessionOfSsoLogin(null));

        verify(ssoSessionService, never()).isSsoLoginRevoked(any());
    }
}
