package io.groo.damda.asset.controller;

import io.groo.damda.security.controller.TokenExchangeController;
import io.groo.damda.security.jwt.JwtTokenProvider;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA 2026-09-08 #101 — 이체 일시를 거래와 <b>같은 형식</b>으로 받는다.
 *
 * <p>고치기 전 실측: {@code POST /asset-transfer} 에 {@code "transferDate":"2026-09-08"} 을 보내면
 * 400 이었다. 요청 DTO 가 {@code LocalDateTime} 이라 Jackson 이 날짜만 있는 문자열을 못 읽고 그
 * 자리에서 끊었기 때문이다. 같은 화면의 거래({@code POST /expense})는 같은 형식을 받는다 —
 * {@code String} 으로 받아 {@link io.groo.damda.common.time.WallClockDateTimeParser} 를 태운다.
 *
 * <p>이건 이론상의 차이가 아니다. 웹 이체 폼({@code AssetTransferForm} 의 {@code InputDatePicker})이
 * 실제로 {@code yyyy-MM-dd} 를 보낸다.
 *
 * <p><b>왜 통합인가.</b> 지키려는 것 셋이 슬라이스 밖에 있다 — ① 실제 MVC 매퍼가 본문을 읽는지,
 * ② 파서가 날짜만 온 값을 그 날 00:00 로 접는지, ③ 파서가 던지는 {@code DateTimeParseException} 이
 * core 의 {@code GlobalExceptionHandler} 를 거쳐 400 봉투로 나가는지(advice 순서가 정한다).
 * 마지막 것은 목으로 서비스를 대신하면 아예 안 돈다.
 *
 * <h4>네거티브 컨트롤</h4>
 * {@code AssetApiDto.CreateTransferRequest.transferDate} 를 {@code LocalDateTime} 으로 되돌리면
 * {@code dateOnlyBecomesMidnight}·{@code dateOnlyOnUpdate} 가 400 으로 깨진다. 컨트롤러의
 * {@code parseTransferDate} 만 빼면 컴파일이 깨진다(커맨드는 {@code LocalDateTime} 을 받는다).
 *
 * <p>아래 {@code missingDateIsRejected}·{@code blankDateIsRejected} 는 <b>고친 것이 아니라 안 건드린 것</b>을
 * 지킨다 — 일시를 빼면 종전에도 400 이었다({@code transfer_date} NOT NULL →
 * core 의 {@code GlobalExceptionHandler}). 타입을 {@code String} 으로 바꾸면 Jackson 이 끊던 자리가
 * 사라지므로, 그 뒤에도 500 으로 새지 않는지 여기서 잰다. 처음엔 {@code @NotBlank} 를 달았다가
 * 뺐다 — 달아도 안 달아도 400 이라 <b>제약이 하는 일이 없었다</b>(네거티브 컨트롤로 실측).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AssetTransferDateFormatTest {

    @LocalServerPort int port;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper objectMapper;

    private String token;
    private Long fromAssetRowId;
    private Long toAssetRowId;

    @BeforeEach
    void setUp() {
        String id = "u" + UUID.randomUUID().toString().substring(0, 8);
        User user = tx.execute(s -> userRepository.save(User.createUser(null, id, "테스터", id + "@porest.com")));
        token = jwtTokenProvider.createAccessToken(id, "테스터", id + "@porest.com",
                user.getRowId(), UUID.randomUUID().toString());
        fromAssetRowId = createAsset("주계좌");
        toAssetRowId = createAsset("비상금");
    }

    private record Res(int status, String body) {}

    private Res send(HttpMethod method, String path, String body) {
        RestClient.RequestBodySpec spec = RestClient.create().method(method)
                .uri("http://localhost:" + port + "/api/v1" + path)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, TokenExchangeController.ACCESS_TOKEN_COOKIE + "=" + token)
                .header(HttpHeaders.ACCEPT_LANGUAGE, "ko");
        if (body != null) spec = spec.body(body);
        return spec.exchange((req, res) -> new Res(res.getStatusCode().value(),
                new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)), false);
    }

    private Res post(String path, String body) { return send(HttpMethod.POST, path, body); }
    private Res put(String path, String body) { return send(HttpMethod.PUT, path, body); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(Res res) {
        return objectMapper.readValue(res.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Res res) {
        return (Map<String, Object>) json(res).get("data");
    }

    private Long createAsset(String name) {
        Res res = post("/asset", """
                {"assetName":"%s","assetType":"BANK_ACCOUNT","currency":"KRW","balance":1000000}"""
                .formatted(name + UUID.randomUUID().toString().substring(0, 4)));
        assertThat(res.status()).as("자산 생성 — 응답: %s", res.body()).isEqualTo(200);
        return ((Number) data(res).get("rowId")).longValue();
    }

    private String transferBody(String dateField) {
        return """
                {"fromAssetRowId":%d,"toAssetRowId":%d,"amount":10000,"description":"이체"%s}"""
                .formatted(fromAssetRowId, toAssetRowId, dateField);
    }

    // ────────────────────────────── 고친 것 ──────────────────────────────

    @Test
    @DisplayName("날짜만 보내면 그 날 00:00 로 받는다 (종전 400: Jackson 이 LocalDateTime 을 못 읽었다)")
    void dateOnlyBecomesMidnight() {
        Res res = post("/asset-transfer", transferBody(",\"transferDate\":\"2026-09-08\""));

        assertThat(res.status()).as("날짜만 — 응답: %s", res.body()).isEqualTo(200);
        // 00:00 으로 접히는지까지 본다. 200 만 보면 아무 시각으로 저장돼도 통과한다.
        assertThat((String) data(res).get("transferDate")).startsWith("2026-09-08T00:00");
    }

    @Test
    @DisplayName("수정도 날짜만 받는다 — 생성·수정이 같은 record 를 쓴다")
    void dateOnlyOnUpdate() {
        Res created = post("/asset-transfer", transferBody(",\"transferDate\":\"2026-09-08T13:45:00\""));
        assertThat(created.status()).isEqualTo(200);
        long rowId = ((Number) data(created).get("rowId")).longValue();

        Res res = put("/asset-transfer/" + rowId, transferBody(",\"transferDate\":\"2026-09-09\""));

        assertThat(res.status()).as("수정/날짜만 — 응답: %s", res.body()).isEqualTo(200);
        assertThat((String) data(res).get("transferDate")).startsWith("2026-09-09T00:00");
    }

    @Test
    @DisplayName("거래(POST /expense)와 같은 형식이다 — 두 화면이 같은 값을 받는다")
    void sameFormatAsExpense() {
        // #101 의 요구는 "이체가 날짜를 받는다" 가 아니라 "이체와 거래가 같은 걸 받는다" 다.
        // 거래 쪽이 먼저 깨지면 통일이 깨진 것이므로 여기서 같이 잰다.
        Res expense = post("/expense", """
                {"categoryRowId":null,"expenseType":"EXPENSE","amount":1000,"expenseDate":"2026-09-08"}""");
        // 카테고리 없이는 거래가 안 만들어지므로 상태코드는 안 본다 — 날짜 때문에 끊기지 않았는지만 본다.
        assertThat(expense.body()).as("거래/날짜만 — 응답: %s", expense.body())
                .doesNotContain("올바른 날짜가 아니에요");

        assertThat(post("/asset-transfer", transferBody(",\"transferDate\":\"2026-09-08\"")).status())
                .isEqualTo(200);
    }

    // ───────────────────── 회귀 방지 (되던 것이 계속 된다) ─────────────────────

    @Test
    @DisplayName("시각까지 보내던 앱은 그대로 통과한다 — 앱은 ISO-LOCAL-DATETIME 을 보낸다")
    void fullDateTimeStillWorks() {
        Res res = post("/asset-transfer", transferBody(",\"transferDate\":\"2026-09-08T13:45:00\""));

        assertThat(res.status()).as("날짜+시각 — 응답: %s", res.body()).isEqualTo(200);
        assertThat((String) data(res).get("transferDate")).startsWith("2026-09-08T13:45");
    }

    @Test
    @DisplayName("공백 구분자도 받는다 — 파서가 허용하는 세 형식이 이체에도 그대로 온다")
    void spaceSeparatorIsAccepted() {
        Res res = post("/asset-transfer", transferBody(",\"transferDate\":\"2026-09-08 13:45:00\""));

        assertThat(res.status()).as("공백 구분자 — 응답: %s", res.body()).isEqualTo(200);
        assertThat((String) data(res).get("transferDate")).startsWith("2026-09-08T13:45");
    }

    // ────────────────────────────── 틀린 값 ──────────────────────────────

    @Test
    @DisplayName("없는 날짜(2026-02-30)는 400 \"올바른 날짜가 아니에요\" — 파서 예외가 500 으로 새지 않는다")
    void impossibleDateIsFourHundred() {
        Res res = post("/asset-transfer", transferBody(",\"transferDate\":\"2026-02-30\""));

        assertRejected("transferDate=2026-02-30", res);
        assertThat((String) json(res).get("message")).isEqualTo("올바른 날짜가 아니에요");
    }

    @Test
    @DisplayName("아무 문자열이나 보내도 400 이다 (500 이 아니다)")
    void garbageDateIsFourHundred() {
        assertRejected("transferDate=어제", post("/asset-transfer", transferBody(",\"transferDate\":\"어제\"")));
    }

    @Test
    @DisplayName("일시를 빼면 종전대로 400 이다 (500 으로 새지 않는다)")
    void missingDateIsRejected() {
        assertRejected("transferDate 생략", post("/asset-transfer", transferBody("")));
    }

    @Test
    @DisplayName("빈 문자열도 400 이다 — 파서가 빈 값을 null 로 접어 같은 자리로 간다")
    void blankDateIsRejected() {
        assertRejected("transferDate=\"\"", post("/asset-transfer", transferBody(",\"transferDate\":\"\"")));
    }

    /** 400 이고 · 서버 오류가 아니며 · 문구가 사용자 말투다(QA #72). */
    private void assertRejected(String label, Res res) {
        assertThat(res.status()).as("%s — 응답: %s", label, res.body()).isEqualTo(400);
        Map<String, Object> body = json(res);
        assertThat(body.get("success")).as("%s", label).isEqualTo(false);
        assertThat((String) body.get("code")).as("%s — 서버 오류 코드가 아니어야 한다", label)
                .doesNotStartWith("COMMON_5");
        assertThat((String) body.get("message")).as("%s — 사용자 말투(QA #72)", label)
                .isNotBlank()
                .doesNotContain("습니다", "합니다", "입니다");
    }
}
