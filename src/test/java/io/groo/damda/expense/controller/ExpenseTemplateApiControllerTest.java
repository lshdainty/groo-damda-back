package io.groo.damda.expense.controller;

import io.groo.core.type.YNType;
import io.groo.core.util.MessageResolver;
import io.groo.damda.common.config.web.WebConfig;
import io.groo.damda.expense.service.ExpenseTemplateService;
import io.groo.damda.expense.service.dto.ExpenseServiceDto;
import io.groo.damda.common.patch.Patch;
import io.groo.damda.expense.service.dto.ExpenseTemplateServiceDto;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.expense.type.TxKind;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ExpenseTemplate API 슬라이스 테스트 — 보안 필터 제외 + {@link WithLoginUser} 로 로그인 사용자 위임 검증.
 */
@WebMvcTest(controllers = ExpenseTemplateApiController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, LoginUserArgumentResolver.class})
@ActiveProfiles("test")
@WithLoginUser(rowId = 1L)
class ExpenseTemplateApiControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ExpenseTemplateService expenseTemplateService;
    @MockitoBean private MessageResolver messageResolver;

    private ExpenseTemplateServiceDto.TemplateInfo sampleTemplate() {
        return new ExpenseTemplateServiceDto.TemplateInfo(
                30L, 1L, "점심 정기", 5L, "식비", 2L, "현금", null, null, null, null,
                TxKind.EXPENSE, 9000L, "회사 근처", "김밥천국", "CARD",
                3, 0, YNType.N,
                LocalDateTime.of(2026, 7, 2, 12, 0),
                LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 7, 1, 0, 0),
                false);
    }

    private ExpenseServiceDto.ExpenseInfo sampleExpense() {
        return new ExpenseServiceDto.ExpenseInfo(
                10L, 1L, 5L, "식비", "utensils", "#ffffff",
                2L, "현금", ExpenseType.EXPENSE, 9000L, "회사 근처",
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
    @DisplayName("POST /expense-template — 로그인 사용자·바디로 템플릿 생성 위임")
    void createTemplate() throws Exception {
        given(expenseTemplateService.createTemplate(any())).willReturn(sampleTemplate());

        String body = """
                {"templateName":"점심 정기","categoryRowId":5,"assetRowId":2,"expenseType":"EXPENSE",
                 "amount":9000,"description":"회사 근처","merchant":"김밥천국","paymentMethod":"CARD",
                 "sortOrder":0,"lockAmount":"N"}
                """;

        mockMvc.perform(post("/api/v1/expense-template")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowId").value(30))
                .andExpect(jsonPath("$.data.templateName").value("점심 정기"));

        var captor = ArgumentCaptor.forClass(ExpenseTemplateServiceDto.CreateCommand.class);
        verify(expenseTemplateService).createTemplate(captor.capture());
        assertThat(captor.getValue().userRowId()).isEqualTo(1L);
        assertThat(captor.getValue().templateName()).isEqualTo("점심 정기");
        assertThat(captor.getValue().categoryRowId()).isEqualTo(5L);
        assertThat(captor.getValue().amount()).isEqualTo(9000L);
        assertThat(captor.getValue().expenseType()).isEqualTo(TxKind.EXPENSE);
        assertThat(captor.getValue().lockAmount()).isEqualTo(YNType.N);
    }

    @Test
    @DisplayName("GET /expense-templates — 로그인 사용자로 템플릿 목록 조회 위임")
    void getTemplates() throws Exception {
        given(expenseTemplateService.getTemplates(1L)).willReturn(List.of(sampleTemplate()));

        mockMvc.perform(get("/api/v1/expense-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.templates[0].rowId").value(30));

        verify(expenseTemplateService).getTemplates(1L);
    }

    @Test
    @DisplayName("PUT /expense-template/{id} — id·로그인 사용자·바디로 수정 위임")
    void updateTemplate() throws Exception {
        given(expenseTemplateService.updateTemplate(eq(30L), eq(1L), any())).willReturn(sampleTemplate());

        String body = """
                {"templateName":"점심 변경","categoryRowId":5,"assetRowId":2,"expenseType":"EXPENSE",
                 "amount":11000,"description":"수정","merchant":"분식","paymentMethod":"CASH","lockAmount":"Y"}
                """;

        mockMvc.perform(put("/api/v1/expense-template/{id}", 30L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowId").value(30));

        var captor = ArgumentCaptor.forClass(ExpenseTemplateServiceDto.UpdateCommand.class);
        verify(expenseTemplateService).updateTemplate(eq(30L), eq(1L), captor.capture());
        assertThat(captor.getValue().templateName()).isEqualTo(Patch.set("점심 변경"));
        assertThat(captor.getValue().amount()).isEqualTo(Patch.set(11000L));
        assertThat(captor.getValue().lockAmount()).isEqualTo(Patch.set(YNType.Y));
        // 실린 칸은 "이 값으로", 안 실린 칸은 "안 왔다" 로 넘어간다(QA #96).
        assertThat(captor.getValue().description()).isEqualTo(Patch.set("수정"));
    }

    @Test
    @DisplayName("DELETE /expense-template/{id} — id·로그인 사용자로 삭제 위임")
    void deleteTemplate() throws Exception {
        mockMvc.perform(delete("/api/v1/expense-template/{id}", 30L))
                .andExpect(status().isOk());

        verify(expenseTemplateService).deleteTemplate(eq(30L), eq(1L));
    }

    @Test
    @DisplayName("POST /expense-template/{id}/use — id·로그인 사용자·날짜로 템플릿 사용(거래 생성) 위임")
    void useTemplate() throws Exception {
        given(expenseTemplateService.useTemplate(30L, 1L, LocalDate.of(2026, 7, 3)))
                .willReturn(sampleExpense());

        mockMvc.perform(post("/api/v1/expense-template/{id}/use", 30L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expenseDate\":\"2026-07-03\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowId").value(10))
                .andExpect(jsonPath("$.data.amount").value(9000));

        verify(expenseTemplateService).useTemplate(30L, 1L, LocalDate.of(2026, 7, 3));
    }

    @Test
    @DisplayName("POST /expense-template/{id}/touch — id·로그인 사용자로 사용 표시(useCount 갱신) 위임")
    void touchTemplate() throws Exception {
        given(expenseTemplateService.markTemplateUsed(30L, 1L)).willReturn(sampleTemplate());

        mockMvc.perform(post("/api/v1/expense-template/{id}/touch", 30L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowId").value(30));

        verify(expenseTemplateService).markTemplateUsed(30L, 1L);
    }
}
