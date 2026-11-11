package io.groo.damda.toss.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.core.controller.dto.CursorResponse;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import io.groo.damda.securities.service.dto.InvestorTradingDay;
import io.groo.damda.toss.dto.TossAccountDto;
import io.groo.damda.toss.dto.TossIndicatorDto;
import io.groo.damda.toss.dto.TossMarketDto;
import io.groo.damda.toss.dto.TossMarketInfoDto;
import io.groo.damda.toss.dto.TossRankingDto;
import io.groo.damda.toss.dto.TossStockDto;
import io.groo.damda.toss.service.TossQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 토스증권 Open API 조회 프록시 컨트롤러.<br>
 * 모든 엔드포인트는 인증된 사용자만 접근 가능하며(SecurityConfig 의 {@code anyRequest().authenticated()}),
 * 1차 범위로 <b>읽기 전용</b> 시세·종목·시장정보·계좌·보유자산 조회만 노출한다. 주문 기능은 제외.
 */
@RestController
@RequestMapping("/api/v1/toss")
@RequiredArgsConstructor
public class TossApiController {

    private final TossQueryService tossQueryService;

    // === Market Data (시세) ===

    @GetMapping("/orderbook")
    public ApiResponse<TossMarketDto.OrderbookResponse> getOrderbook(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbol) {
        return ApiResponse.success(tossQueryService.getOrderbook(loginUser.getRowId(), symbol));
    }

    @GetMapping("/prices")
    public ApiResponse<List<TossMarketDto.PriceResponse>> getPrices(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbols) {
        return ApiResponse.success(tossQueryService.getPrices(loginUser.getRowId(), symbols));
    }

    @GetMapping("/trades")
    public ApiResponse<List<TossMarketDto.Trade>> getTrades(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbol,
            @RequestParam(required = false) Integer count) {
        return ApiResponse.success(tossQueryService.getTrades(loginUser.getRowId(), symbol, count));
    }

    @GetMapping("/price-limits")
    public ApiResponse<TossMarketDto.PriceLimitResponse> getPriceLimits(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbol) {
        return ApiResponse.success(tossQueryService.getPriceLimits(loginUser.getRowId(), symbol));
    }

    /**
     * 캔들 차트 조회. 커서 기반 단일 페이지(요청당 {@code size}개, 토스 상한 200).
     * 더 과거가 있으면 응답 {@code meta.nextCursor}를 다음 요청 {@code cursor}로 전달한다(시간 역방향 lazy-load).
     */
    @GetMapping("/candles")
    public ApiResponse<CursorResponse<TossMarketDto.Candle>> getCandles(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Boolean adjusted) {
        return ApiResponse.success(tossQueryService.getCandles(loginUser.getRowId(), symbol, interval, size, cursor, adjusted));
    }

    // === Stock Info (종목 정보) ===

    @GetMapping("/stocks")
    public ApiResponse<List<TossStockDto.StockInfo>> getStocks(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbols) {
        return ApiResponse.success(tossQueryService.getStocks(loginUser.getRowId(), symbols));
    }

    @GetMapping("/stocks/{symbol}/warnings")
    public ApiResponse<List<TossStockDto.StockWarning>> getStockWarnings(
            @LoginUser UserPrincipal loginUser,
            @PathVariable String symbol) {
        return ApiResponse.success(tossQueryService.getStockWarnings(loginUser.getRowId(), symbol));
    }

    /**
     * 국내 종목 투자자별 매매동향(최신순 · 순매수 주) — 나무 {@code /namu/kr/investors} 와 같은 모양.
     * 당일 잠정 기록의 개인 칸은 null 이다.
     */
    @GetMapping("/stocks/{symbol}/investor-trading")
    public ApiResponse<List<InvestorTradingDay>> getInvestorTrading(
            @LoginUser UserPrincipal loginUser,
            @PathVariable String symbol,
            @RequestParam(required = false, defaultValue = "5") Integer count) {
        return ApiResponse.success(tossQueryService.getInvestorTrading(loginUser.getRowId(), symbol, count));
    }

    // === Rankings (주식 랭킹) ===

