package io.groo.damda.common.event;

import io.groo.core.security.event.SsoEventVerifier;
import org.mockito.Spy;
import io.groo.damda.security.session.service.SsoSessionService;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * SSO 가 내리는 세션 폐기 이벤트를 실제로 알아듣는지.
 *
 * <p>여기서 쓰는 JSON 은 <b>발행 쪽(porest-sso-back {@code SessionEvent})이 실제로 내보내는
 * 모양 그대로</b>다. 두 레포 사이에 공유 타입도 코드젠도 없어, 어긋나도 컴파일은 통과하고
 * 운영에서 조용히 로그아웃만 전파되지 않는다 — 그 계약을 여기서 못 박는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SsoSessionEventSubscriberTest {

    @Mock private UserRepository userRepository;
    @Mock private SsoSessionService ssoSessionService;
    /** 전환 기간 설정(서명 없음 허용) — 서명 판정은 SsoEventSignatureWiringTest 가 본다 */
    @Spy private SsoEventVerifier ssoEventVerifier = new SsoEventVerifier(h -> null, null, false);
    @InjectMocks private SsoSessionEventSubscriber sut;

    /** 발행 쪽이 내보내는 그대로. */
    private static final String REVOKED_ALL = """
            {"type":"SESSION_REVOKED_ALL","ssoUserRowId":7,"reason":"admin-force-logout",\
            "timestamp":"2026-08-24T10:30:00"}""";

    /** 로그인 한 건 폐기(CLI-6) — 발행 쪽은 계열 번호(sessionId)를 싣는다. 전체 폐기에는 null 로 온다. */
    private static final String REVOKED = """
            {"type":"SESSION_REVOKED","ssoUserRowId":7,"sessionId":"sso-login-1","reason":"logout",\
            "timestamp":"2026-11-17T10:30:00"}""";

    private User deskUser(Long rowId) {
        User user = User.createUser(7L, "hong", "홍길동", "hong@hangroo.io");
        ReflectionTestUtils.setField(user, "rowId", rowId);
        return user;
    }

    @Test
    @DisplayName("전체 폐기를 받으면 그 사용자의 desk 세션을 전부 끊는다")
    void revokedAll_revokesDeskSessions() {
        given(userRepository.findBySsoUserRowId(7L)).willReturn(Optional.of(deskUser(42L)));

        sut.handleSessionEvent(REVOKED_ALL);

        // SSO 의 users.row_id 가 아니라 desk 의 PK 로 끊어야 한다 — 헷갈리면 남의 세션을 끊는다.
        verify(ssoSessionService).revokeAll(42L);
    }

    @Test
    @DisplayName("desk 를 안 쓰는 SSO 사용자면 넘어간다 — 끊을 세션이 없는 건 정상이다")
    void revokedAll_unknownUser_skips() {
        given(userRepository.findBySsoUserRowId(anyLong())).willReturn(Optional.empty());

        sut.handleSessionEvent(REVOKED_ALL);

        verify(ssoSessionService, never()).revokeAll(any());
    }

    @Test
    @DisplayName("발행 쪽이 필드를 더해도 계속 읽는다 — 공유 타입이 없어 이쪽이 늘 늦게 따라온다")
    void unknownField_stillParses() {
        given(userRepository.findBySsoUserRowId(7L)).willReturn(Optional.of(deskUser(42L)));

        sut.handleSessionEvent("""
                {"type":"SESSION_REVOKED_ALL","ssoUserRowId":7,"reason":"logout",\
                "timestamp":"2026-08-24T10:30:00","sessionId":"future-field"}""");

        verify(ssoSessionService).revokeAll(42L);
    }

    @Test
    @DisplayName("CLI-6: 로그인 한 건 폐기를 받으면 그 SSO 로그인의 desk 세션만 끊는다 — 사용자 전체는 끊지 않는다")
    void revoked_cutsOnlyThatSsoLogin() {
        sut.handleSessionEvent(REVOKED);

        verify(ssoSessionService).revokeSsoLogin("sso-login-1");
        verify(ssoSessionService, never()).revokeAll(any());
        // 로그인 번호 하나로 대조한다 — 사용자를 찾지 않는다
        verify(userRepository, never()).findBySsoUserRowId(any());
    }

    @Test
    @DisplayName("CLI-6: 사용자 번호가 비어 있어도 끊고, 로그인 번호가 없으면 아무것도 하지 않는다")
    void revoked_needsOnlySessionId() {
        sut.handleSessionEvent("""
                {"type":"SESSION_REVOKED","ssoUserRowId":null,"sessionId":"sso-login-2","reason":"oauth2-revoke",\
                "timestamp":"2026-11-17T10:30:00"}""");
        sut.handleSessionEvent("""
                {"type":"SESSION_REVOKED","ssoUserRowId":7,"sessionId":null,"reason":"logout",\
                "timestamp":"2026-11-17T10:30:00"}""");
        sut.handleSessionEvent("""
                {"type":"SESSION_REVOKED","ssoUserRowId":7,"reason":"logout","timestamp":"2026-11-17T10:30:00"}""");

        verify(ssoSessionService).revokeSsoLogin("sso-login-2");
        verify(ssoSessionService, org.mockito.Mockito.times(1)).revokeSsoLogin(any());
    }

    @Test
    @DisplayName("전체 폐기에 실려 오는 빈 sessionId 는 무시된다 — 예전처럼 사용자 전체를 끊는다")
    void revokedAll_withNullSessionId() {
        given(userRepository.findBySsoUserRowId(7L)).willReturn(Optional.of(deskUser(42L)));

        sut.handleSessionEvent("""
                {"type":"SESSION_REVOKED_ALL","ssoUserRowId":7,"sessionId":null,"reason":"password-changed",\
                "timestamp":"2026-11-17T10:30:00"}""");

        verify(ssoSessionService).revokeAll(42L);
        verify(ssoSessionService, never()).revokeSsoLogin(any());
    }

    @Test
    @DisplayName("모르는 타입은 무시한다 — 발행 쪽이 먼저 나가는 건 정상적인 시차다")
    void unknownType_ignored() {
        sut.handleSessionEvent("""
                {"type":"SESSION_REVOKED_ONE","ssoUserRowId":7,"timestamp":"2026-08-24T10:30:00"}""");

        verify(ssoSessionService, never()).revokeAll(any());
    }

    @Test
    @DisplayName("깨진 메시지는 삼킨다 — 리스너에서 예외가 올라가도 재시도할 방법이 없다")
    void brokenMessage_doesNotThrow() {
        sut.handleSessionEvent("not json");
        sut.handleSessionEvent("""
                {"type":"SESSION_REVOKED_ALL","ssoUserRowId":null,"timestamp":"2026-08-24T10:30:00"}""");

        verify(ssoSessionService, never()).revokeAll(any());
    }
}
