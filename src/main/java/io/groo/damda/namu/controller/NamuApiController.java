package io.groo.damda.namu.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.core.controller.dto.CursorResponse;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.namu.dto.NamuAccountDetailDto;
import io.groo.damda.namu.dto.NamuAccountDto;
import io.groo.damda.namu.dto.NamuQuoteDto;
import io.groo.damda.namu.service.NamuAccountDetailQueryService;
import io.groo.damda.namu.service.NamuMarketQueryService;
import io.groo.damda.namu.service.NamuQueryService;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import io.groo.damda.securities.service.dto.CandlePage;
import io.groo.damda.securities.service.dto.CandleQuery;
import io.groo.damda.securities.service.dto.InstrumentRef;
import io.groo.damda.securities.service.dto.InvestorTradingDay;
import io.groo.damda.securities.service.dto.PriceQuote;
import io.groo.damda.securities.service.dto.SecuritiesCandle;
import io.groo.damda.securities.type.CandleInterval;
import io.groo.damda.toss.dto.TossMarketDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * 나무증권 Open API 조회 프록시.
 *
 * <p>토스({@code /api/v1/toss/**})와 <b>일부러 나눠 둔다.</b> 증권사마다 주는 데이터가 달라
 * (채권 · 금현물은 나무에만 있다) 한 경로로 합치면 한쪽이 "미지원" 이 된다. 호가 · 체결 · 투자자별 ·
 * 경고 · 기본 정보 · 장 상태는 두 증권사 모두 주고, 증권 화면은 <b>탭 하나가 그 증권사 한 벌</b>이라
 * 시세 · 차트 · 환율도 이 경로({@code /namu/prices} · {@code /namu/candles} · {@code /namu/exchange-rate})로
 * 그 탭의 증권사 키를 쓴다 — 공개 {@code /api/v1/securities/**} 는 사용자의 <i>기본</i> 증권사로 가므로
 * 그대로 두고 나무 탭은 쓰지 않는다.
 *
 * <p>활성 구독(SECURITIES) 필요 — {@code FeatureGateInterceptor} 가 게이트한다.
 * 1차 범위는 읽기 전용이다. 운영 환경에서는 실제 체결이 나가는 도메인을 그대로 쓰므로
 * 주문 계열은 붙이지 않는다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/namu")
@RequiredArgsConstructor
public class NamuApiController {

    /** {@code /securities/prices} 와 같은 상한. */
    private static final int MAX_SYMBOLS = 50;
    private static final int MAX_CANDLE_SIZE = 200;

    private final NamuQueryService namuQueryService;
    private final NamuMarketQueryService namuMarketQueryService;
    private final NamuAccountDetailQueryService namuAccountDetailQueryService;

    /**
     * 국내주식 현재가. (옛 클라이언트용 — 그대로 둔다. 화면은 {@code /kr/quote}.)
     *
     * @param marketCode 나무 <b>거래소</b> 코드 — {@code KRX}(기본) · {@code NXT} · {@code UNT}.
     *                   <b>{@code StockMarket.marketCode}({@code KOSPI}·{@code NAS} …)와 이름은 같지만
     *                   다른 어휘다.</b> 여기에 {@code KOSPI} 를 태우면 나무가 모르는 값이라
     *                   400 으로 거절한다 — 예전에는 그 종목만 조용히 빈 응답이 됐다.
     *                   이름을 안 바꾼 이유는 이미 나간 클라이언트가 이 파라미터로 부르고 있어서다
     */
    @GetMapping("/kr/price")
    public ApiResponse<PriceQuote> getKrPrice(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbol,
            @RequestParam(required = false)
            @Schema(allowableValues = {"KRX", "NXT", "UNT"}, defaultValue = "KRX") String marketCode) {
        return ApiResponse.success(namuQueryService.getKrPrice(loginUser.getRowId(), symbol, marketCode));
    }

    /** 해외주식 현재가. (옛 클라이언트용 — 그대로 둔다. 화면은 {@code /gb/quote}.) */
    @GetMapping("/gb/price")
    public ApiResponse<PriceQuote> getGbPrice(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbol) {
        return ApiResponse.success(namuQueryService.getGbPrice(loginUser.getRowId(), symbol));
    }

    /**
     * 본인 계좌 목록. 잔고 조회의 계좌번호를 여기서 얻는다.
     *
     * <p><b>거르지 않고 전부 준다.</b> 다만 계좌구분(운영 01·02 / 모의투자 03)이 그 계좌를 쓸 수
     * 있는 도메인을 정하므로, 현재 연동 환경에서 잔고 조회에 쓸 수 있는 계좌만 {@code usable=true}
     * 로 표시된다. 화면은 그걸로 고르면 된다.
     */
    @GetMapping("/accounts")
    public ApiResponse<List<NamuAccountDto.Account>> getAccounts(@LoginUser UserPrincipal loginUser) {
        return ApiResponse.success(namuQueryService.getAccounts(loginUser.getRowId()));
    }

    /**
     * 보유 종목. 국내와 해외는 나무 쪽 엔드포인트·필드명이 다르지만 응답은 한 모양이다.
     *
     * @param currency  KRW 면 국내, USD 면 해외. 기본 KRW.
     *                  <b>해외는 미국만 지원한다</b> — 나무 잔고 조회가 거래국가를 하나만 받는데
     *                  우리는 미국으로 고정했다. JPY·HKD·CNY 를 넘기면 400 이다(미국 계좌에서
     *                  엔화 종목을 찾는 셈이라, 그냥 태우면 업스트림이 조용히 0건을 준다)
     * @param accountNo 미지정이면 현재 환경에서 쓸 수 있는 첫 계좌를 자동으로 고른다.
     *                  지정하면 본인 계좌인지 + 현재 환경에서 쓸 수 있는 구분인지 검증한다
     *                  ({@code usable=false} 인 계좌를 넘기면 400) — 그냥 태우면 업스트림이
     *                  계좌번호 오류로 거절해 502 만 남고 원인을 알 수 없다
     */
    @GetMapping("/holdings")
    public ApiResponse<NamuAccountDto.Holdings> getHoldings(
            @LoginUser UserPrincipal loginUser,
            @RequestParam(required = false) String accountNo,
            @RequestParam(defaultValue = "KRW") String currency) {
        return ApiResponse.success(
            namuQueryService.getHoldings(loginUser.getRowId(), accountNo, currency));
    }

    // === 13b-0 · 종목 상세 ===

    /** 국내 종목 상세 시세 — 현재가 · 시고저 · 호가 · 경고 · 장 상태 · 기본 정보 · 투자 지표 · ETF. */
    @GetMapping("/kr/quote")
    public ApiResponse<NamuQuoteDto.Detail> getKrQuote(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbol) {
        return ApiResponse.success(namuMarketQueryService.getKrQuote(loginUser.getRowId(), symbol));
    }

    /** 미국 종목 상세 시세 — 시간외(프리 · 애프터 · 주간거래) 포함. */
    @GetMapping("/gb/quote")
    public ApiResponse<NamuQuoteDto.Detail> getGbQuote(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbol) {
        return ApiResponse.success(namuMarketQueryService.getGbQuote(loginUser.getRowId(), symbol));
    }

    /** 국내 최근 체결 — 토스 {@code /toss/trades} 와 같은 모양, 최신이 먼저. */
    @GetMapping("/kr/executions")
    public ApiResponse<List<TossMarketDto.Trade>> getKrExecutions(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbol,
            @RequestParam(required = false, defaultValue = "20") Integer count) {
        return ApiResponse.success(namuMarketQueryService.getKrExecutions(loginUser.getRowId(), symbol, count));
    }

    /** 미국 최근 체결 — 시각은 거래소 현지 시각으로 읽는다. */
    @GetMapping("/gb/executions")
    public ApiResponse<List<TossMarketDto.Trade>> getGbExecutions(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbol,
            @RequestParam(required = false, defaultValue = "20") Integer count) {
        return ApiResponse.success(namuMarketQueryService.getGbExecutions(loginUser.getRowId(), symbol, count));
    }

    /** 국내 투자자별 매매동향(순매수 주). 미국 종목이면 400 이 아니라 빈 목록. */
    @GetMapping("/kr/investors")
    public ApiResponse<List<InvestorTradingDay>> getKrInvestors(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbol,
            @RequestParam(required = false, defaultValue = "5") Integer count) {
        return ApiResponse.success(namuMarketQueryService.getKrInvestors(loginUser.getRowId(), symbol, count));
    }

    /** ETF 구성 종목 — 비중 큰 순. */
    @GetMapping("/kr/etf/components")
    public ApiResponse<List<NamuQuoteDto.EtfComponent>> getEtfComponents(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbol) {
        return ApiResponse.success(namuMarketQueryService.getEtfComponents(loginUser.getRowId(), symbol));
    }

    /** 국내 시간외 단일가 — 체결가가 0 이거나 비면 모든 칸 null. */
    @GetMapping("/kr/after-hours")
    public ApiResponse<NamuQuoteDto.AfterHours> getKrAfterHours(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbol) {
        return ApiResponse.success(namuMarketQueryService.getKrAfterHours(loginUser.getRowId(), symbol));
    }

    /** 해외 증거금(USD) — 화면 줄 이름은 "예수금 · 달러"(이 응답에는 주문 가능 칸이 없다). */
    @GetMapping("/margin")
    public ApiResponse<NamuQuoteDto.Margin> getMargin(@LoginUser UserPrincipal loginUser) {
        return ApiResponse.success(namuMarketQueryService.getMargin(loginUser.getRowId()));
    }

    // === 13b-0 · 그 탭 증권사 경로 ===

    /**
     * 관심 시세 + 장 상태 — {@code /securities/prices} 와 같은 상한(50) · 캐시(20초) · 예산이고
     * 항상 나무 키로 부른다. 응답은 나무 전용 {@code ListQuote}(공개 {@code PriceQuote} 에 칸을 더하지 않는다).
     */
    @GetMapping("/prices")
    public ApiResponse<List<NamuQuoteDto.ListQuote>> getPrices(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbols) {
        List<String> requested = Arrays.stream(symbols.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .toList();
        if (requested.isEmpty()) {
            return ApiResponse.success(List.of());
        }
        // 자른 사실을 로그에 남긴다 — 조용히 limit 하면 클라이언트가 "시세 미확보" 로 읽는다.
        List<String> capped = requested.size() > MAX_SYMBOLS ? requested.subList(0, MAX_SYMBOLS) : requested;
        if (capped.size() < requested.size()) {
            log.warn("나무 시세 조회 상한 초과 - 뒤쪽 종목이 잘린다: userRowId={}, 요청={}, 조회={}, 버림={}",
                loginUser.getRowId(), requested.size(), capped.size(),
                requested.subList(MAX_SYMBOLS, requested.size()));
        }
        List<InstrumentRef> instruments = capped.stream().map(InstrumentRef::of).toList();
        return ApiResponse.success(namuQueryService.getListQuotes(loginUser.getRowId(), instruments));
    }

    /**
     * 캔들 — {@code /securities/candles} 와 같은 모양이지만 기본 증권사를 따르지 않고 나무를 바로 부른다.
     * 주봉({@code interval=1w})은 여기서만 받는다(N1-가 "5년").
     */
    @GetMapping("/candles")
    public ApiResponse<CursorResponse<SecuritiesCandle>> getCandles(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor) {
        String requested = symbol == null ? "" : symbol.trim();
        if (requested.isEmpty()) {
            throw new InvalidValueException(DeskErrorCode.SECURITIES_SYMBOL_INVALID);
        }
        int pageSize = (size == null || size <= 0) ? MAX_CANDLE_SIZE : Math.min(size, MAX_CANDLE_SIZE);
        CandleQuery query = new CandleQuery(requested, CandleInterval.from(interval), pageSize,
            cursor == null || cursor.isBlank() ? null : cursor.trim(), null);
        CandlePage page = namuQueryService.getCandles(loginUser.getRowId(), query);
        return ApiResponse.success(
            CursorResponse.of(page.candles(), pageSize, page.hasNext(), page.nextCursor()));
    }

    /** 원화 환산 환율(USD) — 잔고 → 현재가 순, 사용자별 10분 캐시(지금 그대로). */
    @GetMapping("/exchange-rate")
    public ApiResponse<NamuQuoteDto.ExchangeRate> getExchangeRate(
            @LoginUser UserPrincipal loginUser,
            @RequestParam(defaultValue = "USD") String base) {
        BigDecimal rate = namuQueryService.getFxRate(loginUser.getRowId(), base);
        return ApiResponse.success(new NamuQuoteDto.ExchangeRate(base, "KRW", rate));
    }

    // === 13b-0 · 계좌 자세히 (열 때만 부른다) ===

    /** 국내 계좌 자산(원화). */
    @GetMapping("/account/assets")
    public ApiResponse<NamuAccountDetailDto.AssetStatus> getAssets(@LoginUser UserPrincipal loginUser) {
        return ApiResponse.success(namuAccountDetailQueryService.getAssetStatus(loginUser.getRowId()));
    }

    /** 기간 실현손익 — 국내 종목별 + 미국 날짜별. 한쪽 실패는 그쪽만 null. */
    @GetMapping("/account/realized-pnl")
    public ApiResponse<NamuAccountDetailDto.RealizedPnl> getRealizedPnl(
            @LoginUser UserPrincipal loginUser,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(namuAccountDetailQueryService.getRealizedPnl(loginUser.getRowId(), from, to));
    }

    /**
     * 권리(배당). {@code kind=scheduled} 예정 · {@code kind=held} 지난 것({@code from} 부터,
     * 안 주면 올해 1월 1일). 지난 것은 화면이 고를 때만 부른다.
     */
    @GetMapping("/account/rights")
    public ApiResponse<NamuAccountDetailDto.Rights> getRights(
            @LoginUser UserPrincipal loginUser,
            @RequestParam(defaultValue = "scheduled") String kind,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from) {
        return ApiResponse.success(namuAccountDetailQueryService.getRights(loginUser.getRowId(), kind, from));
    }
}
