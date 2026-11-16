package io.groo.damda.security.session.service;

import io.groo.damda.common.crypto.AesGcmCipher;
import io.groo.damda.security.client.SsoOAuth2Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * B-6 후속 — 로그아웃의 SSO 계열 폐기는 요청 스레드에서 넘기기만 하고 바로 돌아온다(커넥션을 쥐고 SSO 를 기다리지 않는다).
 * 실패 · 대기열 넘침은 로그만 남긴다(로그아웃은 그대로 끝난다).
 */
@DisplayName("SSO 로그인 계열 폐기 — 요청 스레드 밖에서")
class SsoLoginRevokerTest {

    private final SsoOAuth2Client client = mock(SsoOAuth2Client.class);
    private final AesGcmCipher cipher = mock(AesGcmCipher.class);

    @Test
    @DisplayName("넘기기만 하고 바로 돌아온다 — 실제 호출은 실행기가 돌릴 때")
    void handsOffWithoutCalling() {
        List<Runnable> queued = new ArrayList<>();
        SsoLoginRevoker revoker = new SsoLoginRevoker(client, cipher, queued::add);
        given(cipher.decrypt("enc")).willReturn("raw-refresh");

        revoker.revokeLater("sid-1", "enc");
        verify(client, never()).revokeRefreshToken(anyString());

        queued.forEach(Runnable::run);
        verify(client).revokeRefreshToken("raw-refresh");
    }

    @Test
    @DisplayName("대기열이 가득 차 넘기지 못해도 던지지 않는다(로그만)")
    void rejectedIsSwallowed() {
        SsoLoginRevoker revoker = new SsoLoginRevoker(client, cipher, task -> {
            throw new RejectedExecutionException("full");
        });
        assertThatCode(() -> revoker.revokeLater("sid-1", "enc")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SSO 가 실패 · 오류여도 던지지 않는다(로그만)")
    void failuresAreLogged() {
        SsoLoginRevoker revoker = new SsoLoginRevoker(client, cipher, Runnable::run);
        given(cipher.decrypt("enc")).willReturn("raw");
        given(client.revokeRefreshToken("raw")).willReturn(false);
        assertThatCode(() -> revoker.revokeLater("sid-1", "enc")).doesNotThrowAnyException();

        given(cipher.decrypt("bad")).willThrow(new IllegalStateException("decrypt"));
        assertThatCode(() -> revoker.revokeLater("sid-2", "bad")).doesNotThrowAnyException();
    }
}
