package io.groo.damda.securities.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.core.controller.dto.CursorResponse;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import io.groo.damda.securities.service.SecuritiesCandleProviders;
import io.groo.damda.securities.service.SecuritiesPriceProvider;
import io.groo.damda.securities.service.SecuritiesPriceProviders;
import io.groo.damda.securities.service.dto.CandlePage;
import io.groo.damda.securities.service.dto.CandleQuery;
import io.groo.damda.securities.service.dto.InstrumentRef;
import io.groo.damda.securities.service.dto.PriceQuote;
import io.groo.damda.securities.service.dto.SecuritiesCandle;
import io.groo.damda.securities.type.CandleInterval;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 증권사 무관 시세 — <b>사용자가 고른 기본 소스</b>로 대신 조회한다.
 *
 * <p><b>왜 필요한가</b> — 가계부 자산 화면은 보유 종목의 현재가와 환율만 있으면 된다. 그런데
 * 앱·웹이 {@code /api/v1/toss/**} 를 직접 부르고 있어서, <b>나무만 연결한 사용자는 자산
 * 평가액이 0 이나 누락으로 표시됐다</b>(토스 크리덴셜이 없어 403/에러). 서버 쪽 평가는 이미
 * 기본 소스를 따르는데 클라이언트가 안 따라온 것이다.
 *
 * <p>증권사별 조회({@code /api/v1/toss/**} · {@code /api/v1/namu/**})는 그대로 둔다 —
 * 증권 화면이 보여주는 것은 증권사마다 달라 합칠 수 없다. 여기 있는 건
 * <b>두 증권사가 똑같이 주는 것</b>뿐이다.
 *
 * <p>활성 구독(SECURITIES) 필요 — {@code FeatureGateInterceptor} 가 게이트한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/securities")
@RequiredArgsConstructor
public class SecuritiesApiController {

    /** 한 번에 물을 수 있는 종목 수. 나무는 종목마다 1콜이라 상한이 없으면 유량 제한에 걸린다. */
    private static final int MAX_SYMBOLS = 50;

    /**
     * 캔들 한 페이지의 봉 수 상한. <b>토스 상한(200)과 같은 값으로 맞춘다</b> —
     * 여기만 키우면 토스 경로에서 조용히 잘려 페이지 크기가 증권사마다 달라지고,
     * 화면의 커서 루프가 "더 있는데 안 온다" 로 헛돈다.
     */
    private static final int MAX_CANDLE_SIZE = 200;

    private final SecuritiesPriceProviders priceProviders;
    private final SecuritiesCandleProviders candleProviders;

    /**
     * 종목 다건 현재가. 못 구한 종목은 결과에서 빠진다 — 호출부가 빠진 것을 보고 판단한다.
     *
     * @param symbols 콤마 구분 종목코드(stock_master 기준). 시장은 서버가 정한다 —
     *                같은 티커가 여러 시장에 걸리면 {@code StockMasterResolver} 우선순위를 따른다
     */
    @GetMapping("/prices")
    public ApiResponse<List<PriceQuote>> getPrices(
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
        // 자르는 걸 분기로 남긴다. 스트림 안에서 조용히 limit 하면 잘렸다는 사실이 응답에도
        // 로그에도 안 남고, 클라이언트는 "시세 미확보" 로 읽어 그 자산을 평가에서 통째로 뺀다.
        List<String> capped = requested.size() > MAX_SYMBOLS ? requested.subList(0, MAX_SYMBOLS) : requested;
        if (capped.size() < requested.size()) {
            log.warn("시세 조회 상한 초과 - 뒤쪽 종목이 잘린다: userRowId={}, 요청={}, 조회={}, 버림={}",
                loginUser.getRowId(), requested.size(), capped.size(),
                requested.subList(MAX_SYMBOLS, requested.size()));
        }
        List<InstrumentRef> instruments = capped.stream().map(InstrumentRef::of).toList();
        SecuritiesPriceProvider provider = priceProviders.forUser(loginUser.getRowId());
        return ApiResponse.success(provider.getPrices(loginUser.getRowId(), instruments));
    }

    /**
     * 환율. 못 구하면 {@code null} — 나무는 해당 통화 보유 종목이 있어야 환율이 나온다.
     * 호출부는 외화 환산을 접는다(부분합으로 금액을 왜곡하지 않는다).
     */
    @GetMapping("/exchange-rate")
    public ApiResponse<ExchangeRateResponse> getExchangeRate(
            @LoginUser UserPrincipal loginUser,
            @RequestParam(defaultValue = "USD") String base,
            @RequestParam(defaultValue = "KRW") String quote) {
        BigDecimal rate = priceProviders.forUser(loginUser.getRowId())
            .getFxRate(loginUser.getRowId(), base, quote);
        return ApiResponse.success(new ExchangeRateResponse(base, quote, rate));
    }

    /**
     * 종목 캔들 한 페이지. <b>증권사는 서버가 고른다.</b>
     *
     * <p><b>왜 필요한가</b> — 캔들 경로가 {@code /api/v1/toss/candles} 하나뿐이라
     * <b>나무만 연결한 사용자는 차트를 아예 못 봤다.</b> 토스 키가 없으면 그 뒤의
     * {@code TossApiClient} 가 {@code SECURITIES_CREDENTIAL_REQUIRED} 를 던지기 때문이다.
     * 나무에도 기간별시세가 있었고 연동을 안 했을 뿐이다.
     *
     * <p>{@code /api/v1/toss/candles} 는 <b>그대로 둔다</b> — 옛 앱이 아직 부른다.
     * 응답 모양도 그쪽과 같게 맞췄다(같은 {@code CursorResponse}, 같은 필드명).
     *
     * <p>고르는 규칙은 시세와 다르다 — 기본 소스가 캔들을 못 주면 연결된 다른 증권사로
     * 넘어간다. 이유는 {@link SecuritiesCandleProviders} 주석 참고.
     *
     * @param interval {@code 1m}(분봉) · {@code 1d}(일봉)
     * @param size     이 페이지의 봉 수. 미지정이면 {@value #MAX_CANDLE_SIZE}, 초과하면 잘린다
     * @param cursor   직전 응답의 {@code meta.nextCursor}. 첫 페이지면 생략.
     *                 <b>값의 뜻은 증권사가 정한다</b> — 클라이언트는 받은 것을 그대로 돌려주면 된다
     * @param adjusted 수정주가 여부. 토스만 받는다(나무 기간별시세엔 대응 파라미터가 없다)
     */
    @GetMapping("/candles")
    public ApiResponse<CursorResponse<SecuritiesCandle>> getCandles(
            @LoginUser UserPrincipal loginUser,
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Boolean adjusted) {
        String requested = symbol == null ? "" : symbol.trim();
        if (requested.isEmpty()) {
            throw new InvalidValueException(DeskErrorCode.SECURITIES_SYMBOL_INVALID);
        }
        int pageSize = (size == null || size <= 0) ? MAX_CANDLE_SIZE : Math.min(size, MAX_CANDLE_SIZE);
        CandleInterval parsed = CandleInterval.from(interval);
        // 주봉은 나무 전용(N1-가 "5년") — 공개 API 의 모양은 그대로 1m · 1d 만 받는다. 나무 탭은 /namu/candles.
        if (parsed == CandleInterval.WEEK_1) {
            throw new InvalidValueException(DeskErrorCode.SECURITIES_INTERVAL_UNSUPPORTED);
        }
        CandleQuery query = new CandleQuery(requested, parsed, pageSize,
            cursor == null || cursor.isBlank() ? null : cursor.trim(), adjusted);

        CandlePage page = candleProviders.forUser(loginUser.getRowId())
            .getCandles(loginUser.getRowId(), query);
        return ApiResponse.success(
            CursorResponse.of(page.candles(), pageSize, page.hasNext(), page.nextCursor()));
    }

    /** @param rate 못 구하면 null */
    @Schema(name = "SecuritiesExchangeRateResponse")
    public record ExchangeRateResponse(String base, String quote, BigDecimal rate) {
    }
}
