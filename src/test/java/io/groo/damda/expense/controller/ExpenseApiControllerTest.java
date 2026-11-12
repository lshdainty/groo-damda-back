package io.groo.damda.expense.controller;

import io.groo.damda.common.patch.Patch;
import io.groo.core.util.MessageResolver;
import io.groo.damda.common.config.web.WebConfig;
import io.groo.damda.expense.service.ExpenseService;
import io.groo.damda.expense.service.dto.ExpenseServiceDto;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.security.filter.JwtAuthenticationFilter;
import io.groo.damda.security.resolver.LoginUserArgumentResolver;
import io.groo.damda.support.security.WithLoginUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Expense API 슬라이스 테스트.
 *
 * <p>보안 필터는 끄고({@code addFilters=false}) {@link WithLoginUser} 로 SecurityContext 를 세팅 →
 * {@code @LoginUser} ArgumentResolver({@link WebConfig} 가 등록)가 UserPrincipal 을 주입한다.
 * 서비스는 mock — 컨트롤러의 매핑·바디 역직렬화·쿼리파라미터 바인딩·로그인 사용자 위임을 검증한다.
 */
@WebMvcTest(controllers = ExpenseApiController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, LoginUserArgumentResolver.class})
@ActiveProfiles("test")
@WithLoginUser(rowId = 1L)
class ExpenseApiControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ExpenseService expenseService;
    // groo-core GlobalExceptionHandler(@ControllerAdvice) 의존 — 슬라이스 로드용 mock.
    @MockitoBean private MessageResolver messageResolver;

    private ExpenseServiceDto.ExpenseInfo sampleInfo() {
        return new ExpenseServiceDto.ExpenseInfo(
                10L, 1L, 5L, "식비", "utensils", "#ffffff",
                2L, "현금", ExpenseType.EXPENSE, 15000L, "점심",
                LocalDateTime.of(2026, 7, 3, 12, 0), "김밥천국", "CARD",
                null,       // installmentMonths — 일시불
            null, null, // refundedAt · refundTransferRowId — 환불 아님
            null, null, null, // 원 통화 셋 — 원화 결제
            null, null, // 일정·할 일 연결 없음
            null,       // autoSource — 손으로 쓴 거래
                LocalDateTime.of(2026, 7, 3, 12, 0), LocalDateTime.of(2026, 7, 3, 12, 0),
                List.of(),
            null,       // refundedAmount — 조회에는 없다
            null, null, // cardSettledThrough · recordOnlyAmount — 기록용 아님
            false,      // moneyLocked — 결제 전
            false,      // replaceable — 잠기지 않았다
            false,      // isFixed — 변동
            true);      // deletable — 손으로 쓴 거래
    }

    @Test
    @DisplayName("POST /expense — isFixed 를 서비스 명령으로 넘기고, 응답에도 isFixed 키가 실린다")
    void createExpenseCarriesFixed() throws Exception {
        given(expenseService.createExpense(any())).willReturn(sampleInfo());

        String body = """
                {"categoryRowId":5,"expenseType":"EXPENSE","amount":700000,
                 "expenseDate":"2026-07-03","isFixed":true}
                """;

        mockMvc.perform(post("/api/v1/expense")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                // 키 이름은 isFixed 다 — Jackson 이 boolean 레코드 칸을 fixed 로 바꿔 내보내지 않는지 잠근다.
                .andExpect(jsonPath("$.data.isFixed").value(false))
                .andExpect(jsonPath("$.data.deletable").value(true))
                .andExpect(jsonPath("$.data.fixed").doesNotExist());

        ArgumentCaptor<ExpenseServiceDto.CreateCommand> captor =
                ArgumentCaptor.forClass(ExpenseServiceDto.CreateCommand.class);
        verify(expenseService).createExpense(captor.capture());
        assertThat(captor.getValue().isFixed()).isTrue();
    }

    @Test
    @DisplayName("POST /expense — isFixed 를 안 보내는 옛 앱은 변동(null)으로 들어간다")
    void createExpenseWithoutFixedIsVariable() throws Exception {
        given(expenseService.createExpense(any())).willReturn(sampleInfo());

        mockMvc.perform(post("/api/v1/expense")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryRowId":5,"expenseType":"EXPENSE","amount":15000,"expenseDate":"2026-07-03"}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<ExpenseServiceDto.CreateCommand> captor =
                ArgumentCaptor.forClass(ExpenseServiceDto.CreateCommand.class);
        verify(expenseService).createExpense(captor.capture());
        assertThat(captor.getValue().isFixed()).isNull();
    }

    @Test
    @DisplayName("PUT /expense/{id} — isFixed 키가 없으면 안 온 것(유지), 있으면 그 값이다")
    void updateExpenseFixedPatch() throws Exception {
        given(expenseService.updateExpense(eq(10L), eq(1L), any())).willReturn(sampleInfo());

        mockMvc.perform(put("/api/v1/expense/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"메모\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/expense/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isFixed\":true}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ExpenseServiceDto.UpdateCommand> captor =
                ArgumentCaptor.forClass(ExpenseServiceDto.UpdateCommand.class);
        verify(expenseService, org.mockito.Mockito.times(2)).updateExpense(eq(10L), eq(1L), captor.capture());
        assertThat(captor.getAllValues().get(0).isFixed().present()).isFalse();
        assertThat(captor.getAllValues().get(1).isFixed().present()).isTrue();
        assertThat(captor.getAllValues().get(1).isFixed().value()).isTrue();
    }

    @Test
    @DisplayName("GET /expenses/search?fixed=true — 고정 조건이 서비스 명령에 실린다")
    void searchPassesFixedFilter() throws Exception {
        given(expenseService.searchExpenses(any())).willReturn(List.of());

        mockMvc.perform(get("/api/v1/expenses/search").param("fixed", "true"))
                .andExpect(status().isOk());

        ArgumentCaptor<ExpenseServiceDto.SearchCommand> captor =
                ArgumentCaptor.forClass(ExpenseServiceDto.SearchCommand.class);
        verify(expenseService).searchExpenses(captor.capture());
        assertThat(captor.getValue().fixed()).isTrue();
    }

    @Test
    @DisplayName("PUT /expenses/fixed — 로그인 사용자·고른 거래·값으로 일괄 변경을 위임하고 바뀐 건수를 돌려준다")
    void bulkMarkFixed() throws Exception {
        given(expenseService.bulkMarkFixed(any())).willReturn(2);

        mockMvc.perform(put("/api/v1/expenses/fixed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expenseIds\":[1,2,3],\"isFixed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(2));

        ArgumentCaptor<ExpenseServiceDto.BulkFixedCommand> captor =
                ArgumentCaptor.forClass(ExpenseServiceDto.BulkFixedCommand.class);
        verify(expenseService).bulkMarkFixed(captor.capture());
        assertThat(captor.getValue().userRowId()).isEqualTo(1L);
        assertThat(captor.getValue().expenseRowIds()).containsExactly(1L, 2L, 3L);
        assertThat(captor.getValue().fixed()).isTrue();
    }

    @Test
    @DisplayName("PUT /expenses/fixed — 거래를 안 골랐거나 값이 없으면 400, 501건 이상도 400")
    void bulkMarkFixedRejectsBadBody() throws Exception {
        for (String body : List.of(
                "{\"expenseIds\":[],\"isFixed\":true}",
                "{\"isFixed\":true}",
                "{\"expenseIds\":[1]}")) {
            mockMvc.perform(put("/api/v1/expenses/fixed")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        String tooMany = java.util.stream.IntStream.rangeClosed(1, 501)
                .mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        mockMvc.perform(put("/api/v1/expenses/fixed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expenseIds\":[" + tooMany + "],\"isFixed\":true}"))
                .andExpect(status().isBadRequest());

        verify(expenseService, never()).bulkMarkFixed(any());
    }

    @Test
    @DisplayName("GET /expenses/summary/range — 합계·월별·카테고리에 고정 몫 키가 실린다")
    void rangeSummaryExposesFixed() throws Exception {
        given(expenseService.getRangeSummary(1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null))
                .willReturn(new ExpenseServiceDto.RangeSummary(
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), 3_000_000L, 800_000L,
                        3_000_000L, 700_000L,
                        List.of(new ExpenseServiceDto.CategoryBreakdown(
                                10L, "주거", null, null, ExpenseType.EXPENSE, 700_000L, 700_000L)),
                        List.of(new ExpenseServiceDto.RangeMonthlyBucket(2026, 7, 3_000_000L, 800_000L,
                                3_000_000L, 700_000L,
                                List.of(new ExpenseServiceDto.CategoryAmount(10L, 700_000L, 700_000L))))));

        mockMvc.perform(get("/api/v1/expenses/summary/range")
                        .param("startDate", "2026-07-01").param("endDate", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fixedIncome").value(3_000_000))
                .andExpect(jsonPath("$.data.fixedExpense").value(700_000))
                .andExpect(jsonPath("$.data.categoryBreakdown[0].fixedAmount").value(700_000))
                .andExpect(jsonPath("$.data.monthlyBuckets[0].fixedExpense").value(700_000))
                .andExpect(jsonPath("$.data.monthlyBuckets[0].categoryExpenses[0].fixedAmount").value(700_000));
    }

    @Test
    @DisplayName("POST /expense — 거래처 101자는 400 (종전엔 DB 제약에 걸려 500)")
    void createExpenseRejectsLongMerchant() throws Exception {
        String body = """
                {"categoryRowId":5,"expenseType":"EXPENSE","amount":15000,
                 "expenseDate":"2026-07-03","merchant":"%s"}
                """.formatted("가".repeat(101));

        mockMvc.perform(post("/api/v1/expense")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(expenseService, never()).createExpense(any());
    }

    @Test
    @DisplayName("POST /expense — 100억원 초과 금액은 400")
    void createExpenseRejectsHugeAmount() throws Exception {
        String body = """
                {"categoryRowId":5,"expenseType":"EXPENSE","amount":99999999999999,
                 "expenseDate":"2026-07-03","merchant":"김밥천국"}
                """;

        mockMvc.perform(post("/api/v1/expense")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(expenseService, never()).createExpense(any());
    }

    @Test
    @DisplayName("POST /expense — 로그인 사용자·바디로 createExpense 위임(날짜 유연 파싱)")
    void createExpense() throws Exception {
        given(expenseService.createExpense(any())).willReturn(sampleInfo());

        String body = """
                {"categoryRowId":5,"assetRowId":2,"expenseType":"EXPENSE","amount":15000,
                 "description":"점심","expenseDate":"2026-07-03","merchant":"김밥천국","paymentMethod":"CARD"}
                """;

        mockMvc.perform(post("/api/v1/expense")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rowId").value(10))
                .andExpect(jsonPath("$.data.amount").value(15000));

        var captor = ArgumentCaptor.forClass(ExpenseServiceDto.CreateCommand.class);
        verify(expenseService).createExpense(captor.capture());
        assertThat(captor.getValue().userRowId()).isEqualTo(1L);
        assertThat(captor.getValue().categoryRowId()).isEqualTo(5L);
        assertThat(captor.getValue().amount()).isEqualTo(15000L);
        assertThat(captor.getValue().expenseType()).isEqualTo(ExpenseType.EXPENSE);
        // "yyyy-MM-dd" → 해당 일자 00:00:00 으로 파싱
        assertThat(captor.getValue().expenseDate()).isEqualTo(LocalDateTime.of(2026, 7, 3, 0, 0));
    }

    @Test
    @DisplayName("GET /expenses — 쿼리파라미터(카테고리·자산·타입·기간) 매핑해 조회 위임")
    void getExpenses() throws Exception {
        given(expenseService.getExpenses(any(), any(), any(), any(), any(), any()))
                .willReturn(List.of(sampleInfo()));

        mockMvc.perform(get("/api/v1/expenses")
                        .param("categoryId", "5")
                        .param("assetId", "2")
                        .param("expenseType", "EXPENSE")
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expenses[0].rowId").value(10));

        verify(expenseService).getExpenses(1L, 5L, 2L, ExpenseType.EXPENSE,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
    }

    @Test
    @DisplayName("GET /expenses — 선택 파라미터 미지정이면 null 로 위임")
    void getExpensesNoParams() throws Exception {
        given(expenseService.getExpenses(any(), any(), any(), any(), any(), any()))
                .willReturn(List.of());

        mockMvc.perform(get("/api/v1/expenses"))
                .andExpect(status().isOk());

        verify(expenseService).getExpenses(eq(1L), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("GET /expenses?expenseType=INVALID — 잘못된 enum 값이면 400")
    void getExpensesInvalidEnum() throws Exception {
        mockMvc.perform(get("/api/v1/expenses").param("expenseType", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /expense/{id} — id·로그인 사용자·바디(분할 포함)로 수정 위임")
    void updateExpense() throws Exception {
        given(expenseService.updateExpense(eq(10L), eq(1L), any())).willReturn(sampleInfo());

        String body = """
                {"categoryRowId":5,"amount":20000,"expenseType":"EXPENSE","expenseDate":"2026-07-03",
                 "splits":[{"categoryRowId":7,"amount":8000,"label":"커피","sortOrder":0},
                           {"categoryRowId":8,"amount":12000,"label":"밥","sortOrder":1}]}
                """;

        mockMvc.perform(put("/api/v1/expense/{id}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowId").value(10));

        var captor = ArgumentCaptor.forClass(ExpenseServiceDto.UpdateCommand.class);
        verify(expenseService).updateExpense(eq(10L), eq(1L), captor.capture());
        assertThat(captor.getValue().amount()).isEqualTo(Patch.set(20000L));
        assertThat(captor.getValue().splits()).hasSize(2);
        assertThat(captor.getValue().splits().get(0).categoryRowId()).isEqualTo(7L);
        assertThat(captor.getValue().splits().get(0).amount()).isEqualTo(8000L);
    }

    @Test
    @DisplayName("PUT /expense/{id} — 날짜만(yyyy-MM-dd) 온 일시는 표시해 넘긴다(잠긴 거래의 날짜 비교, D12)")
    void updateMarksDateOnly() throws Exception {
        given(expenseService.updateExpense(eq(10L), eq(1L), any())).willReturn(sampleInfo());

        mockMvc.perform(put("/api/v1/expense/{id}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expenseDate\":\"2026-07-03\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/expense/{id}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expenseDate\":\"2026-07-03T12:30\"}"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(ExpenseServiceDto.UpdateCommand.class);
        verify(expenseService, org.mockito.Mockito.times(2)).updateExpense(eq(10L), eq(1L), captor.capture());
        assertThat(captor.getAllValues().get(0).expenseDateDateOnly()).isTrue();
        assertThat(captor.getAllValues().get(1).expenseDateDateOnly()).isFalse();
    }

    @Test
    @DisplayName("POST /expense/{id}/replace — 생성 본문 + 분할로 고쳐 쓰기 위임, 응답은 새 거래")
    void replaceExpense() throws Exception {
        given(expenseService.replaceExpense(eq(10L), eq(1L), any(), any())).willReturn(sampleInfo());

        String body = """
                {"categoryRowId":5,"assetRowId":2,"expenseType":"EXPENSE","amount":20000,
                 "expenseDate":"2026-07-03T12:00","merchant":"김밥천국","installmentMonths":3,
                 "splits":[{"categoryRowId":7,"amount":8000,"label":"커피","sortOrder":0},
                           {"categoryRowId":8,"amount":12000,"label":"밥","sortOrder":1}]}
                """;

        mockMvc.perform(post("/api/v1/expense/{id}/replace", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowId").value(10))
                .andExpect(jsonPath("$.data.moneyLocked").value(false))
                .andExpect(jsonPath("$.data.replaceable").value(false));

        var cmd = ArgumentCaptor.forClass(ExpenseServiceDto.CreateCommand.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<io.groo.damda.expense.service.dto.ExpenseSplitServiceDto.SplitCommand>> splits =
                ArgumentCaptor.forClass(List.class);
        verify(expenseService).replaceExpense(eq(10L), eq(1L), cmd.capture(), splits.capture());
        assertThat(cmd.getValue().userRowId()).isEqualTo(1L);
        assertThat(cmd.getValue().amount()).isEqualTo(20000L);
        assertThat(cmd.getValue().expenseDate()).isEqualTo(LocalDateTime.of(2026, 7, 3, 12, 0));
        assertThat(cmd.getValue().installmentMonths()).isEqualTo(3);
        assertThat(splits.getValue()).hasSize(2);
        assertThat(splits.getValue().get(0).rowId()).as("새 거래의 분할 — 옛 행 id 는 쓰지 않는다").isNull();
    }

    @Test
    @DisplayName("POST /expense/{id}/replace — 분할을 안 보내면 null(옛 분할을 옮긴다), 금액이 빠지면 400")
    void replaceWithoutSplits() throws Exception {
        given(expenseService.replaceExpense(eq(10L), eq(1L), any(), any())).willReturn(sampleInfo());

        mockMvc.perform(post("/api/v1/expense/{id}/replace", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryRowId\":5,\"expenseType\":\"EXPENSE\",\"amount\":1000,\"expenseDate\":\"2026-07-03\"}"))
                .andExpect(status().isOk());
        verify(expenseService).replaceExpense(eq(10L), eq(1L), any(), isNull());

        mockMvc.perform(post("/api/v1/expense/{id}/replace", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryRowId\":5,\"expenseType\":\"EXPENSE\",\"expenseDate\":\"2026-07-03\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /expense/{id} — splits 미포함이면 null(분할 미변경) 로 위임")
    void updateExpenseWithoutSplits() throws Exception {
        given(expenseService.updateExpense(eq(10L), eq(1L), any())).willReturn(sampleInfo());

        mockMvc.perform(put("/api/v1/expense/{id}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        // categoryRowId 는 수정에서도 필수다 — 서비스가 조건 없이 카테고리를 조회해
                        // 빠지면 500 이었다(QA 2026-09-07 #85). 웹·앱 모두 수정에서 항상 싣는다.
                        .content("{\"categoryRowId\":7,\"amount\":30000,\"expenseType\":\"EXPENSE\",\"expenseDate\":\"2026-07-03\"}"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(ExpenseServiceDto.UpdateCommand.class);
        verify(expenseService).updateExpense(eq(10L), eq(1L), captor.capture());
        assertThat(captor.getValue().splits()).isNull();
    }

    /**
     * QA #81 → #96 — <b>같은 자리의 답이 바뀌었다.</b>
     *
     * <p>{@code expense_type}·{@code amount}·{@code expense_date} 는 셋 다 NOT NULL 이다.
     * 종전엔 수정 경로가 받은 값을 그대로 덮어써서, 하나만 빠져도 null 이 DB 까지 내려가
     * <b>409 "다른 곳에서 먼저 수정됐어요"</b> 로 튕겼다. #81 은 그 자리를 {@code @NotBlank} 로
     * 막아 400 을 줬다 — 덮어쓰는 한 그게 맞는 답이었다.
     *
     * <p>지금은 <b>안 보낸 칸을 덮지 않는다</b>(사용자 결정 2026-09-07). 그래서 빠진 일시는
     * 거절할 이유가 없다 — 지금 값이 그대로 남는다. 거절해야 하는 것은 <b>명시적 {@code null}</b>
     * 하나다(아래 {@code explicitNull} 테스트). 이 둘을 가르는 것이 이번 변경의 전부다.
     *
     * <p>되돌려 보는 법(네거티브 컨트롤): {@code AbsentAwareOptionalModule} 을 빼면 "키 없음" 이
     * {@code Optional.empty()} 로 도착해 {@code @NotBlank} 에 걸리고, 아래가 400 으로 깨진다.
     */
    @Test
    @DisplayName("PUT /expense/{id} — 거래 일시를 안 보내면 그 칸을 안 건드린다(400 이 아니다)")
    void updateExpense_missingExpenseDate_isPartialUpdate() throws Exception {
        given(expenseService.updateExpense(eq(10L), eq(1L), any())).willReturn(sampleInfo());

        mockMvc.perform(put("/api/v1/expense/{id}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":30000,\"expenseType\":\"EXPENSE\"}"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(ExpenseServiceDto.UpdateCommand.class);
        verify(expenseService).updateExpense(eq(10L), eq(1L), captor.capture());
        assertThat(captor.getValue().expenseDate()).isEqualTo(Patch.absent());
        assertThat(captor.getValue().categoryRowId()).isEqualTo(Patch.absent());
        assertThat(captor.getValue().amount()).isEqualTo(Patch.set(30000L));
    }

    /**
     * 명시적 {@code null} 은 "지워라" 인데 NOT NULL 칸에서는 성립하지 않는다 — 400 이다.
     * 안 보낸 것과 <b>다른 답</b>이어야 한다(바로 위 테스트).
     */
    @Test
    @DisplayName("PUT /expense/{id} — 거래 일시에 null 을 실으면 400")
    void updateExpense_explicitNullExpenseDate_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/expense/{id}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":30000,\"expenseType\":\"EXPENSE\",\"expenseDate\":null}"))
                .andExpect(status().isBadRequest());

        verify(expenseService, never()).updateExpense(any(Long.class), any(Long.class), any());
    }

    @Test
    @DisplayName("POST /expense — 금액이 빠지면 400")
    void createExpense_missingAmount_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/expense")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryRowId\":5,\"expenseType\":\"EXPENSE\",\"expenseDate\":\"2026-07-03\"}"))
                .andExpect(status().isBadRequest());

        verify(expenseService, never()).createExpense(any());
    }

    @Test
    @DisplayName("DELETE /expense/{id} — id·로그인 사용자로 삭제 위임")
    void deleteExpense() throws Exception {
        mockMvc.perform(delete("/api/v1/expense/{id}", 10L))
                .andExpect(status().isOk());

        verify(expenseService).deleteExpense(eq(10L), eq(1L));
    }

    @Test
    @DisplayName("POST /expenses/delete — 로그인 사용자·고른 거래로 일괄 삭제를 위임하고 지운 수·환급 합을 돌려준다")
    void bulkDeleteExpenses() throws Exception {
        given(expenseService.bulkDeleteExpenses(any()))
                .willReturn(new ExpenseServiceDto.BulkDeleteResult(3, 80_000L));

        mockMvc.perform(post("/api/v1/expenses/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expenseIds\":[1,2,3]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedCount").value(3))
                .andExpect(jsonPath("$.data.refundedAmount").value(80000));

        ArgumentCaptor<ExpenseServiceDto.BulkDeleteCommand> captor =
                ArgumentCaptor.forClass(ExpenseServiceDto.BulkDeleteCommand.class);
        verify(expenseService).bulkDeleteExpenses(captor.capture());
        assertThat(captor.getValue().userRowId()).isEqualTo(1L);
        assertThat(captor.getValue().expenseRowIds()).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("POST /expenses/delete — 환급이 없으면 refundedAmount 는 null")
    void bulkDeleteExpensesWithoutRefund() throws Exception {
        given(expenseService.bulkDeleteExpenses(any()))
                .willReturn(new ExpenseServiceDto.BulkDeleteResult(1, null));

        mockMvc.perform(post("/api/v1/expenses/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expenseIds\":[7]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedCount").value(1))
                .andExpect(jsonPath("$.data.refundedAmount").doesNotExist());
    }

    @Test
    @DisplayName("POST /expenses/delete — 빈 목록 · 아이디 없음 · 501건은 400")
    void bulkDeleteExpensesRejectsBadBody() throws Exception {
        for (String body : List.of("{\"expenseIds\":[]}", "{}")) {
            mockMvc.perform(post("/api/v1/expenses/delete")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        String tooMany = java.util.stream.IntStream.rangeClosed(1, 501)
                .mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        mockMvc.perform(post("/api/v1/expenses/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expenseIds\":[" + tooMany + "]}"))
                .andExpect(status().isBadRequest());
        // 경계: 500건은 통과
        String max = java.util.stream.IntStream.rangeClosed(1, 500)
                .mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        given(expenseService.bulkDeleteExpenses(any()))
                .willReturn(new ExpenseServiceDto.BulkDeleteResult(500, null));
        mockMvc.perform(post("/api/v1/expenses/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expenseIds\":[" + max + "]}"))
                .andExpect(status().isOk());

        verify(expenseService, times(1)).bulkDeleteExpenses(any());
    }

    @Test
    @DisplayName("GET /expenses/summary/daily — 날짜 파라미터로 일별 요약 위임")
    void getDailySummary() throws Exception {
        given(expenseService.getDailySummary(1L, LocalDate.of(2026, 7, 3)))
                .willReturn(new ExpenseServiceDto.DailySummary(LocalDate.of(2026, 7, 3), 0L, 15000L));

        mockMvc.perform(get("/api/v1/expenses/summary/daily").param("date", "2026-07-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalExpense").value(15000))
                .andExpect(jsonPath("$.data.totalIncome").value(0));

        verify(expenseService).getDailySummary(1L, LocalDate.of(2026, 7, 3));
    }

    @Test
    @DisplayName("GET /expenses/summary/range — 기간 파라미터로 범위 요약 위임")
    void getRangeSummary() throws Exception {
        given(expenseService.getRangeSummary(1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null))
                .willReturn(new ExpenseServiceDto.RangeSummary(
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                        1000L, 5000L, 400L, 2000L, List.of(), List.of()));

        mockMvc.perform(get("/api/v1/expenses/summary/range")
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalIncome").value(1000))
                .andExpect(jsonPath("$.data.totalExpense").value(5000));

        verify(expenseService).getRangeSummary(1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null);
    }

    @Test
    @DisplayName("GET /expenses/summary/trend — months 기본값 6 으로 추이 위임")
    void getMonthlyTrendDefault() throws Exception {
        given(expenseService.getMonthlyTrend(1L, 6)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/expenses/summary/trend"))
                .andExpect(status().isOk());

        verify(expenseService).getMonthlyTrend(1L, 6);
    }

    @Test
    @DisplayName("GET /expenses/summary/trend?months=3 — 지정 months 로 추이 위임")
    void getMonthlyTrendExplicit() throws Exception {
        given(expenseService.getMonthlyTrend(1L, 3)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/expenses/summary/trend").param("months", "3"))
                .andExpect(status().isOk());

        verify(expenseService).getMonthlyTrend(1L, 3);
    }

    @Test
    @DisplayName("GET /expenses/summary/by-merchant — 기간(선택) 매핑해 위임")
    void getMerchantSummary() throws Exception {
        given(expenseService.getMerchantSummary(1L, null, null))
                .willReturn(List.of(new ExpenseServiceDto.MerchantSummary("김밥천국", 15000L, 2)));

        mockMvc.perform(get("/api/v1/expenses/summary/by-merchant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchants[0].merchant").value("김밥천국"))
                .andExpect(jsonPath("$.data.merchants[0].count").value(2));

        verify(expenseService).getMerchantSummary(1L, null, null);
    }

    @Test
    @DisplayName("GET /expenses/summary/by-asset — 기간(선택) 매핑해 위임")
    void getAssetSummary() throws Exception {
        given(expenseService.getAssetSummary(1L, null, null))
                .willReturn(List.of(new ExpenseServiceDto.AssetSummary(2L, "현금", 15000L, 3)));

        mockMvc.perform(get("/api/v1/expenses/summary/by-asset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assets[0].assetName").value("현금"));

        verify(expenseService).getAssetSummary(1L, null, null);
    }

    @Test
    @DisplayName("GET /expenses/summary/heatmap — 기간 파라미터로 히트맵 위임")
    void getHeatmap() throws Exception {
        given(expenseService.getHeatmap(1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .willReturn(List.of(new ExpenseServiceDto.HeatmapCell(1, 12, 15000L)));

        mockMvc.perform(get("/api/v1/expenses/summary/heatmap")
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cells[0].totalAmount").value(15000));

        verify(expenseService).getHeatmap(1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
    }

    @Test
    @DisplayName("GET /calendar/event/{eventId}/expenses — 로그인 사용자와 이벤트 id 로 조회 위임 (DAM-1)")
    void getExpensesByCalendarEvent() throws Exception {
        given(expenseService.getExpensesByCalendarEvent(1L, 77L)).willReturn(List.of(sampleInfo()));

        mockMvc.perform(get("/api/v1/calendar/event/{eventId}/expenses", 77L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expenses[0].rowId").value(10));

        verify(expenseService).getExpensesByCalendarEvent(1L, 77L);
    }

    @Test
    @DisplayName("GET /todo/{todoId}/expenses — 로그인 사용자와 투두 id 로 조회 위임 (DAM-1)")
    void getExpensesByTodo() throws Exception {
        given(expenseService.getExpensesByTodo(1L, 88L)).willReturn(List.of(sampleInfo()));

        mockMvc.perform(get("/api/v1/todo/{todoId}/expenses", 88L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expenses[0].rowId").value(10));

        verify(expenseService).getExpensesByTodo(1L, 88L);
    }

    @Test
    @DisplayName("GET /expenses/search — 검색 조건을 SearchCommand 로 묶어 위임")
    void searchExpenses() throws Exception {
        given(expenseService.searchExpenses(any())).willReturn(List.of(sampleInfo()));

        mockMvc.perform(get("/api/v1/expenses/search")
                        .param("categoryId", "5")
                        .param("assetId", "2")
                        .param("expenseType", "EXPENSE")
                        .param("keyword", "점심")
                        .param("merchant", "김밥")
                        .param("minAmount", "1000")
                        .param("maxAmount", "50000")
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expenses[0].rowId").value(10));

        var captor = ArgumentCaptor.forClass(ExpenseServiceDto.SearchCommand.class);
        verify(expenseService).searchExpenses(captor.capture());
        assertThat(captor.getValue().userRowId()).isEqualTo(1L);
        assertThat(captor.getValue().categoryRowId()).isEqualTo(5L);
        assertThat(captor.getValue().assetRowId()).isEqualTo(2L);
        assertThat(captor.getValue().expenseType()).isEqualTo(ExpenseType.EXPENSE);
        assertThat(captor.getValue().keyword()).isEqualTo("점심");
        assertThat(captor.getValue().merchant()).isEqualTo("김밥");
        assertThat(captor.getValue().minAmount()).isEqualTo(1000L);
        assertThat(captor.getValue().maxAmount()).isEqualTo(50000L);
        assertThat(captor.getValue().startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(captor.getValue().endDate()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    @DisplayName("POST /expense — 존재하지 않는 날짜(2026-02-30)는 400 (종전엔 500)")
    void createExpenseRejectsImpossibleDate() throws Exception {
        String body = """
                {"categoryRowId":5,"expenseType":"EXPENSE","amount":15000,
                 "expenseDate":"2026-02-30","merchant":"김밥천국"}
                """;

        mockMvc.perform(post("/api/v1/expense")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(expenseService, never()).createExpense(any());
    }

    @Test
    @DisplayName("POST /expense — 존재하지 않는 일시(2026-02-30T09:00:00)도 400")
    void createExpenseRejectsImpossibleDateTime() throws Exception {
        String body = """
                {"categoryRowId":5,"expenseType":"EXPENSE","amount":15000,
                 "expenseDate":"2026-02-30T09:00:00","merchant":"김밥천국"}
                """;

        mockMvc.perform(post("/api/v1/expense")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(expenseService, never()).createExpense(any());
    }
}
