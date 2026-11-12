package io.groo.damda.common.event;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import io.groo.core.security.event.SsoEventSignature;
import io.groo.core.security.event.SsoEventSigner;
import io.groo.core.security.event.SsoEventVerifier;
import io.groo.damda.security.session.service.SsoSessionService;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import io.jsonwebtoken.Locator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Key;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 보안 점검 IEU-6 — 담다 구독자는 SSO 서명을 확인한 세션 폐기만 처리한다.
 *
 * <p>예전엔 Redis 로 들어온 SESSION_REVOKED_ALL 을 그대로 믿어, Redis 에 발행할 수 있는 누구든 남의 담다 세션을
 * 끊을 수 있었다. 발행은 core {@link SsoEventSigner}(SSO 와 같은 코드)로 흉내 내고, 공개키는 고정 키를 돌려준다.
 */
@DisplayName("SSO 이벤트 서명 확인 (IEU-6)")
class SsoEventSignatureWiringTest {

    private static final String REVOKED_ALL =
            "{\"type\":\"SESSION_REVOKED_ALL\",\"ssoUserRowId\":7,\"reason\":\"admin-force-logout\"}";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final SsoSessionService ssoSessionService = mock(SsoSessionService.class);
    private RSAKey ssoKey;

    @BeforeEach
    void setUp() throws Exception {
        ssoKey = new RSAKeyGenerator(2048).keyID("k1").generate();
        User hong = User.createUser(7L, "hong", "홍길동", "hong@example.com");
        ReflectionTestUtils.setField(hong, "rowId", 70L);
        given(userRepository.findBySsoUserRowId(7L)).willReturn(Optional.of(hong));
    }

    private SsoSessionEventSubscriber subscriber(boolean required) throws Exception {
        Key publicKey = ssoKey.toRSAPublicKey();
        Locator<Key> locator = header -> publicKey;
        return new SsoSessionEventSubscriber(userRepository, ssoSessionService,
                new SsoEventVerifier(locator, null, required));
    }

    @Test
    @DisplayName("SSO 가 서명한 세션 폐기는 처리한다")
    void signedProcessed() throws Exception {
        String signed = new SsoEventSigner(ssoKey.toRSAPrivateKey(), "k1")
                .sign(SsoEventSignature.SESSION_EVENTS_CHANNEL, REVOKED_ALL);

        subscriber(true).handleSessionEvent(signed);

        verify(ssoSessionService).revokeAll(70L);
    }

    @Test
    @DisplayName("다른 키로 서명한(위조) 세션 폐기는 전환 기간이어도 처리하지 않는다")
    void forgedIgnored() throws Exception {
        RSAKey attacker = new RSAKeyGenerator(2048).keyID("k1").generate();
        String forged = new SsoEventSigner(attacker.toRSAPrivateKey(), "k1")
                .sign(SsoEventSignature.SESSION_EVENTS_CHANNEL, REVOKED_ALL);

        subscriber(false).handleSessionEvent(forged);

        verify(ssoSessionService, never()).revokeAll(anyLong());
    }

    @Test
    @DisplayName("서명 강제가 켜지면 서명 없는 세션 폐기는 처리하지 않는다")
    void unsignedRejectedWhenRequired() throws Exception {
        subscriber(true).handleSessionEvent(REVOKED_ALL);

        verify(ssoSessionService, never()).revokeAll(anyLong());
    }
}
