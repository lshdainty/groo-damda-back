package io.groo.damda.asset.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.asset.controller.dto.AssetTradeApiDto;
import io.groo.damda.asset.service.AssetTradeService;
import io.groo.damda.asset.service.dto.AssetTradeServiceDto;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 투자 자산의 매수·매도. 예수금·보유 수량·원가·실현손익이 함께 움직인다. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AssetTradeApiController {
    private final AssetTradeService assetTradeService;

    @PostMapping("/asset-trade")
    public ApiResponse<AssetTradeApiDto.TradeResponse> createTrade(
            @LoginUser UserPrincipal loginUser,
            @RequestBody AssetTradeApiDto.CreateTradeRequest request) {
        AssetTradeServiceDto.TradeInfo info = assetTradeService.createTrade(
            new AssetTradeServiceDto.CreateTradeCommand(
                loginUser.getRowId(), request.assetRowId(), request.tradeType(),
                request.holdingType(), request.holdingRowId(), request.holdingKey(), request.linked(),
                request.quantity(), request.amount(), request.fee(),
                request.tradeDate(), request.description(), request.settlementAssetRowId()));
        return ApiResponse.success(AssetTradeApiDto.TradeResponse.from(info));
    }

    /**
     * 매매 미리보기 — 저장하면 어떤 숫자가 남는지 서버가 계산한다.
     *
     * <p>클라이언트가 double 로 흉내 내면 어긋난다(전량 매도 분기가 화면에 없었다).
     * 본 숫자와 남는 숫자가 달라지지 않도록 같은 코드로 계산해 돌려준다.
     */
    @PostMapping("/asset-trade/preview")
    public ApiResponse<AssetTradeApiDto.TradePreviewResponse> previewTrade(
            @LoginUser UserPrincipal loginUser,
            @RequestBody AssetTradeApiDto.CreateTradeRequest request) {
        AssetTradeServiceDto.TradePreview preview = assetTradeService.previewTrade(
            new AssetTradeServiceDto.CreateTradeCommand(
                loginUser.getRowId(), request.assetRowId(), request.tradeType(),
                request.holdingType(), request.holdingRowId(), request.holdingKey(), request.linked(),
                request.quantity(), request.amount(), request.fee(),
                request.tradeDate(), request.description(), request.settlementAssetRowId()));
        return ApiResponse.success(AssetTradeApiDto.TradePreviewResponse.from(preview));
    }

    @GetMapping("/asset-trades")
    public ApiResponse<List<AssetTradeApiDto.TradeResponse>> getTrades(
            @LoginUser UserPrincipal loginUser,
            @RequestParam Long assetRowId) {
        return ApiResponse.success(AssetTradeApiDto.TradeResponse.from(
            assetTradeService.getTrades(assetRowId, loginUser.getRowId())));
    }

    @DeleteMapping("/asset-trade/{tradeRowId}")
    public ApiResponse<Void> deleteTrade(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long tradeRowId) {
        assetTradeService.deleteTrade(tradeRowId, loginUser.getRowId());
        return ApiResponse.success(null);
    }
}
