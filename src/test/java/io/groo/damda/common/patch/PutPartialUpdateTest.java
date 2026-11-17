package io.groo.damda.common.patch;

import io.groo.damda.constellation.domain.Constellation;
import io.groo.damda.constellation.repository.ConstellationRepository;
import io.groo.damda.security.controller.TokenExchangeController;
import io.groo.damda.security.jwt.JwtTokenProvider;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA 2026-09-07 #96 — PUT 의 뜻을 <b>같은 뜻</b>으로 고정한다(감사 2026-09-08 로 캘린더·프리셋까지).
 *
 * <p>사용자 결정: <b>"없으면 유지, 지우려면 명시적 null"</b>. 도메인마다 세 케이스를 같은 자리에 건다.
 * <ol>
 *   <li><b>일부만 PUT</b> → 안 보낸 칸은 그대로 남는다 (종전: null 로 덮여 사라졌다)</li>
 *   <li><b>명시적 {@code null}</b> → 지워진다 (안 보낸 것과 다른 뜻이다)</li>
 *   <li><b>전체 PUT</b> → 종전과 똑같다 ← <b>가장 중요하다.</b> 지금 웹·앱은 전부 전체를 보낸다.
 *       여기서 회귀가 나면 화면이 조용히 깨진다</li>
 * </ol>
 *
 * <p><b>왜 통합인가.</b> 지키려는 것이 슬라이스 밖에 있다 — "키가 없다" 와 {@code null} 의 구분은
 * {@code AbsentAwareOptionalModule} 이 붙은 <b>실제 MVC 매퍼</b>가 만들고, 그 결과가 DB 행까지
 * 가야 "유지" 인지 "덮임" 인지 알 수 있다. 목으로 서비스를 대신하면 둘 다 안 보인다.
 *
 * <p>필수 칸의 명시적 {@code null} 은 <b>400</b> 이다(각 도메인의 {@code required...Null} 테스트).
 * NOT NULL 칸을 "지운다" 는 뜻이 성립하지 않으므로, 안 보낸 것과 같게 조용히 넘기지 않고 거절한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PutPartialUpdateTest {

    @LocalServerPort int port;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired ConstellationRepository constellationRepository;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper objectMapper;

    private String token;

    @BeforeEach
    void setUp() {
        // 별자리 하나는 있어야 한다 — 메모·할 일 등록이 별빛을 적립하면서 "오늘의 목표"를 찾는다.
        tx.execute(s -> constellationRepository.findAllActive().isEmpty()
                ? constellationRepository.save(Constellation.createConstellation(
                        "test-const", "테스트자리", "Testus", null, null, "blue", 5, "[]", 1))
                : null);
        String id = "u" + UUID.randomUUID().toString().substring(0, 8);
        User user = tx.execute(s -> userRepository.save(User.createUser(null, id, "테스터", id + "@porest.com")));
        token = jwtTokenProvider.createAccessToken(id, "테스터", id + "@porest.com",
                user.getRowId(), UUID.randomUUID().toString(), null);
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
    private Res get(String path) { return send(HttpMethod.GET, path, null); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Res res) {
        assertThat(res.status()).as("응답: %s", res.body()).isEqualTo(200);
        return (Map<String, Object>) objectMapper.readValue(res.body(), Map.class).get("data");
    }

    private void assertRejected(String label, Res res) {
        assertThat(res.status()).as("%s — 응답: %s", label, res.body()).isEqualTo(400);
    }

    // ─────────────────────────────── 메모 ───────────────────────────────

    @Nested
    @DisplayName("메모 — content·color·tag")
    class Memo {

        private Long create() {
            return ((Number) data(post("/memo", """
                    {"title":"원제목","content":"원본문","tag":"원태그","color":"#111111"}""")).get("rowId")).longValue();
        }

        private Map<String, Object> read(Long id) {
            return data(get("/memo/" + id));
        }

        @Test
        @DisplayName("① 제목만 보내면 본문·태그·색이 남는다")
        void partialKeepsRest() {
            Long id = create();

            Map<String, Object> after = data(put("/memo/" + id, """
                    {"title":"새제목"}"""));

            assertThat(after.get("title")).isEqualTo("새제목");
            assertThat(after.get("content")).isEqualTo("원본문");
            assertThat(after.get("tag")).isEqualTo("원태그");
            assertThat(after.get("color")).isEqualTo("#111111");
            assertThat(read(id)).containsAllEntriesOf(Map.of("content", "원본문", "tag", "원태그", "color", "#111111"));
        }

        @Test
        @DisplayName("② 명시적 null 은 지운다")
        void explicitNullErases() {
            Long id = create();

            Map<String, Object> after = data(put("/memo/" + id, """
                    {"content":null,"tag":null,"color":null}"""));

            assertThat(after.get("title")).isEqualTo("원제목");
            assertThat(after.get("content")).isNull();
            assertThat(after.get("tag")).isNull();
            assertThat(after.get("color")).isNull();
            assertThat(read(id).get("content")).isNull();
        }

        @Test
        @DisplayName("③ 전체 PUT — 종전과 같다")
        void fullPutUnchanged() {
            Long id = create();

            Map<String, Object> after = data(put("/memo/" + id, """
                    {"title":"새제목","content":"새본문","tag":"새태그","color":"#222222"}"""));

            assertThat(after.get("title")).isEqualTo("새제목");
            assertThat(after.get("content")).isEqualTo("새본문");
            assertThat(after.get("tag")).isEqualTo("새태그");
            assertThat(after.get("color")).isEqualTo("#222222");
        }

        @Test
        @DisplayName("필수 칸(title)의 명시적 null 은 400 — 안 보낸 것과 다르다")
        void requiredTitleNullRejected() {
            Long id = create();
            assertRejected("memo/title=null", put("/memo/" + id, """
                    {"title":null}"""));
            assertThat(read(id).get("title")).isEqualTo("원제목");
        }

        @Test
        @DisplayName("실린 값의 제약은 그대로 — 색 형식이 틀리면 400")
        void constraintsStillApply() {
            Long id = create();
            assertRejected("memo/color=zzz", put("/memo/" + id, """
                    {"color":"zzz"}"""));
        }
    }

    // ─────────────────────────────── 할 일 ───────────────────────────────

    @Nested
    @DisplayName("할 일 — content·dueDate·category")
    class Todo {

        private Long create() {
            return ((Number) data(post("/todo", """
                    {"title":"원제목","content":"원메모","priority":"HIGH","category":"원분류","dueDate":"2026-12-01"}""")).get("rowId")).longValue();
        }

        private Map<String, Object> read(Long id) {
            return data(get("/todo/" + id));
        }

        @Test
        @DisplayName("① 제목만 보내면 메모·분류·기한·중요도가 남는다")
        void partialKeepsRest() {
            Long id = create();

            Map<String, Object> after = data(put("/todo/" + id, """
                    {"title":"새제목"}"""));

            assertThat(after.get("title")).isEqualTo("새제목");
            assertThat(after.get("content")).isEqualTo("원메모");
            assertThat(after.get("category")).isEqualTo("원분류");
            assertThat(after.get("dueDate")).isEqualTo("2026-12-01");
            assertThat(after.get("priority")).isEqualTo("HIGH");
            assertThat(read(id).get("content")).isEqualTo("원메모");
        }

        @Test
        @DisplayName("② 명시적 null 은 지운다")
        void explicitNullErases() {
            Long id = create();

            Map<String, Object> after = data(put("/todo/" + id, """
                    {"content":null,"category":null,"dueDate":null}"""));

            assertThat(after.get("title")).isEqualTo("원제목");
            assertThat(after.get("content")).isNull();
            assertThat(after.get("category")).isNull();
            assertThat(after.get("dueDate")).isNull();
            assertThat(read(id).get("category")).isNull();
        }

        @Test
        @DisplayName("③ 전체 PUT — 종전과 같다")
        void fullPutUnchanged() {
            Long id = create();

            Map<String, Object> after = data(put("/todo/" + id, """
                    {"title":"새제목","content":"새메모","priority":"LOW","category":"새분류","dueDate":"2027-01-15"}"""));

            assertThat(after.get("title")).isEqualTo("새제목");
            assertThat(after.get("content")).isEqualTo("새메모");
            assertThat(after.get("priority")).isEqualTo("LOW");
            assertThat(after.get("category")).isEqualTo("새분류");
            assertThat(after.get("dueDate")).isEqualTo("2027-01-15");
        }

        @Test
        @DisplayName("필수 칸(title)의 명시적 null 은 400")
        void requiredTitleNullRejected() {
            Long id = create();
            assertRejected("todo/title=null", put("/todo/" + id, """
                    {"title":null}"""));
            assertThat(read(id).get("title")).isEqualTo("원제목");
        }
    }

    // ────────────────────────────── 저축 목표 ──────────────────────────────

    @Nested
    @DisplayName("저축목표 — deadlineDate·description·icon·color")
    class SavingGoal {

        private Long create() {
            return ((Number) data(post("/saving-goal", """
                    {"title":"원목표","description":"원설명","targetAmount":1000000,"currency":"KRW",
                     "deadlineDate":"2027-06-30","icon":"piggy-bank","color":"#111111"}""")).get("rowId")).longValue();
        }

        private Map<String, Object> read(Long id) {
            return data(get("/saving-goal/" + id));
        }

        @Test
        @DisplayName("① 이름만 보내면 설명·기한·아이콘·색이 남는다")
        void partialKeepsRest() {
            Long id = create();

            Map<String, Object> after = data(put("/saving-goal/" + id, """
                    {"title":"새목표"}"""));

            assertThat(after.get("title")).isEqualTo("새목표");
            assertThat(after.get("description")).isEqualTo("원설명");
            assertThat(after.get("deadlineDate")).isEqualTo("2027-06-30");
            assertThat(after.get("icon")).isEqualTo("piggy-bank");
            assertThat(after.get("color")).isEqualTo("#111111");
            assertThat(after.get("targetAmount")).isEqualTo(1000000);
            assertThat(read(id).get("description")).isEqualTo("원설명");
        }

        @Test
        @DisplayName("② 명시적 null 은 지운다")
        void explicitNullErases() {
            Long id = create();

            Map<String, Object> after = data(put("/saving-goal/" + id, """
                    {"description":null,"deadlineDate":null,"icon":null,"color":null}"""));

            assertThat(after.get("title")).isEqualTo("원목표");
            assertThat(after.get("description")).isNull();
            assertThat(after.get("deadlineDate")).isNull();
            assertThat(after.get("icon")).isNull();
            assertThat(after.get("color")).isNull();
            assertThat(read(id).get("icon")).isNull();
        }

        @Test
        @DisplayName("③ 전체 PUT — 종전과 같다")
        void fullPutUnchanged() {
            Long id = create();

            Map<String, Object> after = data(put("/saving-goal/" + id, """
                    {"title":"새목표","description":"새설명","targetAmount":2000000,
                     "deadlineDate":"2028-01-01","icon":"target","color":"#222222","linkedAssetRowId":null}"""));

            assertThat(after.get("title")).isEqualTo("새목표");
            assertThat(after.get("description")).isEqualTo("새설명");
            assertThat(after.get("targetAmount")).isEqualTo(2000000);
            assertThat(after.get("deadlineDate")).isEqualTo("2028-01-01");
            assertThat(after.get("icon")).isEqualTo("target");
            assertThat(after.get("color")).isEqualTo("#222222");
        }

        @Test
        @DisplayName("필수 칸(targetAmount)의 명시적 null 은 400")
        void requiredTargetAmountNullRejected() {
            Long id = create();
            assertRejected("saving-goal/targetAmount=null", put("/saving-goal/" + id, """
                    {"targetAmount":null}"""));
            assertThat(read(id).get("targetAmount")).isEqualTo(1000000);
        }
    }

    // ─────────────────────────────── 거래 ───────────────────────────────

    @Nested
    @DisplayName("거래 — merchant·description·assetRowId")
    class Expense {

        private Long categoryRowId() {
            return ((Number) data(post("/expense/category", """
                    {"categoryName":"식비-%s","icon":"utensils","color":"#2c70bf","expenseType":"EXPENSE"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8)))).get("rowId")).longValue();
        }

        private Long assetRowId() {
            return ((Number) data(post("/asset", """
                    {"assetName":"지갑","assetType":"CASH","balance":100000,"currency":"KRW","isIncludedInTotal":"Y"}"""))
                    .get("rowId")).longValue();
        }

        private Long create(Long categoryRowId, Long assetRowId) {
            return ((Number) data(post("/expense", """
                    {"categoryRowId":%d,"assetRowId":%d,"expenseType":"EXPENSE","amount":10000,
                     "description":"원설명","expenseDate":"2026-09-01","merchant":"원거래처","paymentMethod":"CARD"}"""
                    .formatted(categoryRowId, assetRowId))).get("rowId")).longValue();
        }

        /** 단건 조회 API 가 없다 — 목록에서 그 행을 집어 온다(응답이 아니라 저장된 행을 본다). */
        @SuppressWarnings("unchecked")
        private Map<String, Object> read(Long id) {
            List<Map<String, Object>> rows = (List<Map<String, Object>>)
                    data(get("/expenses?startDate=2026-01-01&endDate=2027-12-31")).get("expenses");
            return rows.stream()
                    .filter(row -> ((Number) row.get("rowId")).longValue() == id)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("거래를 목록에서 못 찾았다: " + id));
        }

        @Test
        @DisplayName("① 금액만 보내면 거래처·설명·자산·카테고리가 남는다")
        void partialKeepsRest() {
            Long categoryRowId = categoryRowId();
            Long assetRowId = assetRowId();
            Long id = create(categoryRowId, assetRowId);

            Map<String, Object> after = data(put("/expense/" + id, """
                    {"amount":20000}"""));

            assertThat(after.get("amount")).isEqualTo(20000);
            assertThat(after.get("merchant")).isEqualTo("원거래처");
            assertThat(after.get("description")).isEqualTo("원설명");
            assertThat(((Number) after.get("assetRowId")).longValue()).isEqualTo(assetRowId);
            assertThat(((Number) after.get("categoryRowId")).longValue()).isEqualTo(categoryRowId);
            assertThat(read(id).get("merchant")).isEqualTo("원거래처");
        }

        @Test
        @DisplayName("② 명시적 null 은 지운다")
        void explicitNullErases() {
            Long id = create(categoryRowId(), assetRowId());

            Map<String, Object> after = data(put("/expense/" + id, """
                    {"merchant":null,"description":null,"assetRowId":null}"""));

            assertThat(after.get("merchant")).isNull();
            assertThat(after.get("description")).isNull();
            assertThat(after.get("assetRowId")).isNull();
            assertThat(after.get("amount")).isEqualTo(10000);
            assertThat(read(id).get("merchant")).isNull();
        }

        @Test
        @DisplayName("③ 전체 PUT — 종전과 같다")
        void fullPutUnchanged() {
            Long categoryRowId = categoryRowId();
            Long assetRowId = assetRowId();
            Long id = create(categoryRowId, assetRowId);

            Map<String, Object> after = data(put("/expense/" + id, """
                    {"categoryRowId":%d,"assetRowId":%d,"expenseType":"EXPENSE","amount":30000,
                     "description":"새설명","expenseDate":"2026-09-05","merchant":"새거래처","paymentMethod":"CASH"}"""
                    .formatted(categoryRowId, assetRowId)));

            assertThat(after.get("amount")).isEqualTo(30000);
            assertThat(after.get("description")).isEqualTo("새설명");
            assertThat(after.get("merchant")).isEqualTo("새거래처");
            assertThat(after.get("paymentMethod")).isEqualTo("CASH");
            assertThat((String) after.get("expenseDate")).startsWith("2026-09-05");
            assertThat(((Number) after.get("assetRowId")).longValue()).isEqualTo(assetRowId);
        }

        @Test
        @DisplayName("필수 칸(expenseDate)의 명시적 null 은 400")
        void requiredExpenseDateNullRejected() {
            Long id = create(categoryRowId(), assetRowId());
            assertRejected("expense/expenseDate=null", put("/expense/" + id, """
                    {"expenseDate":null}"""));
            assertThat((String) read(id).get("expenseDate")).startsWith("2026-09-01");
        }
    }

    // ─────────────────────────────── 자산 ───────────────────────────────

    @Nested
    @DisplayName("자산 — institution·memo·color")
    class Asset {

        private Long create() {
            return ((Number) data(post("/asset", """
                    {"assetName":"원이름","assetType":"BANK_ACCOUNT","balance":50000,"currency":"KRW",
                     "color":"#111111","institution":"원은행","memo":"원메모","isIncludedInTotal":"Y",
                     "creditLimit":300000,"paymentDay":15}"""))
                    .get("rowId")).longValue();
        }

        private Map<String, Object> read(Long id) {
            return data(get("/asset/" + id));
        }

        @Test
        @DisplayName("① 이름만 보내면 은행·메모·색·한도·결제일이 남는다")
        void partialKeepsRest() {
            Long id = create();

            Map<String, Object> after = data(put("/asset/" + id, """
                    {"assetName":"새이름"}"""));

            assertThat(after.get("assetName")).isEqualTo("새이름");
            assertThat(after.get("institution")).isEqualTo("원은행");
            assertThat(after.get("memo")).isEqualTo("원메모");
            assertThat(after.get("color")).isEqualTo("#111111");
            assertThat(after.get("creditLimit")).isEqualTo(300000);
            assertThat(after.get("paymentDay")).isEqualTo(15);
            assertThat(after.get("assetType")).isEqualTo("BANK_ACCOUNT");
            assertThat(read(id).get("memo")).isEqualTo("원메모");
        }

        @Test
        @DisplayName("② 명시적 null 은 지운다 — 한도·결제일도 이제 지워진다")
        void explicitNullErases() {
            Long id = create();

            Map<String, Object> after = data(put("/asset/" + id, """
                    {"institution":null,"memo":null,"color":null,"creditLimit":null,"paymentDay":null}"""));

            assertThat(after.get("assetName")).isEqualTo("원이름");
            assertThat(after.get("institution")).isNull();
            assertThat(after.get("memo")).isNull();
            assertThat(after.get("color")).isNull();
            assertThat(after.get("creditLimit")).isNull();
            assertThat(after.get("paymentDay")).isNull();
            assertThat(read(id).get("memo")).isNull();
        }

        @Test
        @DisplayName("③ 전체 PUT — 종전과 같다")
        void fullPutUnchanged() {
            Long id = create();

            Map<String, Object> after = data(put("/asset/" + id, """
                    {"assetName":"새이름","assetType":"BANK_ACCOUNT","balance":70000,"currency":"KRW",
                     "color":"#222222","institution":"새은행","memo":"새메모","isIncludedInTotal":"N",
                     "creditLimit":500000,"paymentDay":25}"""));

            assertThat(after.get("assetName")).isEqualTo("새이름");
            assertThat(after.get("institution")).isEqualTo("새은행");
            assertThat(after.get("memo")).isEqualTo("새메모");
            assertThat(after.get("color")).isEqualTo("#222222");
            assertThat(after.get("isIncludedInTotal")).isEqualTo("N");
            assertThat(after.get("creditLimit")).isEqualTo(500000);
            assertThat(after.get("paymentDay")).isEqualTo(25);
            assertThat(after.get("balance")).isEqualTo(70000);
        }

        /**
         * 금액 숨김은 <b>자산의 속성</b>이라 PUT 의 부분 갱신 규칙을 그대로 받는다.
         *
         * <p>화면 카드 가리기와 별개 축이고, 여기서 잠그는 것은 "키를 안 보내면 유지" 다 —
         * 자산 이름만 고쳤는데 가려 둔 게 풀리면 사용자는 그걸 눈치채지 못한다.
         */
        @Test
        @DisplayName("④ 금액 숨김 — 키가 없으면 유지, 보내면 바뀐다")
        void amountHiddenFollowsPatchRules() {
            Long id = create();
            assertThat(read(id).get("isAmountHidden")).isEqualTo("N");

            // 켠다.
            assertThat(data(put("/asset/" + id, """
                    {"isAmountHidden":"Y"}""")).get("isAmountHidden")).isEqualTo("Y");

            // 이름만 고친다 — 가려 둔 것이 풀리면 안 된다.
            Map<String, Object> after = data(put("/asset/" + id, """
                    {"assetName":"딴이름"}"""));
            assertThat(after.get("assetName")).isEqualTo("딴이름");
            assertThat(after.get("isAmountHidden")).isEqualTo("Y");
            assertThat(read(id).get("isAmountHidden")).isEqualTo("Y");

            // 끈다.
            assertThat(data(put("/asset/" + id, """
                    {"isAmountHidden":"N"}""")).get("isAmountHidden")).isEqualTo("N");
        }

        @Test
        @DisplayName("⑤ 금액 숨김의 명시적 null 은 400 — NOT NULL 칸이라 '지운다' 가 성립하지 않는다")
        void amountHiddenNullRejected() {
            Long id = create();

            assertRejected("asset/isAmountHidden=null", put("/asset/" + id, """
                    {"isAmountHidden":null}"""));
            assertThat(read(id).get("isAmountHidden")).isEqualTo("N");
        }

        @Test
        @DisplayName("필수 칸(assetName)의 명시적 null 은 400")
        void requiredAssetNameNullRejected() {
            Long id = create();
            assertRejected("asset/assetName=null", put("/asset/" + id, """
                    {"assetName":null}"""));
            assertThat(read(id).get("assetName")).isEqualTo("원이름");
        }
    }

    // ────────────────────────────── 캘린더 일정 ──────────────────────────────

    /**
     * 감사 2026-09-08 [S1] — 캘린더를 #96 과 같은 뜻으로 옮긴다.
     *
     * <p>{@code CalendarEvent.updateEvent} 는 {@code description}·{@code color}·{@code label}·
     * {@code location}·{@code rrule} 다섯 칸을 <b>조건 없이 대입</b>했다. 앱은 값이 없으면 키를 빼고
     * 보내고 <b>{@code rrule} 은 저장 경로에 파라미터조차 없다</b> — 웹에서 만든 반복 일정이
     * 앱에서 한 번 저장되는 순간 반복을 잃었고, 앱에는 그것을 되살릴 화면이 없다.
     *
     * <p>되돌려 보는 법(네거티브 컨트롤): {@code CalendarEventServiceImpl.updateEvent} 의
     * {@code command.rrule().orKeep(event.getRrule())} 을 {@code command.rrule().value()} 로
     * 되돌리면 ①이 곧바로 깨진다(다섯 칸 모두 같은 모양이다).
     */
    @Nested
    @DisplayName("캘린더 일정 — description·color·label·location·rrule")
    class CalendarEvent {

        /** 일정이 놓인 달만 본다 — 반복 전개가 같은 rowId 를 여러 번 돌려주지 않게. */
        private static final String RANGE = "?startDate=2026-07-01T00:00:00&endDate=2026-07-31T23:59:59";

        private Long labelRowId(String name) {
            return ((Number) data(post("/calendar/label", """
                    {"labelName":"%s-%s","color":"#111111"}"""
                    .formatted(name, UUID.randomUUID().toString().substring(0, 8)))).get("rowId")).longValue();
        }

        private Long create(Long labelRowId) {
            return ((Number) data(post("/calendar/event", """
                    {"title":"원제목","description":"원설명","eventType":"WORK","color":"#111111",
                     "startDate":"2026-07-03T10:00:00","endDate":"2026-07-03T11:00:00","isAllDay":"N",
                     "labelRowId":%d,"location":"원장소","rrule":"FREQ=YEARLY","reminderMinutes":[10]}"""
                    .formatted(labelRowId))).get("rowId")).longValue();
        }

        /** 단건 조회 API 가 없다 — 목록에서 그 행을 집어 온다(응답이 아니라 저장된 행을 본다). */
        @SuppressWarnings("unchecked")
        private Map<String, Object> read(Long id) {
            List<Map<String, Object>> rows = (List<Map<String, Object>>)
                    data(get("/calendar/events" + RANGE)).get("events");
            return rows.stream()
                    .filter(row -> ((Number) row.get("rowId")).longValue() == id)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("일정을 목록에서 못 찾았다: " + id));
        }

        @Test
        @DisplayName("① 제목만 보내면 설명·색·라벨·장소·반복이 남는다")
        void partialKeepsRest() {
            Long labelRowId = labelRowId("원라벨");
            Long id = create(labelRowId);

            // 시작·종료는 @NotNull 이라 늘 실린다 — 앱·웹도 그렇게 보낸다.
            Map<String, Object> after = data(put("/calendar/event/" + id, """
                    {"title":"새제목","startDate":"2026-07-03T10:00:00","endDate":"2026-07-03T11:00:00"}"""));

            assertThat(after.get("title")).isEqualTo("새제목");
            assertThat(after.get("description")).isEqualTo("원설명");
            assertThat(after.get("color")).isEqualTo("#111111");
            assertThat(((Number) after.get("labelRowId")).longValue()).isEqualTo(labelRowId);
            assertThat(after.get("location")).isEqualTo("원장소");
            assertThat(after.get("rrule")).isEqualTo("FREQ=YEARLY");
            // 안 보낸 종류·종일 여부는 종전부터 유지였다(도메인 null 가드) — 그대로인지 함께 본다.
            assertThat(after.get("eventType")).isEqualTo("WORK");
            assertThat(after.get("isAllDay")).isEqualTo("N");
            assertThat(read(id)).containsAllEntriesOf(
                    Map.of("description", "원설명", "location", "원장소", "rrule", "FREQ=YEARLY"));
        }

        @Test
        @DisplayName("② 명시적 null 은 지운다")
        void explicitNullErases() {
            Long id = create(labelRowId("원라벨"));

            Map<String, Object> after = data(put("/calendar/event/" + id, """
                    {"description":null,"color":null,"labelRowId":null,"location":null,"rrule":null,
                     "startDate":"2026-07-03T10:00:00","endDate":"2026-07-03T11:00:00"}"""));

            assertThat(after.get("title")).isEqualTo("원제목");
            assertThat(after.get("description")).isNull();
            assertThat(after.get("color")).isNull();
            assertThat(after.get("labelRowId")).isNull();
            assertThat(after.get("location")).isNull();
            assertThat(after.get("rrule")).isNull();
            assertThat(read(id).get("location")).isNull();
        }

        @Test
        @DisplayName("③ 전체 PUT — 종전과 같다")
        void fullPutUnchanged() {
            Long newLabelRowId = labelRowId("새라벨");
            Long id = create(labelRowId("원라벨"));

            Map<String, Object> after = data(put("/calendar/event/" + id, """
                    {"title":"새제목","description":"새설명","eventType":"PERSONAL","color":"#222222",
                     "startDate":"2026-07-04T09:00:00","endDate":"2026-07-04T10:00:00","isAllDay":"Y",
                     "labelRowId":%d,"location":"새장소","rrule":"FREQ=MONTHLY","reminderMinutes":[30]}"""
                    .formatted(newLabelRowId)));

            assertThat(after.get("title")).isEqualTo("새제목");
            assertThat(after.get("description")).isEqualTo("새설명");
            assertThat(after.get("eventType")).isEqualTo("PERSONAL");
            assertThat(after.get("color")).isEqualTo("#222222");
            assertThat(after.get("isAllDay")).isEqualTo("Y");
            assertThat(((Number) after.get("labelRowId")).longValue()).isEqualTo(newLabelRowId);
            assertThat(after.get("location")).isEqualTo("새장소");
            assertThat(after.get("rrule")).isEqualTo("FREQ=MONTHLY");
            assertThat((String) after.get("startDate")).startsWith("2026-07-04T09:00");
            assertThat(read(id).get("description")).isEqualTo("새설명");
        }

        /**
         * 알림은 <b>목록을 통째로 교체하는 칸</b>이라 이번에도 그대로 뒀다 —
         * 종전부터 "{@code null}=미변경, 리스트=교체" 였고 #96 도 거래 {@code splits}·
         * 자산 {@code holdings}·할 일 {@code tagIds} 를 같은 이유로 건드리지 않았다.
         */
        @Test
        @DisplayName("알림은 종전 계약 그대로 — 안 보내면 유지, 리스트를 보내면 교체")
        @SuppressWarnings("unchecked")
        void reminderContractUnchanged() {
            Long id = create(labelRowId("원라벨"));

            Map<String, Object> kept = data(put("/calendar/event/" + id, """
                    {"title":"새제목","startDate":"2026-07-03T10:00:00","endDate":"2026-07-03T11:00:00"}"""));
            assertThat((List<Map<String, Object>>) kept.get("reminders"))
                    .extracting(r -> r.get("minutesBefore")).containsExactly(10);

            Map<String, Object> replaced = data(put("/calendar/event/" + id, """
                    {"title":"새제목","startDate":"2026-07-03T10:00:00","endDate":"2026-07-03T11:00:00",
                     "reminderMinutes":[30]}"""));
            assertThat((List<Map<String, Object>>) replaced.get("reminders"))
                    .extracting(r -> r.get("minutesBefore")).containsExactly(30);
        }

        @Test
        @DisplayName("실린 값의 제약은 그대로 — 색 형식이 틀리면 400")
        void constraintsStillApply() {
            Long id = create(labelRowId("원라벨"));
            assertRejected("calendar/color=zzz", put("/calendar/event/" + id, """
                    {"color":"zzz","startDate":"2026-07-03T10:00:00","endDate":"2026-07-03T11:00:00"}"""));
            assertThat(read(id).get("color")).isEqualTo("#111111");
        }
    }

    // ─────────────────────────────── 프리셋 ───────────────────────────────

    /**
     * 감사 2026-09-08 [S2] — 프리셋 메모가 편집 한 번에 사라지던 자리.
     *
     * <p>{@code description} 은 <b>웹·앱 어느 편집 화면도 싣지 않는데</b> 등록은 받고 웹 상세는
     * 그 값을 그린다 — 저장된 메모가 있고, 보이고, 편집 한 번에 사라지고, 되살릴 입력칸이
     * 어디에도 없었다. {@code merchant}·{@code paymentMethod}·{@code assetRowId} 도 같은 모양이라
     * {@code ExpenseApiDto.UpdateRequest} 와 같은 뜻으로 통째로 맞췄다.
     *
     * <p>되돌려 보는 법(네거티브 컨트롤): {@code ExpenseTemplateServiceImpl.updateTemplate} 의
     * {@code command.description().orKeep(template.getDescription())} 을
     * {@code command.description().value()} 로 되돌리면 ①·{@code clientPayloadKeepsDescription}
     * 이 곧바로 깨진다.
     */
    @Nested
    @DisplayName("프리셋 — description·merchant·paymentMethod·assetRowId")
    class Preset {

        private Long categoryRowId() {
            return ((Number) data(post("/expense/category", """
                    {"categoryName":"식비-%s","icon":"utensils","color":"#2c70bf","expenseType":"EXPENSE"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8)))).get("rowId")).longValue();
        }

        private Long assetRowId() {
            return ((Number) data(post("/asset", """
                    {"assetName":"지갑","assetType":"CASH","balance":100000,"currency":"KRW","isIncludedInTotal":"Y"}"""))
                    .get("rowId")).longValue();
        }

        private Long create(Long categoryRowId, Long assetRowId) {
            return ((Number) data(post("/expense-template", """
                    {"templateName":"원프리셋-%s","categoryRowId":%d,"assetRowId":%d,"expenseType":"EXPENSE",
                     "amount":10000,"description":"원메모","merchant":"원거래처","paymentMethod":"CARD",
                     "lockAmount":"Y"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8), categoryRowId, assetRowId)))
                    .get("rowId")).longValue();
        }

        /** 단건 조회 API 가 없다 — 목록에서 그 행을 집어 온다(응답이 아니라 저장된 행을 본다). */
        @SuppressWarnings("unchecked")
        private Map<String, Object> read(Long id) {
            List<Map<String, Object>> rows = (List<Map<String, Object>>)
                    data(get("/expense-templates")).get("templates");
            return rows.stream()
                    .filter(row -> ((Number) row.get("rowId")).longValue() == id)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("프리셋을 목록에서 못 찾았다: " + id));
        }

        @Test
        @DisplayName("① 이름만 보내면 메모·거래처·결제수단·자산·카테고리·금액이 남는다")
        void partialKeepsRest() {
            Long categoryRowId = categoryRowId();
            Long assetRowId = assetRowId();
            Long id = create(categoryRowId, assetRowId);

            Map<String, Object> after = data(put("/expense-template/" + id, """
                    {"templateName":"새프리셋-%s"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8))));

            assertThat(after.get("description")).isEqualTo("원메모");
            assertThat(after.get("merchant")).isEqualTo("원거래처");
            assertThat(after.get("paymentMethod")).isEqualTo("CARD");
            assertThat(((Number) after.get("categoryRowId")).longValue()).isEqualTo(categoryRowId);
            assertThat(((Number) after.get("assetRowId")).longValue()).isEqualTo(assetRowId);
            assertThat(after.get("amount")).isEqualTo(10000);
            assertThat(after.get("lockAmount")).isEqualTo("Y");
            assertThat(after.get("expenseType")).isEqualTo("EXPENSE");
            assertThat(read(id).get("description")).isEqualTo("원메모");
        }

        @Test
        @DisplayName("② 명시적 null 은 지운다")
        void explicitNullErases() {
            Long id = create(categoryRowId(), assetRowId());

            Map<String, Object> after = data(put("/expense-template/" + id, """
                    {"description":null,"merchant":null,"paymentMethod":null,"assetRowId":null}"""));

            assertThat(after.get("description")).isNull();
            assertThat(after.get("merchant")).isNull();
            assertThat(after.get("paymentMethod")).isNull();
            assertThat(after.get("assetRowId")).isNull();
            assertThat(after.get("amount")).isEqualTo(10000);
            assertThat(read(id).get("merchant")).isNull();
        }

        @Test
        @DisplayName("③ 전체 PUT — 종전과 같다")
        void fullPutUnchanged() {
            Long categoryRowId = categoryRowId();
            Long newCategoryRowId = categoryRowId();
            Long assetRowId = assetRowId();
            Long id = create(categoryRowId, assetRowId);

            Map<String, Object> after = data(put("/expense-template/" + id, """
                    {"templateName":"새프리셋-%s","categoryRowId":%d,"assetRowId":%d,"expenseType":"EXPENSE",
                     "amount":20000,"description":"새메모","merchant":"새거래처","paymentMethod":"CASH",
                     "lockAmount":"Y"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8), newCategoryRowId, assetRowId)));

            assertThat(((Number) after.get("categoryRowId")).longValue()).isEqualTo(newCategoryRowId);
            assertThat(((Number) after.get("assetRowId")).longValue()).isEqualTo(assetRowId);
            assertThat(after.get("amount")).isEqualTo(20000);
            assertThat(after.get("description")).isEqualTo("새메모");
            assertThat(after.get("merchant")).isEqualTo("새거래처");
            assertThat(after.get("paymentMethod")).isEqualTo("CASH");
            assertThat(after.get("lockAmount")).isEqualTo("Y");
            assertThat(read(id).get("description")).isEqualTo("새메모");
        }

        /**
         * 웹·앱이 <b>지금 그대로 보내는 본문</b>이다 — {@code description} 키가 없다.
         * 이 한 번의 저장이 메모를 지웠고, 다시 적어 넣을 입력칸이 어디에도 없었다.
         */
        @Test
        @DisplayName("지금 화면이 보내는 본문(메모 키 없음)으로 저장해도 메모가 남는다")
        void clientPayloadKeepsDescription() {
            Long categoryRowId = categoryRowId();
            Long assetRowId = assetRowId();
            Long id = create(categoryRowId, assetRowId);

            Map<String, Object> after = data(put("/expense-template/" + id, """
                    {"templateName":"새프리셋-%s","categoryRowId":%d,"assetRowId":%d,"expenseType":"EXPENSE",
                     "amount":20000,"merchant":"새거래처","paymentMethod":"CASH","lockAmount":"Y"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8), categoryRowId, assetRowId)));

            assertThat(after.get("description")).isEqualTo("원메모");
            assertThat(read(id).get("description")).isEqualTo("원메모");
        }

        /**
         * 고정 금액과 금액은 한 쌍이다 — 한쪽만 실린 요청이 나머지 한쪽을 지금 값으로 못 읽으면
         * 고정을 켜 둔 프리셋의 금액이 조용히 사라진다({@code resolveAmount} 는 고정이 아니면 금액을 버린다).
         */
        @Test
        @DisplayName("거래처만 고쳐도 고정 금액이 살아남는다")
        void lockedAmountSurvivesPartialUpdate() {
            Long id = create(categoryRowId(), assetRowId());

            Map<String, Object> after = data(put("/expense-template/" + id, """
                    {"merchant":"새거래처"}"""));

            assertThat(after.get("lockAmount")).isEqualTo("Y");
            assertThat(after.get("amount")).isEqualTo(10000);
            assertThat(read(id).get("amount")).isEqualTo(10000);
        }

        @Test
        @DisplayName("필수 칸(templateName)의 명시적 null 은 400")
        void requiredTemplateNameNullRejected() {
            Long id = create(categoryRowId(), assetRowId());
            String before = (String) read(id).get("templateName");

            assertRejected("expense-template/templateName=null", put("/expense-template/" + id, """
                    {"templateName":null}"""));
            assertThat(read(id).get("templateName")).isEqualTo(before);
        }
    }
}
