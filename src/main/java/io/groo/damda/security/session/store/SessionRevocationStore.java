package io.groo.damda.security.session.store;

/**
 * 폐기된 세션 표식 — "이 세션의 access token 은 더 이상 통하지 않는다".
 *
 * <p><b>왜 필요한가</b> — desk access token 은 무상태 JWT 라 로그아웃해도 서명과 exp 만으로
 * 계속 통과한다. 세션 행({@code user_sso_session})을 지우는 것만으로는 <b>이미 발급된 토큰</b>에
 * 아무 영향이 없어서, 복사된 쿠키가 만료까지 살아 있었다. 게다가
 * {@code JwtAuthenticationFilter} 의 갱신 경로가 세션을 확인하지 않고 claims 만으로 재서명하므로,
 * 그 쿠키를 만료 전에 한 번이라도 쓰면 <b>무기한</b> 연장됐다. 여기 표식을 남겨 필터가 막는다.
 *
 * <p><b>키는 세션 단위(jti)다.</b> 사용자 단위로 두면 "이 기기만 로그아웃" 을 표현할 수 없다.
 * desk access token 의 jti 는 곧 {@code user_sso_session.session_id} 다.
 *
 * <p><b>왜 인터페이스로 빼는가</b> — 운영은 인스턴스가 여럿이라 표식이 공유돼야 한다(Redis).
 * 반면 테스트는 Redis 를 띄우지 않으므로 프로세스 메모리 구현으로 갈아끼운다
 * ({@code app.session-revocation.store}). {@code BrokerTokenStore} 와 같은 구조다.
 */
public interface SessionRevocationStore {

    /**
     * 이 세션을 폐기됐다고 표시한다.
     *
     * <p>{@code ttlSeconds} 는 <b>access token 수명</b>과 같게 준다. 그 시각을 넘긴 토큰은
     * 어차피 서명 검증 단계에서 만료로 떨어지므로 표식을 더 들고 있을 이유가 없다.
     * 0 이하면 저장하지 않는다.
     */
    void revoke(String sessionId, long ttlSeconds);

    /**
     * 폐기된 세션인가.
     *
     * <p><b>구현은 fail-open 이다</b> — 저장소를 못 읽으면 {@code false}(통과)를 돌려준다.
     * 이유는 각 구현의 주석에 적혀 있다.
     */
    boolean isRevoked(String sessionId);

    /**
     * 이 SSO 로그인(SSO access 토큰의 {@code sid})에서 나온 세션을 전부 폐기됐다고 표시한다 — SSO 가 그 로그인을
     * 끝냈을 때다(그루 계정 로그아웃 · 기기 끊기 · 관리자 세션 종료, 보안 점검 CLI-6).
     *
     * <p>세션 단위 표식({@link #revoke})과 키가 다르다. 세션 행에는 SSO 로그인 번호가 없어 "그 로그인의 세션" 을
     * 행으로 찾을 수 없다 — 대신 desk access token 이 싣고 다니는 {@code sso_sid} 를 필터가 이 표식과 대조한다.
     * 0 이하의 수명이면 저장하지 않는다.
     */
    void revokeSsoLogin(String ssoSid, long ttlSeconds);

    /** SSO 가 끊은 로그인인가. {@link #isRevoked} 와 같이 구현은 fail-open 이다. */
    boolean isSsoLoginRevoked(String ssoSid);
}
