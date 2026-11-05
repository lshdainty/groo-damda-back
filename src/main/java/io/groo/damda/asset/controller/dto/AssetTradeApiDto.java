package io.groo.damda.asset.controller.dto;

import io.groo.damda.asset.service.dto.AssetTradeServiceDto;
import io.groo.damda.asset.type.HoldingType;
import io.groo.damda.asset.type.TradeType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class AssetTradeApiDto {
    private AssetTradeApiDto() {}

    /**
     * 매수·매도 입력.
     *
     * <p>{@code amount} 는 수수료를 뺀 순수 거래대금이다. 수수료는 매수면 취득원가에 들어가고
     * 매도면 대금에서 빠진다 — 어느 쪽이든 예수금에서 실제로 나간다.
     */
    public record CreateTradeRequest(
        Long assetRowId,
        TradeType tradeType,
        HoldingType holdingType,
        /** 보유 행 아이디 — 보내면 이름 대신 이걸로 묶어 종목명을 바꿔도 이력이 안 끊긴다. */
        Long holdingRowId,
        /** 종목 식별자 — 연동은 토스 종목코드, 미연동은 항목명. */
        String holdingKey,
        Boolean linked,
        BigDecimal quantity,
        Long amount,
        Long fee,
        LocalDateTime tradeDate,
        String description,
        /**
         * 결제 계좌 — 지정하면 증권계좌 예수금 대신 이 계좌에서 나가고 들어온다.
         * 예수금을 따로 관리하지 않는 사용자를 위한 길이라, 통장이 마이너스가 되는 건 막지 않는다.
         */
        Long settlementAssetRowId
    ) {}

    public record TradeResponse(
        Long rowId,
        Long assetRowId,
        TradeType tradeType,
        HoldingType holdingType,
        String holdingKey,
        boolean linked,
        /** 소수 정밀도 보존 — 금 3.75g·코인 0.05 개가 숫자로 나가면 자릿수가 흔들린다. */
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
        @Schema(type = "string", format = "decimal", example = "3.75")
        BigDecimal quantity,
        Long amount,
        Long fee,
        /** 실현손익 (매도 전용). 이익 양수 / 손실 음수. */
        Long realizedPl,
        LocalDateTime tradeDate,
        String description,
        Long settlementAssetRowId
    ) {
        public static TradeResponse from(AssetTradeServiceDto.TradeInfo t) {
            return new TradeResponse(t.rowId(), t.assetRowId(), t.tradeType(), t.holdingType(),
                t.holdingKey(), t.linked(), t.quantity(), t.amount(), t.fee(),
                t.realizedPl(), t.tradeDate(), t.description(), t.settlementAssetRowId());
        }

        public static List<TradeResponse> from(List<AssetTradeServiceDto.TradeInfo> list) {
            return list.stream().map(TradeResponse::from).toList();
        }
    }

    /** 매매 미리보기 응답 — 저장하면 남을 숫자를 서버가 계산해 준다. */
    public record TradePreviewResponse(
        Long soldCost,
        Long realizedPl,
        Long cashDelta,
        Long cashAfter,
        Long fundingAmount,
        /** cashDelta · cashAfter 의 통화(투자 계좌 통화). soldCost · realizedPl 은 원화 */
        String cashCurrency,
        /** fundingAmount 의 통화(결제 계좌 통화). 결제 계좌를 안 골랐으면 null */
        String fundingCurrency
    ) {
        public static TradePreviewResponse from(AssetTradeServiceDto.TradePreview p) {
            return new TradePreviewResponse(
                p.soldCost(), p.realizedPl(), p.cashDelta(), p.cashAfter(), p.fundingAmount(),
                p.cashCurrency(), p.fundingCurrency());
        }
    }
}
