package io.groo.damda.asset.controller;

import io.groo.core.util.MessageResolver;
import io.groo.damda.asset.service.AssetTradeService;
import io.groo.damda.asset.service.dto.AssetTradeServiceDto;
import io.groo.damda.common.config.web.WebConfig;
import io.groo.damda.security.filter.JwtAuthenticationFilter;
import io.groo.damda.security.resolver.LoginUserArgumentResolver;
import io.groo.damda.support.security.WithLoginUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 매매 미리보기 응답 — 금액 칸의 통화가 함께 실린다(칸 추가만, 기존 칸 그대로). */
@WebMvcTest(controllers = AssetTradeApiController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, LoginUserArgumentResolver.class})
@ActiveProfiles("test")
@WithLoginUser(rowId = 1L)
class AssetTradeApiControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AssetTradeService assetTradeService;
    @MockitoBean private MessageResolver messageResolver;

    @Test
    @DisplayName("POST /asset-trade/preview — cashCurrency · fundingCurrency 가 실리고 금액 칸은 그대로")
    void previewCarriesCurrencies() throws Exception {
        given(assetTradeService.previewTrade(any())).willReturn(new AssetTradeServiceDto.TradePreview(
                0L, null, -1_000L, 0L, 1_170_000L, "USD", "KRW"));

        mockMvc.perform(post("/api/v1/asset-trade/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetRowId\":11,\"tradeType\":\"BUY\",\"holdingKey\":\"AAPL\","
                                + "\"linked\":true,\"quantity\":10,\"amount\":1300000,\"fee\":0,"
                                + "\"settlementAssetRowId\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cashDelta").value(-1000))
                .andExpect(jsonPath("$.data.cashAfter").value(0))
                .andExpect(jsonPath("$.data.fundingAmount").value(1170000))
                .andExpect(jsonPath("$.data.cashCurrency").value("USD"))
                .andExpect(jsonPath("$.data.fundingCurrency").value("KRW"));
    }

    @Test
    @DisplayName("POST /asset-trade/preview — 결제 계좌를 안 골랐으면 fundingCurrency 없음")
    void previewWithoutSettlement() throws Exception {
        given(assetTradeService.previewTrade(any())).willReturn(new AssetTradeServiceDto.TradePreview(
                0L, null, -7_000_000L, 3_000_000L, 0L, "KRW", null));

        mockMvc.perform(post("/api/v1/asset-trade/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetRowId\":11,\"tradeType\":\"BUY\",\"holdingKey\":\"005930\","
                                + "\"linked\":true,\"quantity\":100,\"amount\":7000000,\"fee\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cashCurrency").value("KRW"))
                .andExpect(jsonPath("$.data.fundingCurrency").doesNotExist());
    }
}
