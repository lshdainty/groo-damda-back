package io.groo.damda.asset.service.dto;

import io.groo.damda.asset.domain.AssetTrade;
import io.groo.damda.asset.type.HoldingType;
import io.groo.damda.asset.type.TradeType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class AssetTradeServiceDto {
    private AssetTradeServiceDto() {}

    /**
     * 매수·매도 입력.
     *
     * <p>{@code amount} 는 수수료를 뺀 순수 거래대금이다. 수수료는 매수면 원가에 더해지고
     * 매도면 대금에서 빠진다 — 어느 쪽이든 예수금에서 실제로 나간다.
     */
    public record CreateTradeCommand(
        Long userRowId,
        Long assetRowId,
        TradeType tradeType,
        HoldingType holdingType,
        /** 종목 식별자 — 연동은 토스 종목코드, 미연동은 항목명. */
        /** 보유 행 아이디 — 있으면 이름 대신 이걸로 묶는다(이름을 고쳐도 안 끊긴다). */
        Long holdingRowId,
        String holdingKey,
        Boolean linked,
        BigDecimal quantity,
        Long amount,
        Long fee,
        LocalDateTime tradeDate,
        String description,
        /**
         * 결제 계좌 — 지정하면 증권계좌 예수금 대신 이 계좌에서 나가고 들어온다.
         *
         * <p>예수금을 따로 관리하지 않는 사용자를 위한 길이다. 증권계좌에 돈을 옮겨 두는
         * 이체를 먼저 적지 않아도 매수 한 번으로 끝난다. 통장이 마이너스가 되는 건 막지 않는다 —
         * 초기 잔액을 안 채우고 쓰는 가계부에선 정상적인 상태다.
         */
        Long settlementAssetRowId
    ) {}

    public record TradeInfo(
        Long rowId,
        Long assetRowId,
        TradeType tradeType,
        HoldingType holdingType,
        String holdingKey,
        boolean linked,
        BigDecimal quantity,
        Long amount,
        Long fee,
        Long realizedPl,
        LocalDateTime tradeDate,
        String description,
        Long settlementAssetRowId
    ) {
        public static TradeInfo from(AssetTrade t) {
            return new TradeInfo(
                t.getRowId(), t.getAsset().getRowId(), t.getTradeType(), t.getHoldingType(),
                t.getHoldingKey(), t.getLinked() == io.groo.core.type.YNType.Y,
                t.getQuantity(), t.getAmount(), t.getFee(), t.getRealizedPl(),
                t.getTradeDate(), t.getDescription(),
                t.getSettlementAsset() != null ? t.getSettlementAsset().getRowId() : null);
        }
    }

    /**
     * 매매 미리보기 — 저장하면 어떤 숫자가 남는지 <b>서버가</b> 계산해 돌려준다.
     *
     * <p>클라이언트가 double 로 흉내 내면 어긋난다. 서버는 BigDecimal 이고 전량 매도면
     * 비율 계산을 아예 건너뛰는데(반올림을 안 타게), 그 분기가 화면에 없었다.
     * 본 숫자와 남는 숫자가 다르면 신뢰가 깎인다.
     */
    public record TradePreview(
        /** 판 만큼의 원가 (매도 전용, 매수면 0) */
        Long soldCost,
        /** 실현손익 (매도 전용, 매수면 null) */
        Long realizedPl,
        /** 예수금 변동(<b>자산 통화</b>) — 매수 −round((대금+수수료) ÷ 환율), 매도 +round((대금−수수료) ÷ 환율). 원화 계좌는 원화 그대로 */
        Long cashDelta,
        /** 거래 후 예수금(자산 통화) */
        Long cashAfter,
        /** 결제 계좌에서 나갈 금액(<b>결제 계좌 통화</b> — 원화 통장이면 원화, 없으면 0) */
        Long fundingAmount,
        /** cashDelta · cashAfter 의 통화 — 투자 계좌 통화(예: USD). soldCost · realizedPl 은 늘 원화 */
        String cashCurrency,
        /** fundingAmount 의 통화 — 결제 계좌 통화. 결제 계좌를 안 골랐으면 null */
        String fundingCurrency
    ) {}
}
