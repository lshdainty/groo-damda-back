package io.groo.damda.user.service;

import io.groo.damda.user.type.ReauthPurpose;

/**
 * 재인증 프록시 — 민감 조작 직전의 본인 확인을 SSO 에 대신 물어본다.
 *
 * <p><b>왜 프록시인가.</b> 브라우저·앱에는 <b>SSO 액세스 토큰이 없다</b>. desk 토큰만 들고
 * 있고 SSO 쪽에 있는 것은 무음 재인증용 Refresh 쿠키뿐이라(OAuth 흐름용이지 API 인증용이
 * 아니다) 재인증 API 를 직접 부르지 못한다. 그래서 desk-back 이 서비스 토큰으로 대신
 * 부른다 — 비밀번호 변경({@link UserService#changePassword})·소셜 연동
 * ({@link OAuthLinkService})과 같은 BFF 패턴이다.
 *
 * <p>확인이 끝나면 SSO 가 <b>재인증 티켓</b>(RS256, 10분, 단회)을 준다. 그 티켓을
 * {@code X-Reauth-Token} 으로 들고 와야 해지가 진행된다
 * ({@link ReauthTicketVerifier}).
 */
public interface ReauthProxyService {

    /**
     * 본인 메일로 6자리 코드를 보낸다.
     *
     * <p>비밀번호가 없는 <b>소셜 전용 계정</b>을 위한 경로다. 그 계정은 비밀번호로 확인할
     * 방법이 없으므로 이 길이 막히면 해지를 아예 못 한다.
     */
    void sendEmailCode(String userId);

    /** 코드 확인 → {@code purpose} 용 재인증 티켓. 코드가 틀리거나 시도를 다 쓰면 400. */
    String verifyEmailCode(String userId, String code, ReauthPurpose purpose);

    /** 비밀번호 확인 → {@code purpose} 용 재인증 티켓. 틀리면 400. */
    String verifyPassword(String userId, String password, ReauthPurpose purpose);
}