    /**
     * 주식 랭킹 조회 (상위 100위, 등락률·거래량·거래대금 포함).
     * type: MARKET_TRADING_AMOUNT|MARKET_TRADING_VOLUME|TOP_GAINERS|TOP_LOSERS|TOSS_SECURITIES_*
     */
    @GetMapping("/rankings")
    public ApiResponse<TossRankingDto.RankingResponse> getRankings(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String type,
            @RequestParam String marketCountry,
            @RequestParam String duration,
            @RequestParam(required = false) Boolean excludeInvestmentCaution,
            @RequestParam(required = false) Integer count) {
        return ApiResponse.success(tossQueryService.getRankings(
                loginUser.getRowId(), type, marketCountry, duration, excludeInvestmentCaution, count));
    }

    // === Market Indicators (시장 지표: 국내 지수·국채) ===

    /** 시장 지표 현재가 조회 (토스 카탈로그 8종: KOSPI, KOSDAQ, KR_BOND_2Y~30Y) */
    @GetMapping("/market-indicators/prices")
    public ApiResponse<List<TossIndicatorDto.IndicatorPriceResponse>> getMarketIndicatorPrices(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbols) {
        return ApiResponse.success(tossQueryService.getMarketIndicatorPrices(loginUser.getRowId(), symbols));
    }

    /** 시장 지표 캔들 조회. 페이지네이션 규약은 /candles 와 동일 */
    @GetMapping("/market-indicators/{symbol}/candles")
    public ApiResponse<CursorResponse<TossIndicatorDto.IndicatorCandle>> getMarketIndicatorCandles(
            @LoginUser UserPrincipal loginUser,
            @PathVariable String symbol,
            @RequestParam String interval,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor) {
        return ApiResponse.success(tossQueryService.getMarketIndicatorCandles(
                loginUser.getRowId(), symbol, interval, size, cursor));
    }

    // === Market Info (환율·장 일정) ===

    @GetMapping("/exchange-rate")
    public ApiResponse<TossMarketInfoDto.ExchangeRateResponse> getExchangeRate(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String baseCurrency,
            @RequestParam String quoteCurrency,
            @RequestParam(required = false) String dateTime) {
        return ApiResponse.success(tossQueryService.getExchangeRate(loginUser.getRowId(), baseCurrency, quoteCurrency, dateTime));
    }

    @GetMapping("/market-calendar/KR")
    public ApiResponse<TossMarketInfoDto.KrMarketCalendarResponse> getKrMarketCalendar(
            @LoginUser UserPrincipal loginUser,
            @RequestParam(required = false) String date) {
        return ApiResponse.success(tossQueryService.getKrMarketCalendar(loginUser.getRowId(), date));
    }

    @GetMapping("/market-calendar/US")
    public ApiResponse<TossMarketInfoDto.UsMarketCalendarResponse> getUsMarketCalendar(
            @LoginUser UserPrincipal loginUser,
            @RequestParam(required = false) String date) {
        return ApiResponse.success(tossQueryService.getUsMarketCalendar(loginUser.getRowId(), date));
    }

    // === Account / Asset (계좌·보유자산) ===

    @GetMapping("/accounts")
    public ApiResponse<List<TossAccountDto.Account>> getAccounts(
            @LoginUser UserPrincipal loginUser) {
        return ApiResponse.success(tossQueryService.getAccounts(loginUser.getRowId()));
    }

    @GetMapping("/holdings")
    public ApiResponse<TossAccountDto.HoldingsOverview> getHoldings(
            @LoginUser UserPrincipal loginUser,
            @RequestParam Long accountSeq,
            @RequestParam(required = false) String symbol) {
        return ApiResponse.success(tossQueryService.getHoldings(loginUser.getRowId(), accountSeq, symbol));
    }

    /** 매수 가능 금액(현금 기반) — {@code currency} KRW | USD. 계좌는 보유 조회와 같은 {@code accountSeq}. */
    @GetMapping("/buying-power")
    public ApiResponse<TossAccountDto.BuyingPower> getBuyingPower(
            @LoginUser UserPrincipal loginUser,
            @RequestParam Long accountSeq,
            @RequestParam String currency) {
        return ApiResponse.success(tossQueryService.getBuyingPower(loginUser.getRowId(), accountSeq, currency));
    }

    /** 이 계좌의 시장별 매매 수수료율. */
    @GetMapping("/commissions")
    public ApiResponse<List<TossAccountDto.Commission>> getCommissions(
            @LoginUser UserPrincipal loginUser,
            @RequestParam Long accountSeq) {
        return ApiResponse.success(tossQueryService.getCommissions(loginUser.getRowId(), accountSeq));
    }
}
