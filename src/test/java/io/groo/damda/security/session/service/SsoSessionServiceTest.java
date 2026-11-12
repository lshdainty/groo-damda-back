package io.groo.damda.security.session.service;

import io.groo.core.type.YNType;
import io.groo.damda.common.config.properties.JwtProperties;
import io.groo.damda.common.crypto.AesGcmCipher;
import io.groo.damda.security.client.SsoOAuth2Client;
import io.groo.damda.security.session.domain.UserSsoSession;
import io.groo.damda.security.session.repository.UserSsoSessionRepository;
import io.groo.damda.security.session.store.InMemorySessionRevocationStore;
import io.groo.damda.security.session.controller.dto.SessionApiDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 무음 재인증의 세션 쪽 단위 테스트.
 *
 * <p>여기서 잘못되면 증상이 "가끔 로그아웃됨" 으로 나타나 재현이 어렵다. 특히 동시 요청과
 * SSO 장애 구분은 눈으로 검증할 수 없어 테스트로 못 박는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SsoSessionServiceTest {

    private static final String SESSION_ID = "sid-1";

    @Mock private UserSsoSessionRepository sessionRepository;
    @Mock private SsoOAuth2Client ssoOAuth2Client;
    @Mock private AesGcmCipher cipher;

    private SsoSessionService sut;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSessionExpiration(604_800_000L); // 7일
        props.setAccessTokenExpiration(3_600_000L); // 폐기 표식 TTL 의 근거
        // 폐기 표식이 실제로 남는지는 SsoSessionServiceRevocationTest 가 본다.
        sut = new SsoSessionService(
                sessionRepository, ssoOAuth2Client, cipher, props, new InMemorySessionRevocationStore());

        given(cipher.isConfigured()).willReturn(true);
        given(cipher.encrypt(anyString())).willAnswer(inv -> "enc(" + inv.getArgument(0) + ")");
        given(cipher.decrypt(anyString())).willAnswer(inv -> {
            String v = inv.getArgument(0);
            return v.startsWith("enc(") ? v.substring(4, v.length() - 1) : v;
        });
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 살아 있고 아직 갱신된 적 없는 세션 — 하루 전에 로그인했다. */
    private UserSsoSession liveSession() {
        return loggedInAt(UserSsoSession.issue(7L, SESSION_ID, "enc(old-refresh)", nowUtc().plusDays(3), "iPhone"),
                nowUtc().minusDays(1));
    }

    /** 로그인 시각(create_at) — 저장 때 auditing 이 채우는 값을 테스트에서 직접 넣는다. */
    private static UserSsoSession loggedInAt(UserSsoSession session, LocalDateTime loginAt) {
        org.springframework.test.util.ReflectionTestUtils.setField(session, "createAt", loginAt);
        return session;
    }

    private void givenSession(UserSsoSession session) {
        given(sessionRepository.findForRefresh(SESSION_ID, YNType.N)).willReturn(Optional.ofNullable(session));
    }

    // ── create ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("로그인 — refresh 를 암호문으로 저장한다(평문 미저장)")
    void create_storesEncrypted() {
        sut.create(7L, SESSION_ID, "raw-refresh",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 Safari/604.1");

        ArgumentCaptor<UserSsoSession> captor = ArgumentCaptor.forClass(UserSsoSession.class);
        verify(sessionRepository).save(captor.capture());
        UserSsoSession saved = captor.getValue();
        // 평문이 아니라 cipher 를 거친 값이 저장되는지만 본다 — 실제 암호 강도는 AesGcmCipher 몫이다
        assertThat(saved.getRefreshTokenEnc()).isEqualTo("enc(raw-refresh)");
        assertThat(saved.getSessionId()).isEqualTo(SESSION_ID);
        // UA 원문이 아니라 사람이 읽을 이름이 저장된다(UserAgentParser).
        assertThat(saved.getDeviceLabel()).isEqualTo("iPhone · Safari");
    }

    @Test
    @DisplayName("SSO 가 refresh 를 안 주면 세션을 만들지 않는다 — 로그인 자체는 막지 않는다")
    void create_withoutRefreshToken_skips() {
        sut.create(7L, SESSION_ID, null, "iPhone");
        sut.create(7L, SESSION_ID, "  ", "iPhone");

        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("암호화 키가 없으면 세션을 만들지 않는다 — 로그인까지 막으면 안 된다")
    void create_withoutCipherKey_skipsInsteadOfThrowing() {
        given(cipher.isConfigured()).willReturn(false);

        sut.create(7L, SESSION_ID, "raw-refresh", "iPhone"); // 예외 없이 넘어가야 한다

        // 평문으로 저장하는 선택지는 없다. 무음 재인증만 못 하고 로그인은 정상 동작한다.
        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("못 알아본 User-Agent 는 기기명 없이 저장한다 — 화면이 '알 수 없는 기기' 로 그린다")
    void create_unknownUserAgent_storesNullLabel() {
        sut.create(7L, SESSION_ID, "raw", "U".repeat(500));

        ArgumentCaptor<UserSsoSession> captor = ArgumentCaptor.forClass(UserSsoSession.class);
        verify(sessionRepository).save(captor.capture());
        // 예전에는 UA 원문을 200자로 잘라 담았다 — 사용자가 알아볼 수 없는 문자열이었다.
        assertThat(captor.getValue().getDeviceLabel()).isNull();
    }

    // ── refresh: 정상 ────────────────────────────────────────────────────

    @Test
    @DisplayName("재발급 성공 — 새 SSO 토큰을 돌려주고 세션의 refresh 를 갈아끼운다")
    void refresh_success_rotatesStoredToken() {
        UserSsoSession session = liveSession();
        givenSession(session);
        given(ssoOAuth2Client.refreshTokens("old-refresh"))
                .willReturn(new SsoOAuth2Client.TokenPair("new-sso-access", "new-refresh"));

        SsoSessionService.RefreshResult result = sut.refresh(SESSION_ID);

        assertThat(result.renewed()).isTrue();
        assertThat(result.ssoAccessToken()).isEqualTo("new-sso-access");
        // 옛 암호문이 남으면 다음 재발급이 이미 폐기된 토큰으로 나간다
        assertThat(session.getRefreshTokenEnc()).isEqualTo("enc(new-refresh)");
        assertThat(session.getLastUsedAt()).isNotNull();
    }

    @Test
    @DisplayName("재발급마다 만료를 미룬다 — 계속 쓰는 사용자는 로그아웃되지 않는다")
    void refresh_extendsExpiry() {
        UserSsoSession session = liveSession();
        LocalDateTime before = session.getExpiresAt();
        givenSession(session);
        given(ssoOAuth2Client.refreshTokens(anyString()))
                .willReturn(new SsoOAuth2Client.TokenPair("a", "r"));

        sut.refresh(SESSION_ID);

        assertThat(session.getExpiresAt()).isAfter(before);
    }

    // ── refresh: 동시 요청 ───────────────────────────────────────────────

    @Test
    @DisplayName("앞선 요청이 방금 갱신했으면 SSO 를 다시 부르지 않는다")
    void refresh_withinGrace_doesNotCallSso() {
        UserSsoSession session = liveSession();
        // 1초 전에 갱신됨 — 뒤따라온 요청이 락을 얻은 상황
        ReflectionTestUtils.setField(session, "lastUsedAt", nowUtc().minusSeconds(1));
        givenSession(session);

        SsoSessionService.RefreshResult result = sut.refresh(SESSION_ID);

        // 또 부르면 rotation 때문에 앞선 요청이 방금 받은 토큰이 폐기된다 → 멀쩡한 사용자가 로그아웃
        verify(ssoOAuth2Client, never()).refreshTokens(anyString());
        assertThat(result.renewed()).isTrue();
        assertThat(result.ssoAccessToken()).isNull(); // 세션은 그대로, desk 토큰만 새로 만들면 된다
    }

    @Test
    @DisplayName("갱신한 지 오래됐으면 정상적으로 SSO 를 부른다 — 유예가 영구 면제가 되면 안 된다")
    void refresh_afterGrace_callsSso() {
        UserSsoSession session = liveSession();
        ReflectionTestUtils.setField(session, "lastUsedAt", nowUtc().minusMinutes(5));
        givenSession(session);
        given(ssoOAuth2Client.refreshTokens("old-refresh"))
                .willReturn(new SsoOAuth2Client.TokenPair("a", "r"));

        SsoSessionService.RefreshResult result = sut.refresh(SESSION_ID);

        assertThat(result.ssoAccessToken()).isEqualTo("a");
    }

    // ── refresh: 실패 갈래 ───────────────────────────────────────────────

    @Test
    @DisplayName("세션이 없으면 재발급하지 않는다 — 세션 도입 前 토큰·로그아웃된 세션")
    void refresh_noSession_fails() {
        givenSession(null);

        assertThat(sut.refresh(SESSION_ID).renewed()).isFalse();
        verify(ssoOAuth2Client, never()).refreshTokens(anyString());
    }

    @Test
    @DisplayName("세션이 만료됐으면 SSO 를 부르지 않고 끊는다 — 어차피 거부당한다")
    void refresh_expiredSession_revokesWithoutCallingSso() {
        UserSsoSession session = UserSsoSession.issue(
                7L, SESSION_ID, "enc(old-refresh)", nowUtc().minusMinutes(1), "iPhone");
        givenSession(session);

        assertThat(sut.refresh(SESSION_ID).renewed()).isFalse();
        verify(ssoOAuth2Client, never()).refreshTokens(anyString());
        assertThat(session.isActive()).isFalse();
    }

    @Test
    @DisplayName("로그인부터 30일이 지나면 쓰는 중이어도 재발급하지 않고 끊는다 — SSO 를 부르지 않는다(보안 점검 DAM-5)")
    void refresh_pastAbsoluteLifetime_revokesWithoutCallingSso() {
        // 미끄럼 만료는 아직 남았다(계속 써 왔다) — 그래도 절대 수명이 끝났다.
        UserSsoSession session = loggedInAt(
                UserSsoSession.issue(7L, SESSION_ID, "enc(old-refresh)", nowUtc().plusDays(5), "iPhone"),
                nowUtc().minusDays(30).minusMinutes(1));
        givenSession(session);

        assertThat(sut.refresh(SESSION_ID).renewed()).isFalse();
        verify(ssoOAuth2Client, never()).refreshTokens(anyString());
        assertThat(session.isActive()).isFalse();
    }

    @Test
    @DisplayName("로그인 29일째는 그대로 재발급된다 — 절대 수명이 멀쩡한 세션을 끊지 않는다")
    void refresh_withinAbsoluteLifetime_renews() {
        UserSsoSession session = loggedInAt(
                UserSsoSession.issue(7L, SESSION_ID, "enc(old-refresh)", nowUtc().plusDays(5), "iPhone"),
                nowUtc().minusDays(29));
        givenSession(session);
        given(ssoOAuth2Client.refreshTokens("old-refresh"))
                .willReturn(new SsoOAuth2Client.TokenPair("new-access", "new-refresh"));

        assertThat(sut.refresh(SESSION_ID).renewed()).isTrue();
        // 재발급해도 로그인 시각은 그대로다 — 무음 재인증으로 절대 수명이 늘어나지 않는다.
        assertThat(session.getCreateAt()).isBefore(nowUtc().minusDays(28));
    }

    @Test
    @DisplayName("로그인 시각을 모르는 세션은 재발급하지 않는다 — 근거 없이 늘리지 않는다")
    void refresh_unknownLoginTime_refuses() {
        givenSession(UserSsoSession.issue(7L, SESSION_ID, "enc(old-refresh)", nowUtc().plusDays(5), "iPhone"));

        assertThat(sut.refresh(SESSION_ID).renewed()).isFalse();
        verify(ssoOAuth2Client, never()).refreshTokens(anyString());
    }

    @Test
    @DisplayName("SSO 가 거부하면(4xx) 세션을 끊는다 — 다시 시도해도 결과가 같다")
    void refresh_rejectedBySso_revokes() {
        UserSsoSession session = liveSession();
        givenSession(session);
        given(ssoOAuth2Client.refreshTokens("old-refresh")).willReturn(null);

        assertThat(sut.refresh(SESSION_ID).renewed()).isFalse();
        assertThat(session.isActive()).isFalse();
    }

    @Test
    @DisplayName("SSO 장애(예외)면 세션을 끊지 않는다 — 여기서 끊으면 SSO 재시작에 전원이 로그아웃된다")
    void refresh_ssoOutage_keepsSession() {
        UserSsoSession session = liveSession();
        givenSession(session);
        given(ssoOAuth2Client.refreshTokens("old-refresh"))
                .willThrow(new RuntimeException("connect timed out"));

        assertThat(sut.refresh(SESSION_ID).renewed()).isFalse();
        // 세션이 살아 있어야 다음 요청에 다시 시도된다
        assertThat(session.isActive()).isTrue();
        assertThat(session.getRefreshTokenEnc()).isEqualTo("enc(old-refresh)");
    }

    @Test
    @DisplayName("rotation 이 안 오면 세션을 끊는다 — 옛 토큰은 이미 폐기됐을 수 있다")
    void refresh_withoutNewRefreshToken_revokes() {
        UserSsoSession session = liveSession();
        givenSession(session);
        given(ssoOAuth2Client.refreshTokens("old-refresh"))
                .willReturn(new SsoOAuth2Client.TokenPair("new-access", null));

        assertThat(sut.refresh(SESSION_ID).renewed()).isFalse();
        assertThat(session.isActive()).isFalse();
    }

    // ── revoke ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("로그아웃 — 이 기기 세션만 끊는다")
    void revoke_marksDeleted() {
        UserSsoSession session = liveSession();
        given(sessionRepository.findBySessionIdAndIsDeleted(SESSION_ID, YNType.N))
                .willReturn(Optional.of(session));

        sut.revoke(SESSION_ID);

        assertThat(session.isActive()).isFalse();
    }

    // ── 기기 목록 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("만료된 세션은 목록에서 뺀다 — 못 쓰는 기기를 두면 로그아웃이 안 된다고 헤맨다")
    void listDevices_excludesExpired() {
        UserSsoSession live = UserSsoSession.issue(7L, "live", "enc", nowUtc().plusDays(3), "iPhone · Safari");
        UserSsoSession dead = UserSsoSession.issue(7L, "dead", "enc", nowUtc().minusDays(1), "Windows · Chrome");
        given(sessionRepository.findAllByUserRowIdAndIsDeleted(7L, YNType.N))
                .willReturn(List.of(live, dead));

        var devices = sut.listDevices(7L, "live");

        assertThat(devices).extracting(SessionApiDto.DeviceRes::sessionId).containsExactly("live");
    }

    @Test
    @DisplayName("지금 쓰는 기기에 current 를 세운다")
    void listDevices_marksCurrent() {
        UserSsoSession a = UserSsoSession.issue(7L, "sid-a", "enc", nowUtc().plusDays(3), "iPhone · Safari");
        UserSsoSession b = UserSsoSession.issue(7L, "sid-b", "enc", nowUtc().plusDays(3), "Windows · Chrome");
        given(sessionRepository.findAllByUserRowIdAndIsDeleted(7L, YNType.N))
                .willReturn(List.of(a, b));

        var devices = sut.listDevices(7L, "sid-b");

        assertThat(devices).filteredOn(SessionApiDto.DeviceRes::current)
                .extracting(SessionApiDto.DeviceRes::sessionId).containsExactly("sid-b");
    }

    // ── 기기 하나 로그아웃 ────────────────────────────────────────────────

    @Test
    @DisplayName("남의 세션은 못 끊는다 — 세션 id 만으로 끊게 두면 아무 기기나 끊을 수 있다")
    void revokeOwned_otherUsersSession_refuses() {
        UserSsoSession othersSession =
                UserSsoSession.issue(99L, SESSION_ID, "enc", nowUtc().plusDays(3), "iPhone · Safari");
        given(sessionRepository.findBySessionIdAndIsDeleted(SESSION_ID, YNType.N))
                .willReturn(Optional.of(othersSession));

        boolean revoked = sut.revokeOwned(7L, SESSION_ID);

        assertThat(revoked).isFalse();
        assertThat(othersSession.isActive()).isTrue();
    }

    @Test
    @DisplayName("내 세션이면 끊는다")
    void revokeOwned_ownSession_revokes() {
        UserSsoSession mine = liveSession();
        given(sessionRepository.findBySessionIdAndIsDeleted(SESSION_ID, YNType.N))
                .willReturn(Optional.of(mine));

        assertThat(sut.revokeOwned(7L, SESSION_ID)).isTrue();
        assertThat(mine.isActive()).isFalse();
    }

    @Test
    @DisplayName("없는 세션 로그아웃은 조용히 넘어간다 — 로그아웃이 에러가 되면 안 된다")
    void revoke_missingSession_doesNotThrow() {
        given(sessionRepository.findBySessionIdAndIsDeleted(SESSION_ID, YNType.N))
                .willReturn(Optional.empty());

        sut.revoke(SESSION_ID); // 예외 없음
    }

    // ── 모든 기기 로그아웃 ────────────────────────────────────────────────

    @Test
    @DisplayName("모든 기기 로그아웃 — 지금 쓰는 기기까지 전부 끊는다")
    void revokeAll_revokesEverySessionIncludingCurrent() {
        UserSsoSession current = UserSsoSession.issue(7L, "sid-a", "enc", nowUtc().plusDays(3), "iPhone · Safari");
        UserSsoSession other = UserSsoSession.issue(7L, "sid-b", "enc", nowUtc().plusDays(3), "Windows · Chrome");
        given(sessionRepository.findAllByUserRowIdAndIsDeleted(7L, YNType.N))
                .willReturn(List.of(current, other));

        sut.revokeAll(7L);

        assertThat(current.isActive()).isFalse();
        assertThat(other.isActive()).isFalse();
    }

    @Test
    @DisplayName("세션이 없어도 예외 없이 넘어간다")
    void revokeAll_noSessions_doesNotThrow() {
        given(sessionRepository.findAllByUserRowIdAndIsDeleted(7L, YNType.N))
                .willReturn(List.of());

        sut.revokeAll(7L); // 예외 없음
    }
}
