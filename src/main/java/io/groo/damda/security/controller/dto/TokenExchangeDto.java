package io.groo.damda.security.controller.dto;

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;

public class TokenExchangeDto {
    /** Authorization Code 교환 요청 — 인가코드 + PKCE code_verifier + redirect_uri. */
    public record CodeRequest(String code, String codeVerifier, String redirectUri) {}
    /** 서비스 안쪽 결과 — 발급한 desk 토큰을 들고 나온다. <b>API 응답으로 그대로 내보내지 않는다</b>({@link LoginResponse}). */
    public record Response(String accessToken, String userId, String userName, String userEmail) {}

    /**
     * {@code POST /auth/exchange-code} 응답 본문 — <b>토큰이 없다</b>(보안 점검 N-4).
     *
     * <p>desk 토큰은 HttpOnly 쿠키({@code desk_access_token})로만 내려간다. 본문에도 실으면 웹 페이지의
     * 스크립트(XSS)가 응답을 읽어 토큰을 가져갈 수 있어 HttpOnly 의 뜻이 사라진다. 웹·앱 모두 본문 토큰을
     * 읽지 않는다 — 웹은 쿠키, 앱은 Dio cookie_jar 가 쿠키를 저장하고 사용자 정보는 {@code /auth/check} 로 받는다
     * (앱은 v1.0.0, 2026-05 부터 본문을 무시한다). 그래서 클라이언트를 가르지 않고 둘 다 뺀다.
     */
    @Schema(name = "TokenExchangeResponse")
    public record LoginResponse(String userId, String userName, String userEmail) {
        public static LoginResponse from(Response r) {
            return new LoginResponse(r.userId(), r.userName(), r.userEmail());
        }
    }
    /** 세션 확인 응답. joinedAt = 가입일시(User.createAt @CreatedDate), 없으면 null. */
    public record CheckResponse(Long rowId, String userId, String userName, String userEmail, String timezone, LocalDateTime joinedAt) {}

    /** 임베드(차트 WebView 등) 단명 컨텍스트용 토큰. expiresIn 은 초 단위. */
    public record EmbedTokenResponse(String token, long expiresIn) {}
}
