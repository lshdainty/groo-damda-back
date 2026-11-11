package io.groo.damda.common.exception;

import io.groo.damda.security.controller.TokenExchangeController;
import io.groo.damda.security.handler.CustomAccessDeniedHandler;
import io.groo.damda.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA 2026-09-03 #74 — 인증 실패 · 없는 경로 · 파라미터 오류가 <b>같은 봉투와 같은 말투</b>로 나가는지,
 * 앱을 통째로 띄워 실제 응답 본문으로 확인한다.
 *
 * <p>단위 테스트로는 아무것도 안 지켜진다. 401 은 {@code @RestControllerAdvice} 가 아니라
 * <b>시큐리티 필터</b>({@code ExceptionTranslationFilter} → {@code AuthenticationEntryPoint})가 쓰고,
 * 404 문구는 <b>메시지 번들 basename 순서</b>가 정하며(core 번들이 앞에 서면 이 레포 문구가 통째로
 * 무시된다), 타입 불일치 문구는 <b>advice 순서</b>가 정한다. 셋 다 진짜 필터 체인 · 진짜 컨텍스트에서만
 * 드러난다.
 *
 * <p>고치기 전 실측(2026-09-04):
 * <pre>
 * GET  /api/v1/expenses (미인증)        401 {"status": 401, "message": "인증이 필요합니다."}
 * GET  /api/v1/nope                     404 {"success":false,"code":"COMMON_404","message":"존재하지 않는 리소스입니다.","data":null}
 * DELETE /api/v1/expense/budget/abc     400 {"success":false,"code":"COMMON_400","message":"'id' 파라미터의 값이 유효하지 않습니다.","data":null}
 * </pre>
 *
 * <p>QA 2026-09-04 #75 — 여기에 <b>내부 파라미터 이름이 응답에 안 나오는지</b>도 함께 건다.
 * 고치기 전 실측(2026-09-04, 이 테스트가 부르는 그 경로들):
 * <pre>
 * DELETE /api/v1/expense/budget/abc          400 "id 값이 올바르지 않아요"        / en "Invalid value for id"
 * GET    /api/v1/expense/budgets?year=abc    400 "year 값이 올바르지 않아요"
 * GET    /api/v1/expenses/summary/daily      400 "필수 요청 파라미터가 빠졌어요: date"
 *                                                / en "Required request parameter is missing: date"
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ErrorEnvelopeTest {

    /** 격식체 종결 — 하나라도 남으면 앱·웹 문구와 말투가 갈린다({@code MessageToneTest} 와 같은 규칙). */
    private static final String[] FORMAL = {"습니다", "합니다", "입니다"};

    private static final List<String> ENVELOPE = List.of("success", "code", "message", "data");

    @LocalServerPort int port;

    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private MessageSource messageSource;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CustomAccessDeniedHandler accessDeniedHandler;

    private record Res(int status, String allow, String body) {}

    private String login() {
        return jwtTokenProvider.createAccessToken(
                "qa-user", "QA", "qa@porest.com", 4242L, UUID.randomUUID().toString());
    }

    private Res call(HttpMethod method, String path, String token, String lang, String body) {
        RestClient.RequestBodySpec spec = RestClient.create().method(method)
                .uri("http://localhost:" + port + path)
                .contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec = spec.header(HttpHeaders.COOKIE, TokenExchangeController.ACCESS_TOKEN_COOKIE + "=" + token);
        }
        if (lang != null) spec = spec.header(HttpHeaders.ACCEPT_LANGUAGE, lang);
        if (body != null) spec = spec.body(body);
        return spec.exchange((req, res) -> new Res(
                res.getStatusCode().value(),
                res.getHeaders().getFirst(HttpHeaders.ALLOW),
                new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)), false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(Res res) {
        return objectMapper.readValue(res.body(), Map.class);
    }

    private String message(Res res) {
        return (String) body(res).get("message");
    }

    /** 본문이 공통 봉투 4개 필드 그대로인지 — 필드가 더 있거나 빠지면 클라이언트 파싱이 갈린다. */
    private void assertEnvelope(Res res, String code, String message) {
        Map<String, Object> json = body(res);
        assertThat(json.keySet())
                .as("공통 봉투 필드: %s", res.body())
                .containsExactlyInAnyOrderElementsOf(ENVELOPE);
        assertThat(json.get("success")).isEqualTo(false);
        assertThat(json.get("code")).isEqualTo(code);
        assertThat(json.get("message")).isEqualTo(message);
        assertThat(json.get("data")).isNull();
        assertThat((String) json.get("message")).doesNotContain(FORMAL);
    }

    @Test
    @DisplayName("미인증 401 — 공통 봉투 + `~어요` (종전: status/message 만 있는 다른 모양)")
    void unauthenticatedUsesTheCommonEnvelope() {
        Res res = call(HttpMethod.GET, "/api/v1/expenses", null, "ko", null);

        assertThat(res.status()).isEqualTo(401);
        assertEnvelope(res, "COMMON_411", "로그인이 필요해요");
        // 종전 봉투의 흔적이 남으면 안 된다 — 클라이언트가 두 모양을 다 알아야 하는 상태가 된다.
        assertThat(body(res)).doesNotContainKey("status");
    }

    @Test
    @DisplayName("401 도 Accept-Language 를 본다 — 시큐리티 필터엔 LocaleContextHolder 가 아직 없다")
    void unauthenticatedRespectsAcceptLanguage() {
        // 종전엔 한국어가 박혀 있어 en 요청도 "인증이 필요합니다." 를 받았다.
        Res res = call(HttpMethod.GET, "/api/v1/expenses", null, "en", null);

        assertThat(res.status()).isEqualTo(401);
        assertEnvelope(res, "COMMON_411", "Sign in to continue");
    }

    @Test
    @DisplayName("없는 경로 404 — `~어요` (종전: 존재하지 않는 리소스입니다.)")
    void unmappedPathSpeaksProductTone() {
        Res res = call(HttpMethod.GET, "/api/v1/nope", login(), "ko", null);

        assertThat(res.status()).isEqualTo(404);
        assertEnvelope(res, "COMMON_404", "요청하신 주소를 찾을 수 없어요");
    }

    @Test
    @DisplayName("메서드 없음 405 — `~어요` + Allow")
    void methodNotAllowedSpeaksProductTone() {
        Res res = call(HttpMethod.POST, "/api/v1/expense/budgets", login(), "ko", "{}");

        assertThat(res.status()).isEqualTo(405);
        assertEnvelope(res, "COMMON_405", "이 주소에서는 쓸 수 없는 요청 방식이에요");
        assertThat(res.allow()).contains("GET");
    }

    @Test
    @DisplayName("파라미터 타입 불일치 400 — 내부 이름(id·year)이 응답에 없다")
    void parameterTypeMismatchHidesTheParameterName() {
        // QA 2026-09-04 #75. 고치기 전 실측: "id 값이 올바르지 않아요" / "year 값이 올바르지 않아요".
        // `id`·`year` 는 코드 이름이라 쓰는 사람에겐 뜻이 없고 API 구조만 드러낸다.
        Res path = call(HttpMethod.DELETE, "/api/v1/expense/budget/abc", login(), "ko", null);
        assertThat(path.status()).isEqualTo(400);
        assertEnvelope(path, "COMMON_400", "요청 값이 올바르지 않아요");
        assertThat(message(path)).doesNotContain("id");

        // 쿼리 파라미터도 같은 자리로 온다.
        Res query = call(HttpMethod.GET, "/api/v1/expense/budgets?year=abc", login(), "ko", null);
        assertThat(query.status()).isEqualTo(400);
        assertEnvelope(query, "COMMON_400", "요청 값이 올바르지 않아요");
        assertThat(message(query)).doesNotContain("year");
    }

    @Test
    @DisplayName("필수 파라미터 누락 400 — 내부 이름(date·endDate)이 응답에 없다")
    void missingParameterHidesTheParameterName() {
        // 고치기 전 실측: "필수 요청 파라미터가 빠졌어요: date" / "… : endDate".
        // core 의 GlobalExceptionHandler 가 답하던 자리다 — 이제 desk advice 가 먼저 잡는다.
        Res missing = call(HttpMethod.GET, "/api/v1/expenses/summary/daily", login(), "ko", null);
        assertThat(missing.status()).isEqualTo(400);
        assertEnvelope(missing, "COMMON_400", "요청에 빠진 값이 있어요");
        assertThat(message(missing)).doesNotContain("date");

        // 두 개 중 하나만 빠져도 남은 하나의 이름이 새면 안 된다.
        Res partial = call(HttpMethod.GET, "/api/v1/expenses/summary/range?startDate=2026-01-01",
                login(), "ko", null);
        assertThat(partial.status()).isEqualTo(400);
        assertEnvelope(partial, "COMMON_400", "요청에 빠진 값이 있어요");
        assertThat(message(partial)).doesNotContain("endDate").doesNotContain("startDate");
    }

    @Test
    @DisplayName("영어 번들에서도 이름이 안 나온다 — 말투와 별개로 이름 노출을 없앤 결정이다")
    void parameterNamesAreHiddenInEnglishToo() {
        // 고치기 전 실측: "Invalid value for id" / "Required request parameter is missing: date".
        Res mismatch = call(HttpMethod.DELETE, "/api/v1/expense/budget/abc", login(), "en", null);
        assertThat(mismatch.status()).isEqualTo(400);
        assertEnvelope(mismatch, "COMMON_400", "That request value is not valid");

        Res missing = call(HttpMethod.GET, "/api/v1/expenses/summary/daily", login(), "en", null);
        assertThat(missing.status()).isEqualTo(400);
        assertEnvelope(missing, "COMMON_400", "A required value is missing from the request");
        assertThat(message(missing)).doesNotContain("date");
    }

    @Test
    @DisplayName("네거티브 컨트롤 — @Valid 필드 문구는 그대로 DTO 것이 나온다")
    void validationMessagesStillComeFromTheDto() {
        // core GlobalExceptionHandler 에 BindException 핸들러를 두면 MethodArgumentNotValidException
        // 까지 그 advice 가 먹어 이 문구가 사라진다(#307 이 맞춘 136개가 통째로). 그래서 여기서 못 박는다.
        Res res = call(HttpMethod.POST, "/api/v1/expense/budget", login(), "ko",
                "{\"categoryRowId\":null,\"budgetAmount\":0,\"budgetYear\":2026,\"budgetMonth\":9}");

        assertThat(res.status()).isEqualTo(400);
        assertEnvelope(res, "COMMON_400", "예산 금액은 0보다 커야 해요");
    }

    @Test
    @DisplayName("필터 단계 403 도 같은 봉투 — 없으면 부트 기본 에러 본문이 나간다")
    void accessDeniedHandlerUsesTheSameEnvelope() throws Exception {
        // 지금 설정(anyRequest().authenticated(), 메서드 시큐리티 없음)에서는 필터 단계 403 이 나지
        // 않는다. 그래서 HTTP 로는 못 부르고 핸들러를 직접 부른다 — 규칙(hasRole 등)이 생기는 날
        // 401 만 고쳐 두고 403 에 구멍을 남기지 않으려고 미리 박아 둔다.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/expenses");
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "ko");
        MockHttpServletResponse response = new MockHttpServletResponse();

        accessDeniedHandler.handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertEnvelope(new Res(403, null, response.getContentAsString()), "COMMON_412", "접근 권한이 없어요");
    }

    @Test
    @DisplayName("번들 우선순위 — 이 레포 문구가 core 번들을 덮는다")
    void deskBundleOverridesCoreBundle() {
        // basename 을 core 먼저로 되돌리면 이 레포의 messages_ko 는 겹치는 키에서 통째로 무시된다.
        // 화면은 그대로인데 파일만 고쳐지는 상태라, 실측 없이는 고쳤다고 착각하기 쉽다.
        assertThat(messageSource.getMessage("error.common.invalid.input", null, Locale.KOREAN))
                .isEqualTo("입력값이 올바르지 않아요");
        assertThat(messageSource.getMessage("error.file.too.large", null, Locale.KOREAN))
                .isEqualTo("파일은 10MB 까지 올릴 수 있어요");
        // core 에만 있는 키는 그대로 core 에서 온다 — 덮어쓰기가 아니라 우선순위다.
        assertThat(messageSource.getMessage("error.common.success", null, Locale.KOREAN))
                .isEqualTo("요청이 성공적으로 처리되었습니다.");
    }

    @Test
    @DisplayName("core 의 공통 에러 문구도 `~어요` — 500 fallback 은 core 키로 나간다")
    void coreCommonMessagesSpeakProductTone() {
        // core advice 가 자기 키로 답하는 자리들. 이 레포 번들에 같은 키가 없으면 격식체가 그대로 나간다.
        for (String key : List.of("error.common.unauthorized", "error.common.forbidden",
                "error.common.404", "error.common.internal.server", "error.common.invalid.input")) {
            assertThat(messageSource.getMessage(key, null, Locale.KOREAN))
                    .as("core 키 %s", key)
                    .doesNotContain(FORMAL);
        }
    }
}
