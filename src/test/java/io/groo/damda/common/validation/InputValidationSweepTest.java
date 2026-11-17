package io.groo.damda.common.validation;

import io.groo.damda.expense.domain.ExpenseCategory;
import io.groo.damda.expense.repository.ExpenseCategoryRepository;
import io.groo.damda.expense.type.ExpenseType;
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
 * QA 2026-09-07 #85 · #86 · #91 — 요청값 검증을 <b>앱을 통째로 띄워</b> 실제 응답으로 고정한다.
 *
 * <p><b>왜 슬라이스가 아니라 통합인가.</b> 여기서 지키려는 것 셋이 전부 슬라이스 밖에 있다:
 * ① {@code @Valid} 가 <b>컨트롤러에 붙어 있는지</b>(DTO 제약만 달고 컨트롤러를 안 고치면
 * 제약이 통째로 죽는데 컴파일도 단위 테스트도 통과한다 — 지난 라운드가 그렇게 새어 나갔다),
 * ② 서비스 진입 전 null 검사가 <b>QueryDSL 조회보다 먼저</b> 도는지,
 * ③ 그 결과가 {@code 400} 봉투로 나가는지(advice 순서가 정한다).
 *
 * <h4>고치기 전 실측 — 500 여덟 자리(#85)</h4>
 * 원인이 두 갈래다. <b>하나로 뭉뚱그리면 한쪽만 고치고 넘어간다.</b>
 * <ul>
 *   <li><b>QueryDSL {@code eq(null)}</b> — 카테고리({@code expenseType}) · 거래({@code categoryRowId}) ·
 *       예산({@code budgetYear}/{@code budgetMonth}) · 이체({@code fromAssetRowId}/{@code toAssetRowId}).
 *       중복·기존행 조회가 그 값으로 걸러 도는데 QueryDSL 은 {@code eq(null)} 을
 *       {@code IllegalArgumentException} 으로 거절하고, {@code @Repository} 프록시가 그것을
 *       {@code InvalidDataAccessApiUsageException} 으로 번역해 매핑이 없는 채로 500 이 됐다.</li>
 *   <li><b>NPE</b> — 반복거래({@code frequency}/{@code startDate}). 저장 전에 다음 실행일을 계산하는데
 *       그 계산이 {@code startDate.isBefore(오늘)} 과 {@code switch (frequency)} 로 시작한다.</li>
 * </ul>
 *
 * <p><b>{@code POST /expense/category} 의 {@code expenseType} 이 지난 보고서와 다른 이유</b> —
 * 지난 라운드 보고는 "400 + 라이브러리 문구" 였고 dev 는 500 이었다. 둘 다 맞다. 값을
 * <b>틀리게</b> 보내면({@code "INVALID"}) Jackson 이 enum 역직렬화에서 죽어
 * {@code HttpMessageNotReadableException} → 400 이고, 값을 <b>빼면</b> null 이 그대로 흘러
 * 위의 {@code eq(null)} 로 간다. 지난 보고가 틀린 값을 쟀고 QA 는 빠진 값을 쟀다.
 * 아래 {@code wrongEnumValueWasAlreadyFourHundred} 가 그 차이를 그대로 박아 둔다.
 *
 * <h4>네거티브 컨트롤</h4>
 * 각 테스트가 되돌렸을 때 실제로 깨지는지는 PR 본문에 실측으로 적었다. 여기서는 그 대칭도 같이 건다 —
 * {@code StillAccepted} 가 <b>멀쩡한 요청이 새 제약에 걸리지 않는지</b>를 지킨다. 제약을 새로 거는
 * 변경은 통과 테스트만으로는 과잉 여부를 알 수 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InputValidationSweepTest {

    @LocalServerPort int port;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired ExpenseCategoryRepository expenseCategoryRepository;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper objectMapper;

    private String token;
    private Long categoryRowId;

    @BeforeEach
    void setUp() {
        String id = "u" + UUID.randomUUID().toString().substring(0, 8);
        User user = tx.execute(s -> userRepository.save(User.createUser(null, id, "테스터", id + "@porest.com")));
        ExpenseCategory category = tx.execute(s -> expenseCategoryRepository.save(
                ExpenseCategory.createCategory(user, "식비" + id, "utensils", "#2c70bf", ExpenseType.EXPENSE, null)));
        categoryRowId = category.getRowId();
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

    private Res post(String path, String body) {
        return send(HttpMethod.POST, path, body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(Res res) {
        return objectMapper.readValue(res.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Res res) {
        return (Map<String, Object>) json(res).get("data");
    }

    /**
     * 400 이고 · 서버 오류가 아니며 · 문구가 사용자 말투다.
     *
     * <p>{@code 500 이 아니다} 를 따로 거는 이유: 상태코드만 보면 나중에 누가 다른 예외로 바꿔도
     * 400 이면 통과한다. 이 항목의 요구는 "400 이다" 가 아니라 <b>"500 이 아니다"</b> 다.
     */
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

    private void assertAccepted(String label, Res res) {
        assertThat(res.status()).as("%s — 응답: %s", label, res.body()).isEqualTo(200);
    }

    // ────────────────────────────── #85 ──────────────────────────────

    @Nested
    @DisplayName("#85 필수값을 빼면 500 이 아니라 400 이다")
    class MissingRequiredValue {

        @Test
        @DisplayName("카테고리 — expenseType 없음 (종전 500: 중복이름 조회의 eq(null))")
        void categoryWithoutExpenseType() {
            assertRejected("category/expenseType", post("/expense/category",
                    """
                    {"categoryName":"짜장","icon":"tag","color":"#2c70bf"}"""));
        }

        @Test
        @DisplayName("거래 — categoryRowId 없음 (종전 500: 카테고리 조회의 eq(null))")
        void expenseWithoutCategory() {
            assertRejected("expense/categoryRowId", post("/expense",
                    """
                    {"expenseType":"EXPENSE","amount":1000,"expenseDate":"2026-09-07"}"""));
        }

        @Test
        @DisplayName("예산 — budgetYear 없음 (종전 500: 기존행 조회의 eq(null))")
        void budgetWithoutYear() {
            assertRejected("budget/budgetYear", post("/expense/budget",
                    """
                    {"budgetAmount":1000,"budgetMonth":9}"""));
        }

        @Test
        @DisplayName("예산 — budgetMonth 없음 (종전 500: 기존행 조회의 eq(null))")
        void budgetWithoutMonth() {
            assertRejected("budget/budgetMonth", post("/expense/budget",
                    """
                    {"budgetAmount":1000,"budgetYear":2026}"""));
        }

        @Test
        @DisplayName("반복거래 — frequency 없음 (종전 500: 다음 실행일 계산의 switch NPE)")
        void recurringWithoutFrequency() {
            assertRejected("recurring/frequency", post("/recurring-transaction",
                    """
                    {"categoryRowId":%d,"expenseType":"EXPENSE","amount":1000,"startDate":"2026-09-07"}"""
                            .formatted(categoryRowId)));
        }

        @Test
        @DisplayName("반복거래 — startDate 없음 (종전 500: 다음 실행일 계산의 isBefore NPE)")
        void recurringWithoutStartDate() {
            assertRejected("recurring/startDate", post("/recurring-transaction",
                    """
                    {"categoryRowId":%d,"expenseType":"EXPENSE","amount":1000,"frequency":"MONTHLY"}"""
                            .formatted(categoryRowId)));
        }

        @Test
        @DisplayName("이체 — fromAssetRowId 없음 (종전 500: 자산 조회의 eq(null))")
        void transferWithoutFromAsset() {
            assertRejected("transfer/fromAssetRowId", post("/asset-transfer",
                    """
                    {"toAssetRowId":1,"amount":1000,"transferDate":"2026-09-07T00:00:00"}"""));
        }

        @Test
        @DisplayName("이체 — toAssetRowId 없음 (종전 500: 자산 조회의 eq(null))")
        void transferWithoutToAsset() {
            assertRejected("transfer/toAssetRowId", post("/asset-transfer",
                    """
                    {"fromAssetRowId":1,"amount":1000,"transferDate":"2026-09-07T00:00:00"}"""));
        }

        @Test
        @DisplayName("빠진 값과 틀린 값은 원인이 다르다 — 틀린 enum 은 종전에도 400 이었다")
        void wrongEnumValueWasAlreadyFourHundred() {
            // 지난 라운드 보고("400 + 라이브러리 문구")가 잰 자리. Jackson 이 본문을 못 읽어 400 이다 —
            // 값이 아예 없을 때(위 categoryWithoutExpenseType)와 다른 경로다.
            Res res = post("/expense/category", """
                    {"categoryName":"짜장","icon":"tag","color":"#2c70bf","expenseType":"NOT_A_TYPE"}""");
            assertRejected("category/expenseType=NOT_A_TYPE", res);
        }
    }

    // ────────────────────────────── #86 ──────────────────────────────

    @Nested
    @DisplayName("#86 틀린 값은 저장 전에 끊는다")
    class WrongValueRejected {

        @Test
        @DisplayName("예산 — 13월 (종전 200: 어느 달에도 안 걸리는 행이 남았다)")
        void budgetMonthThirteen() {
            assertRejected("budgetMonth=13", post("/expense/budget",
                    """
                    {"budgetAmount":1000,"budgetYear":2026,"budgetMonth":13}"""));
        }

        @Test
        @DisplayName("반복거래 — 32일 (종전 200: 스케줄러가 31 로 접어 고른 날짜가 조용히 사라졌다)")
        void recurringDayThirtyTwo() {
            assertRejected("dayOfMonth=32", post("/recurring-transaction",
                    """
                    {"categoryRowId":%d,"expenseType":"EXPENSE","amount":1000,
                     "frequency":"MONTHLY","dayOfMonth":32,"startDate":"2026-09-07"}"""
                            .formatted(categoryRowId)));
        }

        @Test
        @DisplayName("반복거래 — 0일 (종전 500: withDayOfMonth(0) 이 DateTimeException 을 던졌다)")
        void recurringDayZero() {
            // QA 목록엔 32 만 있지만 하한도 같은 자리다. 32 는 200 이었고 0 은 500 이었다 —
            // @Min(1) 하나가 둘을 함께 막는다.
            assertRejected("dayOfMonth=0", post("/recurring-transaction",
                    """
                    {"categoryRowId":%d,"expenseType":"EXPENSE","amount":1000,
                     "frequency":"MONTHLY","dayOfMonth":0,"startDate":"2026-09-07"}"""
                            .formatted(categoryRowId)));
        }

        @Test
        @DisplayName("더치페이 — 총액 -1 (종전 200: 받을 돈이 음수인 정산)")
        void dutchPayNegativeAmount() {
            assertRejected("dutchpay/totalAmount=-1", post("/dutch-pay",
                    """
                    {"title":"저녁","totalAmount":-1,"splitMethod":"EQUAL","dutchPayDate":"2026-09-07",
                     "participants":[{"participantName":"나","amount":1000,"isPayer":true}]}"""));
        }

        @Test
        @DisplayName("더치페이 — 참가자 빈 배열 (종전 200: 나눌 사람이 없는 정산)")
        void dutchPayEmptyParticipants() {
            assertRejected("dutchpay/participants=[]", post("/dutch-pay",
                    """
                    {"title":"저녁","totalAmount":1000,"splitMethod":"EQUAL","dutchPayDate":"2026-09-07",
                     "participants":[]}"""));
        }

        @Test
        @DisplayName("더치페이 — 참가자 생략 (종전 200)")
        void dutchPayNoParticipants() {
            assertRejected("dutchpay/participants 생략", post("/dutch-pay",
                    """
                    {"title":"저녁","totalAmount":1000,"splitMethod":"EQUAL","dutchPayDate":"2026-09-07"}"""));
        }

        @Test
        @DisplayName("일정 라벨 — color \\\"not-a-color\\\" (종전 200)")
        void labelBadColor() {
            assertRejected("label/color", post("/calendar/label",
                    """
                    {"labelName":"업무","color":"not-a-color"}"""));
        }

        @Test
        @DisplayName("메모 — color \\\"zzz\\\" (종전 200: 색이 안 칠해진 메모가 남았다)")
        void memoBadColor() {
            assertRejected("memo/color", post("/memo",
                    """
                    {"title":"메모","color":"zzz"}"""));
        }

        @Test
        @DisplayName("할 일 태그 — color \\\"zzz\\\" (종전 200)")
        void todoTagBadColor() {
            assertRejected("todo-tag/color", post("/todo-tag",
                    """
                    {"tagName":"업무","color":"zzz"}"""));
        }

        @Test
        @DisplayName("카테고리 — color \\\"zzz\\\" (종전 200)")
        void categoryBadColor() {
            assertRejected("category/color", post("/expense/category",
                    """
                    {"categoryName":"짜장","icon":"tag","color":"zzz","expenseType":"EXPENSE"}"""));
        }

        @Test
        @DisplayName("색은 3자리 축약도 안 받는다 — 컬럼(varchar(7))·팔레트가 전부 6자리다")
        void shortHexIsNotAColor() {
            assertRejected("memo/color=#fff", post("/memo",
                    """
                    {"title":"메모","color":"#fff"}"""));
        }
    }

    // ────────────────────────────── #91 ──────────────────────────────

    @Nested
    @DisplayName("#91 보낸 sortOrder 가 실제로 저장된다")
    class SortOrderIsKept {

        @Test
        @DisplayName("카테고리 — 5 를 보내면 5 가 남는다 (종전: 도메인이 0 을 무조건 박았다)")
        void categoryKeepsSortOrder() {
            Res res = post("/expense/category", """
                    {"categoryName":"짜장%s","icon":"tag","color":"#2c70bf","expenseType":"EXPENSE","sortOrder":5}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 6)));
            assertAccepted("category+sortOrder", res);
            assertThat(data(res).get("sortOrder")).isEqualTo(5);
        }

        @Test
        @DisplayName("카테고리 — 안 보내면 0(맨 앞)")
        void categoryDefaultsToZero() {
            Res res = post("/expense/category", """
                    {"categoryName":"짬뽕%s","icon":"tag","color":"#2c70bf","expenseType":"EXPENSE"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 6)));
            assertAccepted("category 기본값", res);
            assertThat(data(res).get("sortOrder")).isEqualTo(0);
        }

        @Test
        @DisplayName("저축목표 — 5 를 보내면 5 가 남는다")
        void savingGoalKeepsSortOrder() {
            Res res = post("/saving-goal", """
                    {"title":"여행","targetAmount":1000000,"currency":"KRW","sortOrder":5}""");
            assertAccepted("saving-goal+sortOrder", res);
            assertThat(data(res).get("sortOrder")).isEqualTo(5);
        }

        @Test
        @DisplayName("저축목표 — 안 보내면 0(맨 앞)")
        void savingGoalDefaultsToZero() {
            Res res = post("/saving-goal", """
                    {"title":"여행","targetAmount":1000000,"currency":"KRW"}""");
            assertAccepted("saving-goal 기본값", res);
            assertThat(data(res).get("sortOrder")).isEqualTo(0);
        }

        @Test
        @DisplayName("보유 — 보낸 값이 배열 순서를 이긴다")
        void holdingKeepsSentSortOrder() {
            Res res = post("/asset", """
                    {"assetName":"증권","assetType":"INVESTMENT","currency":"KRW","holdings":[
                      {"holdingType":"STOCK","linked":false,"holdingName":"가","holdingValue":100,"sortOrder":7},
                      {"holdingType":"STOCK","linked":false,"holdingName":"나","holdingValue":200,"sortOrder":3}]}""");
            assertAccepted("holdings+sortOrder", res);
            assertThat(sortOrdersOf(res)).containsExactly(7, 3);
        }

        @Test
        @DisplayName("보유 — 안 보내면 배열 인덱스다(0 이 아니다). 앱이 이 계약에 기대 값을 안 싣는다")
        void holdingWithoutSortOrderFallsBackToArrayIndex() {
            Res res = post("/asset", """
                    {"assetName":"증권","assetType":"INVESTMENT","currency":"KRW","holdings":[
                      {"holdingType":"STOCK","linked":false,"holdingName":"가","holdingValue":100},
                      {"holdingType":"STOCK","linked":false,"holdingName":"나","holdingValue":200},
                      {"holdingType":"STOCK","linked":false,"holdingName":"다","holdingValue":300}]}""");
            assertAccepted("holdings 기본값", res);
            // 전부 0 으로 두면 정렬이 rowId 로 떨어져 끌어 옮긴 순서가 사라진다.
            assertThat(sortOrdersOf(res)).containsExactly(0, 1, 2);
        }

        @SuppressWarnings("unchecked")
        private List<Integer> sortOrdersOf(Res res) {
            List<Map<String, Object>> holdings = (List<Map<String, Object>>) data(res).get("holdings");
            return holdings.stream().map(h -> (Integer) h.get("sortOrder")).toList();
        }
    }

    // ───────────────────────── 네거티브 컨트롤 ─────────────────────────

    @Nested
    @DisplayName("멀쩡한 요청은 그대로 통과한다 — 새 제약이 쓰던 화면을 막지 않는다")
    class StillAccepted {

        @Test
        @DisplayName("반복거래 31일은 받는다 — 2월엔 28·29일로 접혀 '말일' 이 된다")
        void dayThirtyOneIsMonthEnd() {
            // 32 를 막았다고 31 까지 막으면, 매달 마지막 날 나가는 이체·구독을 적을 방법이 사라진다.
            // 스케줄러가 min(dayOfMonth, 그 달 마지막 날) 로 접기 때문에 31 은 짧은 달에도 돈다.
            assertAccepted("dayOfMonth=31", post("/recurring-transaction",
                    """
                    {"categoryRowId":%d,"expenseType":"EXPENSE","amount":1000,
                     "frequency":"MONTHLY","dayOfMonth":31,"startDate":"2026-09-07"}"""
                            .formatted(categoryRowId)));
        }

        @Test
        @DisplayName("예산 12월은 받는다")
        void budgetMonthTwelve() {
            assertAccepted("budgetMonth=12", post("/expense/budget",
                    """
                    {"categoryRowId":%d,"budgetAmount":1000,"budgetYear":2026,"budgetMonth":12}"""
                            .formatted(categoryRowId)));
        }

        @Test
        @DisplayName("색은 대문자 6자리도 받는다 — 서버 기본값(#9E9E9E)이 그 모양이다")
        void upperCaseHexIsAColor() {
            assertAccepted("todo-tag/color=#9E9E9E", post("/todo-tag",
                    """
                    {"tagName":"업무%s","color":"#9E9E9E"}"""
                            .formatted(UUID.randomUUID().toString().substring(0, 6))));
        }

        @Test
        @DisplayName("색을 아예 안 보내면 통과한다 — 앱은 안 골랐을 때 키를 뺀다")
        void missingColorIsStillFine() {
            assertAccepted("todo-tag/color 생략", post("/todo-tag",
                    """
                    {"tagName":"업무%s"}"""
                            .formatted(UUID.randomUUID().toString().substring(0, 6))));
        }

        @Test
        @DisplayName("더치페이는 참가자 한 명이면 통과한다 — 결제자 수는 여기서 안 본다(#80)")
        void oneParticipantIsEnough() {
            assertAccepted("dutchpay/참가자 1명", post("/dutch-pay",
                    """
                    {"title":"저녁","totalAmount":1000,"splitMethod":"EQUAL","dutchPayDate":"2026-09-07",
                     "participants":[{"participantName":"나","amount":1000,"isPayer":true}]}"""));
        }

        @Test
        @DisplayName("웹이 실제로 보내는 팔레트 색(#2c70bf)은 통과한다")
        void webPaletteColorIsAccepted() {
            assertAccepted("label/color=#2c70bf", post("/calendar/label",
                    """
                    {"labelName":"업무%s","color":"#2c70bf"}"""
                            .formatted(UUID.randomUUID().toString().substring(0, 6))));
        }
    }
}
