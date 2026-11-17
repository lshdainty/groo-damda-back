package io.groo.damda.security.session.store;

import java.time.Duration;

/**
 * 담다에서 로그아웃한 SSO 로그인 목록 — "이 SSO 로그인으로는 담다에 다시 들여보내지 않는다".
 *
 * <p><b>왜 필요한가</b> — 로그아웃은 담다 세션만 끝내고 그루 계정(SSO) 로그인과 다른 서비스는 그대로
 * 둔다(보안 점검 CLI-6, 사용자 결정). 그런데 SSO 로그인이 살아 있으면 SSO 는 비밀번호를 묻지 않고
 * 인가코드를 내준다. 아무것도 기억해 두지 않으면 로그아웃한 직후 로그인 버튼 한 번으로 다시 들어오고,
 * 잠금이 풀린 기기를 쥔 사람도 똑같이 들어온다. 그래서 담다가 <b>자기 로그아웃을 기억</b>했다가, 같은
 * SSO 로그인으로 돌아온 인가코드 교환을 거절한다({@code AUTH_030}). 비밀번호로 다시 로그인하면 SSO 가
 * 새 로그인 번호를 주므로 그때는 통과한다.
 *
 * <p><b>키는 SSO 로그인 단위다</b> — SSO access token 의 {@code sid} 클레임(로그인 한 번 = 값 하나)이고,
 * 담다 access token 에는 {@code sso_sid} 로 실려 다닌다. 사용자 단위로 두면 다른 기기에서 새로 한 로그인까지
 * 막힌다.
 *
 * <p><b>보는 자리는 새 로그인을 만드는 교환 하나뿐이다.</b> 저장해 둔 SSO refresh 로 담다 세션을 조용히
 * 연장하는 경로는 보지 않는다 — 같은 SSO 로그인으로 만든 다른 담다 세션(예: 같은 브라우저로 로그인한 앱)은
 * 로그아웃하지 않았으니 살아 있어야 한다.
 *
 * <p>세션 폐기 표식({@link SessionRevocationStore})과 같은 구조다 — 운영은 Redis, 테스트는 프로세스
 * 메모리({@code app.session-revocation.store}).
 */
public interface SsoLoginLogoutStore {

    /**
     * 기억해 두는 기간.
     *
     * <p>SSO 로그인 하나가 살아 있는 동안은 기억하고 있어야 한다. SSO refresh 는 7일짜리가 쓸 때마다
     * 새로 나오므로 넉넉히 잡는다. 이 기간이 지나도록 살아 있는 SSO 로그인은 다시 통과한다.
     */
    Duration RETENTION = Duration.ofDays(90);

    /**
     * 이 SSO 로그인을 "담다에서 로그아웃함" 으로 기억한다. 값이 없으면(옛 토큰) 아무것도 하지 않는다.
     *
     * <p><b>던지지 않는다</b> — 기록하지 못해도 로그아웃은 끝나야 한다.
     */
    void markLoggedOut(String ssoSid);

    /**
     * 담다에서 로그아웃한 SSO 로그인인가.
     *
     * <p><b>구현은 fail-open 이다</b> — 저장소를 못 읽으면 {@code false}(통과)를 돌려준다.
     * 이유는 {@link RedisSsoLoginLogoutStore} 주석에 적혀 있다.
     */
    boolean isLoggedOut(String ssoSid);
}
