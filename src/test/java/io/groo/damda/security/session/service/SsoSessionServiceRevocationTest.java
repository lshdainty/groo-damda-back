package io.groo.damda.security.session.service;

import io.groo.core.type.YNType;
import io.groo.damda.common.config.properties.JwtProperties;
import io.groo.damda.common.crypto.AesGcmCipher;
import io.groo.damda.security.client.SsoOAuth2Client;
import io.groo.damda.security.session.domain.UserSsoSession;
import io.groo.damda.security.session.repository.UserSsoSessionRepository;
import io.groo.damda.security.session.store.SessionRevocationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 세션을 끊는 <b>모든</b> 자리가 폐기 표식을 남기는지 — QA #44.
 *
 * <p>세션 행을 지우는 것만으로는 이미 발급된 access token 이 죽지 않는다. 폐기 경로는 네
 * 곳이고(로그아웃 · 기기 하나 끊기 · 전 기기 · 관리자 강제 로그아웃 전파), 한 자리라도
 * 빠뜨리면 그 경로만 조용히 옛 동작으로 되돌아간다 — 화면상으로는 로그아웃된 것처럼 보이므로
 * 눈으로는 못 잡는다.
 *
 * <p>관리자 강제 로그아웃({@code SsoSessionEventSubscriber.handleRevokedAll})은
 * {@code revokeAll} 을 부르므로 여기서 같이 덮인다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SsoSessionServiceRevocationTest {

    private static final String SESSION_ID = "sid-1";
    /** access token 수명 1시간 → 표식 TTL 3600초. 그 뒤로는 어차피 자연 만료라 들고 있을 이유가 없다. */
    private static final long ACCESS_EXP_MS = 3_600_000L;
    private static final long TTL_SECONDS = ACCESS_EXP_MS / 1000;

    @Mock private UserSsoSessionRepository sessionRepository;
    @Mock private SsoOAuth2Client ssoOAuth2Client;
    @Mock private AesGcmCipher cipher;
    @Mock private SessionRevocationStore revocationStore;

    private SsoSessionService sut;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSessionExpiration(604_800_000L);
        props.setAccessTokenExpiration(ACCESS_EXP_MS);
        sut = new SsoSessionService(sessionRepository, ssoOAuth2Client, cipher, props, revocationStore,
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));

        given(cipher.decrypt(anyString())).willAnswer(inv -> inv.getArgument(0));
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 하루 전에 로그인한 살아 있는 세션 — 로그인 시각(create_at)은 저장 때 auditing 이 채우는 값이다. */
    private static UserSsoSession session(Long userRowId, String sessionId) {
        UserSsoSession s = UserSsoSession.issue(userRowId, sessionId, "enc", nowUtc().plusDays(3), "iPhone · Safari");
        org.springframework.test.util.ReflectionTestUtils.setField(s, "createAt", nowUtc().minusDays(1));
        return s;
    }

    private void givenStoredSession(UserSsoSession session) {
        given(sessionRepository.findBySessionIdAndIsDeleted(SESSION_ID, YNType.N))
                .willReturn(Optional.ofNullable(session));
    }

    @Test
    @DisplayName("로그아웃이 표식을 남긴다 — TTL 은 access token 수명과 같다")
    void revoke_marksSession() {
        givenStoredSession(session(7L, SESSION_ID));

        sut.revoke(SESSION_ID);

        verify(revocationStore).revoke(SESSION_ID, TTL_SECONDS);
    }

    @Test
    @DisplayName("세션 행이 없어도 표식은 남긴다 — 암호화 키가 비면 행 자체가 안 만들어진다")
    void revoke_withoutSessionRow_stillMarks() {
        givenStoredSession(null);

        sut.revoke(SESSION_ID);

        verify(revocationStore).revoke(SESSION_ID, TTL_SECONDS);
    }

    @Test
    @DisplayName("기기 하나 끊기 — 내 세션이면 표식을 남긴다")
    void revokeOwned_ownSession_marks() {
        givenStoredSession(session(7L, SESSION_ID));

        sut.revokeOwned(7L, SESSION_ID);

        verify(revocationStore).revoke(SESSION_ID, TTL_SECONDS);
    }

    @Test
    @DisplayName("남의 세션에는 표식을 남기지 않는다 — 세션 id 만으로 남의 기기를 끊게 된다")
    void revokeOwned_othersSession_doesNotMark() {
        givenStoredSession(session(99L, SESSION_ID));

        sut.revokeOwned(7L, SESSION_ID);

        verify(revocationStore, never()).revoke(anyString(), anyLong());
    }

    @Test
    @DisplayName("모든 기기 로그아웃 — 세션마다 표식을 남긴다")
    void revokeAll_marksEverySession() {
        given(sessionRepository.findAllByUserRowIdAndIsDeleted(7L, YNType.N))
                .willReturn(List.of(session(7L, "sid-a"), session(7L, "sid-b"), session(7L, "sid-c")));

        sut.revokeAll(7L);

        verify(revocationStore).revoke("sid-a", TTL_SECONDS);
        verify(revocationStore).revoke("sid-b", TTL_SECONDS);
        verify(revocationStore).revoke("sid-c", TTL_SECONDS);
    }

    // ── 재발급 중에 세션이 죽는 경로 ────────────────────────────────────
    //
    // 여기는 access token 이 이미 만료된 요청만 들어온다. 그래도 표식이 필요하다 — 갱신은
    // 같은 jti 로 재서명하므로, 같은 세션의 <b>다른 사본</b>이 아직 한 시간짜리 토큰을 들고
    // 있을 수 있다. 표식이 없으면 SSO 가 권한을 회수한 뒤에도 그 사본만 계속 돈다.

    private void givenSessionForRefresh(UserSsoSession session) {
        given(sessionRepository.findForRefresh(SESSION_ID, YNType.N)).willReturn(Optional.ofNullable(session));
    }

    @Test
    @DisplayName("세션 만료로 끊길 때도 표식을 남긴다")
    void refresh_expiredSession_marks() {
        UserSsoSession expired =
                UserSsoSession.issue(7L, SESSION_ID, "enc", nowUtc().minusDays(1), "iPhone · Safari");
        givenSessionForRefresh(expired);

        sut.refresh(SESSION_ID);

        verify(revocationStore).revoke(SESSION_ID, TTL_SECONDS);
    }

    @Test
    @DisplayName("SSO 가 재발급을 거부해 끊길 때도 표식을 남긴다 — 권한 회수가 여기로 온다")
    void refresh_ssoRefused_marks() {
        givenSessionForRefresh(session(7L, SESSION_ID));
        given(ssoOAuth2Client.refreshTokens(anyString())).willReturn(null);

        sut.refresh(SESSION_ID);

        verify(revocationStore).revoke(SESSION_ID, TTL_SECONDS);
    }

    @Test
    @DisplayName("SSO 가 잠깐 죽은 것뿐이면 표식을 남기지 않는다 — 여기서 끊으면 전원 로그아웃이다")
    void refresh_ssoOutage_doesNotMark() {
        givenSessionForRefresh(session(7L, SESSION_ID));
        given(ssoOAuth2Client.refreshTokens(anyString())).willThrow(new RuntimeException("timeout"));

        sut.refresh(SESSION_ID);

        verify(revocationStore, never()).revoke(anyString(), anyLong());
    }

    @Test
    @DisplayName("재발급이 성공하면 표식을 남기지 않는다 — 방금 살아난 세션을 죽이면 안 된다")
    void refresh_success_doesNotMark() {
        givenSessionForRefresh(session(7L, SESSION_ID));
        given(ssoOAuth2Client.refreshTokens(anyString()))
                .willReturn(new SsoOAuth2Client.TokenPair("new-access", "new-refresh"));

        sut.refresh(SESSION_ID);

        verify(revocationStore, never()).revoke(anyString(), anyLong());
    }
}
