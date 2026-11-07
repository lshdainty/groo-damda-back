package io.groo.damda.securities.client;

import io.groo.damda.common.crypto.AesGcmCipher;
import io.groo.damda.securities.client.dto.OAuth2TokenResponse;
import io.groo.damda.securities.repository.UserSecuritiesCredentialRepository;
import io.groo.damda.securities.type.SecuritiesBroker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 나무증권(NH PLUG) 인증.
 *
 * <p>토스와 두 군데가 다르다.
 *
 * <ol>
 *   <li><b>발급</b> — 폼 바디가 아니라 <b>쿼리 파라미터</b>로 보내고, 필드명이
 *       {@code appkey}/{@code appsecretkey} 이며 {@code scope=oob} 가 필요하다.</li>
 *   <li><b>헤더</b> — 2026-10-15 문서 개정으로 {@code Bearer} 하나만 싣는다
 *       ({@code x-client-id}/{@code x-client-secret} 불필요). dev 실서버 확인됨(2026-11-06 사용자 확인) —
 *       401 이 나면 이 변경을 되돌린다.</li>
 * </ol>
 *
 * <p>토큰 수명은 24시간이다. <b>재발급을 남발하면 사용자에게 보안 알림이 쌓이므로</b>
 * 캐시가 필수다 — 부모가 {@code BrokerTokenStore}(운영 기본값 Redis)로 처리한다.
 *
 * <p>쿼리 값은 URI 변수로 바인딩해 RestTemplate 이 정확히 한 번만 인코딩하게 한다.
 * 키에 {@code +}·{@code &} 가 섞여도 안전하다.
 *
 * <p><b>조회용 템플릿을 쓰지 않는다.</b> 발급은 스펙상 모의투자 미제공이라 환경과 무관하게
 * 항상 운영 도메인({@code namuAuthRestTemplate})으로 나가야 한다 — 조회용을 쓰면 모의투자
 * 환경에서 인증부터 죽는다. 발급받은 토큰 자체는 양쪽 환경에 그대로 쓴다.
 */
@Component
public class NamuTokenManager extends AbstractBrokerTokenManager {

    private static final String TOKEN_PATH =
        "/oauth2/token?grant_type=client_credentials&scope=oob&appkey={appkey}&appsecretkey={appsecretkey}";

    private final RestTemplate namuAuthRestTemplate;

    public NamuTokenManager(UserSecuritiesCredentialRepository credentialRepository,
                            AesGcmCipher cipher,
                            BrokerTokenStore tokenStore,
                            @Qualifier("namuAuthRestTemplate") RestTemplate namuAuthRestTemplate) {
        super(credentialRepository, cipher, tokenStore);
        this.namuAuthRestTemplate = namuAuthRestTemplate;
    }

    @Override
    public SecuritiesBroker broker() {
        return SecuritiesBroker.NAMU;
    }

    @Override
    protected BrokerToken issueToken(String apiKey, String apiSecret) {
        OAuth2TokenResponse res = namuAuthRestTemplate.postForObject(
            TOKEN_PATH, null, OAuth2TokenResponse.class,
            Map.of("appkey", apiKey, "appsecretkey", apiSecret));
        return res == null ? null : new BrokerToken(res.accessToken(), res.expiresIn());
    }
}
