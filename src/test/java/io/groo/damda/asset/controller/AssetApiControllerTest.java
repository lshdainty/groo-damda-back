package io.groo.damda.asset.controller;

import io.groo.core.util.MessageResolver;
import io.groo.damda.asset.service.AssetService;
import io.groo.damda.common.config.web.WebConfig;
import io.groo.damda.security.filter.JwtAuthenticationFilter;
import io.groo.damda.security.resolver.LoginUserArgumentResolver;
import io.groo.damda.support.security.WithLoginUser;
import io.groo.damda.asset.service.dto.AssetServiceDto;
import io.groo.damda.asset.type.AssetType;
import io.groo.core.type.YNType;
import io.groo.damda.common.patch.Patch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asset API 슬라이스 테스트 — 보안 필터 제외 + {@link WithLoginUser} 로 로그인 사용자 위임 검증.
 */
@WebMvcTest(controllers = AssetApiController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, LoginUserArgumentResolver.class})
@ActiveProfiles("test")
@WithLoginUser(rowId = 1L)
class AssetApiControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AssetService assetService;
    @MockitoBean private MessageResolver messageResolver;

    @Test
    @DisplayName("GET /assets — 로그인 사용자로 목록 조회")
    void getAssets() throws Exception {
        given(assetService.getAssets(1L)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/assets"))
                .andExpect(status().isOk());

        verify(assetService).getAssets(1L);
    }

    @Test
    @DisplayName("DELETE /asset/{id} — id·로그인 사용자로 삭제 위임")
    void deleteAsset() throws Exception {
        mockMvc.perform(delete("/api/v1/asset/{id}", 100L))
                .andExpect(status().isOk());

        verify(assetService).deleteAsset(eq(100L), eq(1L));
    }

    @Test
    @DisplayName("GET /assets · /asset/{id} — 응답에 원화 합계 balanceInKrw 가 실리고 기존 칸은 그대로")
    void responseCarriesBalanceInKrw() throws Exception {
        AssetServiceDto.AssetInfo usd = sampleAsset().withBalanceInKrw(135_000L);
        given(assetService.getAssets(1L)).willReturn(List.of(usd));
        given(assetService.getAsset(100L, 1L)).willReturn(usd);

        mockMvc.perform(get("/api/v1/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assets[0].balanceInKrw").value(135000))
                .andExpect(jsonPath("$.data.assets[0].balance").value(50000))
                .andExpect(jsonPath("$.data.assets[0].cashBalance").value(50000))
                .andExpect(jsonPath("$.data.assets[0].holdingBalance").value(0));
        mockMvc.perform(get("/api/v1/asset/{id}", 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balanceInKrw").value(135000));
    }

    @Test
    @DisplayName("GET /asset-transfers · POST /asset-transfer — 응답에 받는 금액 toAmount 가 실리고(NULL 이면 null) 기존 칸은 그대로")
    void transferResponseCarriesToAmount() throws Exception {
        java.time.LocalDateTime at = java.time.LocalDateTime.of(2026, 11, 6, 10, 0);
        AssetServiceDto.TransferInfo cross = new AssetServiceDto.TransferInfo(
                7L, 1L, 10L, "통장", 11L, "달러 계좌", 1_300_000L, 0L, 0L, 1_300_000L,
                "환전", null, at, at, 1_000L, true);
        AssetServiceDto.TransferInfo same = new AssetServiceDto.TransferInfo(
                8L, 1L, 10L, "통장", 12L, "적금", 50_000L, 0L, 0L, 50_000L,
                "이체", null, at, at, null, true);
        given(assetService.getTransfers(eq(1L), any(), any())).willReturn(List.of(cross, same));
        given(assetService.createTransfer(any())).willReturn(cross);

        mockMvc.perform(get("/api/v1/asset-transfers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transfers[0].toAmount").value(1000))
                .andExpect(jsonPath("$.data.transfers[0].deletable").value(true))
                .andExpect(jsonPath("$.data.transfers[0].amount").value(1300000))
                .andExpect(jsonPath("$.data.transfers[0].principalAmount").value(1300000))
                .andExpect(jsonPath("$.data.transfers[1].toAmount").doesNotExist());
        mockMvc.perform(post("/api/v1/asset-transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAssetRowId\":10,\"toAssetRowId\":11,\"amount\":1300000,"
                                + "\"transferDate\":\"2026-11-06T10:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toAmount").value(1000));

        // 사용자 요청은 받는 금액을 싣지 않는다 — 서버가 정한다.
        ArgumentCaptor<AssetServiceDto.CreateTransferCommand> captor =
                ArgumentCaptor.forClass(AssetServiceDto.CreateTransferCommand.class);
        verify(assetService).createTransfer(captor.capture());
        assertThat(captor.getValue().toAmount()).isNull();
    }

    private AssetServiceDto.AssetInfo sampleAsset() {
        return new AssetServiceDto.AssetInfo(
                100L, 1L, "주거래", AssetType.BANK_ACCOUNT, 50_000L, 50_000L, 0L, "KRW",
                java.math.BigDecimal.ONE,        // exchangeRate
                null, null, null, 0, YNType.Y,   // color · institution · memo · sortOrder · isIncludedInTotal
                YNType.N,                        // isAmountHidden
                null, null, null, null,          // cardCatalog · creditLimit · paymentDay · paymentAssetRowId
                null, null, null,                // marketCode · symbol · quantity
                List.of(), null, null, null);    // holdings · createAt · modifyAt · monthlyUsedAmount
    }

    // === 잔액 상한·부호 (QA #17 #19) ===

    @Test
    @DisplayName("POST /asset — 잔액 99조는 400 (종전엔 그대로 저장)")
    void createRejectsOversizedBalance() throws Exception {
        mockMvc.perform(post("/api/v1/asset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetName\":\"통장\",\"assetType\":\"BANK_ACCOUNT\",\"balance\":99999999999999}"))
                .andExpect(status().isBadRequest());

        verify(assetService, never()).createAsset(any());
    }

    @Test
    @DisplayName("POST /asset — 잔액 1,000억(경계)은 통과")
    void createAcceptsBalanceAtLimit() throws Exception {
        given(assetService.createAsset(any())).willReturn(sampleAsset());

        mockMvc.perform(post("/api/v1/asset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetName\":\"통장\",\"assetType\":\"BANK_ACCOUNT\",\"balance\":100000000000}"))
                .andExpect(status().isOk());

        verify(assetService).createAsset(any());
    }

    @Test
    @DisplayName("POST /asset — 음수 잔액도 크기만 본다(마이너스 통장·대출을 400 으로 막으면 안 된다)")
    void createAcceptsNegativeBalanceWithinLimit() throws Exception {
        given(assetService.createAsset(any())).willReturn(sampleAsset());

        mockMvc.perform(post("/api/v1/asset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetName\":\"마통\",\"assetType\":\"BANK_ACCOUNT\",\"balance\":-50000}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/asset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetName\":\"마통\",\"assetType\":\"BANK_ACCOUNT\",\"balance\":-100000000001}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /asset — isOverdraft 를 그대로 서비스 커맨드에 실어 보낸다")
    void createPassesOverdraftFlag() throws Exception {
        given(assetService.createAsset(any())).willReturn(sampleAsset());

        mockMvc.perform(post("/api/v1/asset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetName\":\"마통\",\"assetType\":\"BANK_ACCOUNT\","
                                + "\"balance\":50000,\"isOverdraft\":true}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AssetServiceDto.CreateAssetCommand> c =
                ArgumentCaptor.forClass(AssetServiceDto.CreateAssetCommand.class);
        verify(assetService).createAsset(c.capture());
        assertThat(c.getValue().isOverdraft()).isTrue();
        // 부호는 서비스가 씌운다 — 컨트롤러는 사용자가 친 절대값을 그대로 넘긴다.
        assertThat(c.getValue().balance()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("POST /asset — isOverdraft 를 안 보내면 null 로 넘어간다(옛 클라이언트 폴백)")
    void createLeavesOverdraftNullForLegacyClient() throws Exception {
        given(assetService.createAsset(any())).willReturn(sampleAsset());

        mockMvc.perform(post("/api/v1/asset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetName\":\"통장\",\"assetType\":\"BANK_ACCOUNT\",\"balance\":-50000}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AssetServiceDto.CreateAssetCommand> c =
                ArgumentCaptor.forClass(AssetServiceDto.CreateAssetCommand.class);
        verify(assetService).createAsset(c.capture());
        assertThat(c.getValue().isOverdraft()).isNull();
    }

    @Test
    @DisplayName("PUT /asset/{id} — 잔액 99조는 400")
    void updateRejectsOversizedBalance() throws Exception {
        mockMvc.perform(put("/api/v1/asset/{id}", 100L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"balance\":99999999999999}"))
                .andExpect(status().isBadRequest());

        verify(assetService, never()).updateAsset(any(Long.class), any(Long.class), any());
    }

    @Test
    @DisplayName("POST /asset — 한도 1,000억 초과는 400, 음수 한도도 400")
    void creditLimitBounded() throws Exception {
        mockMvc.perform(post("/api/v1/asset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetName\":\"카드\",\"assetType\":\"CREDIT_CARD\",\"creditLimit\":100000000001}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/asset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetName\":\"카드\",\"assetType\":\"CREDIT_CARD\",\"creditLimit\":-1}"))
                .andExpect(status().isBadRequest());

        verify(assetService, never()).createAsset(any());
    }

    // === 별칭 길이 (QA #65) ===

    @Test
    @DisplayName("POST /asset — 별칭 31자는 400 (종전엔 그대로 저장)")
    void createRejectsOversizedAssetName() throws Exception {
        mockMvc.perform(post("/api/v1/asset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetName\":\"" + "가".repeat(31) + "\",\"assetType\":\"BANK_ACCOUNT\"}"))
                .andExpect(status().isBadRequest());

        verify(assetService, never()).createAsset(any());
    }

    @Test
    @DisplayName("POST /asset — 별칭 30자(경계)는 통과")
    void createAcceptsAssetNameAtLimit() throws Exception {
        given(assetService.createAsset(any())).willReturn(sampleAsset());

        mockMvc.perform(post("/api/v1/asset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetName\":\"" + "가".repeat(30) + "\",\"assetType\":\"BANK_ACCOUNT\"}"))
                .andExpect(status().isOk());

        verify(assetService).createAsset(any());
    }

    @Test
    @DisplayName("PUT /asset/{id} — 별칭 31자는 400")
    void updateRejectsOversizedAssetName() throws Exception {
        mockMvc.perform(put("/api/v1/asset/{id}", 100L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetName\":\"" + "가".repeat(31) + "\"}"))
                .andExpect(status().isBadRequest());

        verify(assetService, never()).updateAsset(any(Long.class), any(Long.class), any());
    }

    @Test
    @DisplayName("PUT /asset/{id} — 별칭을 안 보내면 검증 대상이 아니다(이름 무변경 수정)")
    void updateWithoutAssetNameStillPasses() throws Exception {
        given(assetService.updateAsset(any(Long.class), any(Long.class), any())).willReturn(sampleAsset());

        mockMvc.perform(put("/api/v1/asset/{id}", 100L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"balance\":1000}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT /asset/{id} — 이월 금액은 carryoverAmount 키로 넘긴다(D7), 응답에 카드 상태가 실린다")
    void updateCarriesCarryoverAmount() throws Exception {
        given(assetService.updateAsset(any(Long.class), any(Long.class), any())).willReturn(
                sampleAsset().withCardState(70_000L, true, LocalDate.of(2026, 8, 31),
                        LocalDate.of(2026, 9, 25)));

        mockMvc.perform(put("/api/v1/asset/{id}", 100L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carryoverAmount\":70000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.carryoverAmount").value(70000))
                .andExpect(jsonPath("$.data.carryoverLocked").value(true))
                .andExpect(jsonPath("$.data.cardClosedThrough").value("2026-08-31"))
                .andExpect(jsonPath("$.data.nextPaymentDate").value("2026-09-25"));

        ArgumentCaptor<AssetServiceDto.UpdateAssetCommand> c =
                ArgumentCaptor.forClass(AssetServiceDto.UpdateAssetCommand.class);
        verify(assetService).updateAsset(eq(100L), eq(1L), c.capture());
        assertThat(c.getValue().carryoverAmount()).isEqualTo(Patch.set(70_000L));
        assertThat(c.getValue().balance()).isEqualTo(Patch.absent());
    }

    @Test
    @DisplayName("PUT /asset/{id} — 음수 이월 금액은 400")
    void negativeCarryoverRejected() throws Exception {
        mockMvc.perform(put("/api/v1/asset/{id}", 100L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carryoverAmount\":-1}"))
                .andExpect(status().isBadRequest());
    }
}
