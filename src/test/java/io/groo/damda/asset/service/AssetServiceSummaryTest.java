package io.groo.damda.asset.service;

import io.groo.core.type.YNType;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.asset.repository.AssetTransferRepository;
import io.groo.damda.asset.service.dto.AssetServiceDto;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.card.repository.CardCatalogRepository;
import io.groo.damda.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import io.groo.core.time.ServiceClock;
import io.groo.core.time.UserClock;

/**
 * 자산 순자산 집계 로직 회귀 방지 테스트 — 자산/부채 분류 합산과 netWorth 계산.
 *
 * <p>가르는 기준은 잔액의 <b>부호가 아니라 유형</b>이다(QA 2026-09-03 #21). 부호로 가르면
 * 잔액이 음수인 입출금(마이너스 통장)이 부채로 넘어가 홈과 자산 화면의 총자산이 어긋난다.
 * 대신 선결제한 카드처럼 부채군이 양수가 되면 {@code totalDebt} 가 <b>음수</b>가 될 수 있다.
 * {@code netWorth == totalBalance} 단언이 두 식이 갈라지지 않게 잡아 주는 방어선이다.
 */
@ExtendWith(MockitoExtension.class)
class AssetServiceSummaryTest {

    @Mock private AssetRepository assetRepository;
    @Mock private AssetTransferRepository assetTransferRepository;
    @Mock private UserRepository userRepository;
    @Mock private CardCatalogRepository cardCatalogRepository;
    @Mock private AssetBalanceHistoryService balanceHistoryService;
    // 날짜 판정용 — mock 이면 null 이 흘러 NPE. 실물을 주입하되 사용자 조회는 비어
    // 서비스 기준(Asia/Seoul)으로 폴백한다.
    @Spy private UserClock userClock = new UserClock(rowId -> null, new ServiceClock("Asia/Seoul"));

    // 시장코드 확정은 mock 기본값(null) — 확정 못 한 경우와 같다.

    @Mock private io.groo.damda.stock.service.StockMasterResolver stockMasterResolver;
    // 회차별 결제일(D5) — 이력이 비어 있으면 카드의 지금 결제일 하나로 센다. 닫힌 회차의 "오늘"은 서울 시계(D11).
    private final io.groo.damda.asset.repository.AssetPaymentDayHistoryRepository paymentDayHistory =
        org.mockito.Mockito.mock(io.groo.damda.asset.repository.AssetPaymentDayHistoryRepository.class);
    @org.mockito.Spy private io.groo.damda.card.service.PaymentScheduleService paymentScheduleService =
        new io.groo.damda.card.service.PaymentScheduleService(paymentDayHistory);
    @org.mockito.Spy private io.groo.core.time.ServiceClock serviceClock =
        new io.groo.core.time.ServiceClock("Asia/Seoul");

    @InjectMocks private AssetServiceImpl sut;

    private static final long USER_ID = 1L;

    private Asset asset(long rowId, AssetType type) {
        Asset a = Asset.createAsset(null, "자산" + rowId, type, 0L, "KRW",
            null,
                null, null, null, 0, YNType.Y, null, null, null, null);
        ReflectionTestUtils.setField(a, "rowId", rowId);
        return a;
    }

    private Asset foreignAsset(long rowId, AssetType type, String currency, String rate) {
        Asset a = Asset.createAsset(null, currency + "통장", type, 0L, currency,
            new java.math.BigDecimal(rate),
            null, null, null, 0, YNType.Y, null, null, null, null);
        ReflectionTestUtils.setField(a, "rowId", rowId);
        return a;
    }

    @Test
    @DisplayName("getAssetSummary — 자산/부채를 분류 합산하고 netWorth = 자산 - 부채")
    void netWorthClassifiesAssetsAndDebt() {
        Asset bank = asset(1L, AssetType.BANK_ACCOUNT);
        Asset creditCard = asset(2L, AssetType.CREDIT_CARD);
        Asset loan = asset(3L, AssetType.LOAN);
        given(assetRepository.findByUser(USER_ID)).willReturn(List.of(bank, creditCard, loan));

        // 잔액은 이제 DB 집계로 온다 — 자산별 값을 그대로 돌려준다.
        java.util.Map<Long, AssetBalanceHistoryService.Split> balances = new java.util.HashMap<>();
        given(balanceHistoryService.balancesAt(anyCollection(), any(LocalDateTime.class)))
            .willReturn(balances);
        balances.put(1L, new AssetBalanceHistoryService.Split(1_000_000L, 0L));
        balances.put(2L, new AssetBalanceHistoryService.Split(-300_000L, 0L));
        balances.put(3L, new AssetBalanceHistoryService.Split(-500_000L, 0L));

        AssetServiceDto.AssetSummary summary = sut.getAssetSummary(USER_ID, null, null);

        assertThat(summary.totalAssets()).isEqualTo(1_000_000L);     // 은행만 자산
        assertThat(summary.totalDebt()).isEqualTo(800_000L);         // |카드| + |대출|
        assertThat(summary.netWorth()).isEqualTo(200_000L);          // 1,000,000 - 800,000
        assertThat(summary.totalBalance()).isEqualTo(200_000L);      // 모든 잔액 합(부채 음수 포함)
    }

    @Test
    @DisplayName("선결제한 카드(잔액 양수) — 자산으로 잡히고 두 번 깎이지 않는다")
    void overpaidCardCountsAsAsset() {
        Asset bank = asset(1L, AssetType.BANK_ACCOUNT);
        Asset creditCard = asset(2L, AssetType.CREDIT_CARD);
        given(assetRepository.findByUser(USER_ID)).willReturn(List.of(bank, creditCard));

        java.util.Map<Long, AssetBalanceHistoryService.Split> balances = new java.util.HashMap<>();
        given(balanceHistoryService.balancesAt(anyCollection(), any(LocalDateTime.class)))
            .willReturn(balances);
        balances.put(1L, new AssetBalanceHistoryService.Split(1_000_000L, 0L));
        // 낼 돈보다 많이 넣어 둔 상태 — 카드사가 내 돈을 들고 있다.
        balances.put(2L, new AssetBalanceHistoryService.Split(356_800L, 0L));

        AssetServiceDto.AssetSummary summary = sut.getAssetSummary(USER_ID, null, null);

        // 유형 기준이라 카드는 부채군에 남고, 양수 잔액은 부호를 뒤집어 더해져 <b>부채를 줄인다</b>.
        // abs() 로 묶으면 자산에서 빠지고 부채로도 더해져 713,600 이 두 번 깎인다.
        assertThat(summary.totalAssets()).isEqualTo(1_000_000L);
        assertThat(summary.totalDebt()).isEqualTo(-356_800L);
        assertThat(summary.netWorth()).isEqualTo(1_356_800L);
        // 같은 응답 안에서 두 값이 어긋나면 화면의 분해 합계와 헤드라인이 안 맞는다.
        assertThat(summary.netWorth()).isEqualTo(summary.totalBalance());
    }

    @Test
    @DisplayName("대출을 양수로 입력해도 왜곡되지 않는다")
    void positiveLoanIsNotDoubleCounted() {
        Asset loan = asset(3L, AssetType.LOAN);
        given(assetRepository.findByUser(USER_ID)).willReturn(List.of(loan));

        java.util.Map<Long, AssetBalanceHistoryService.Split> balances = new java.util.HashMap<>();
        given(balanceHistoryService.balancesAt(anyCollection(), any(LocalDateTime.class)))
            .willReturn(balances);
        balances.put(3L, new AssetBalanceHistoryService.Split(500_000L, 0L));

        AssetServiceDto.AssetSummary summary = sut.getAssetSummary(USER_ID, null, null);

        // 유형이 대출이므로 자산군에는 한 푼도 안 들어가고, 부호를 뒤집어 부채가 음수가 된다.
        assertThat(summary.totalAssets()).isZero();
        assertThat(summary.totalDebt()).isEqualTo(-500_000L);
        assertThat(summary.netWorth()).isEqualTo(500_000L);
        assertThat(summary.netWorth()).isEqualTo(summary.totalBalance());
    }

    @Test
    @DisplayName("마이너스 통장 — 음수 입출금은 부채가 아니라 총자산을 깎는다(QA #21)")
    void minusAccountStaysInAssetsNotDebt() {
        Asset minus = asset(1L, AssetType.BANK_ACCOUNT);
        Asset bank = asset(2L, AssetType.BANK_ACCOUNT);
        given(assetRepository.findByUser(USER_ID)).willReturn(List.of(minus, bank));

        java.util.Map<Long, AssetBalanceHistoryService.Split> balances = new java.util.HashMap<>();
        given(balanceHistoryService.balancesAt(anyCollection(), any(LocalDateTime.class)))
            .willReturn(balances);
        balances.put(1L, new AssetBalanceHistoryService.Split(-50_000L, 0L));
        balances.put(2L, new AssetBalanceHistoryService.Split(1_000_000L, 0L));

        AssetServiceDto.AssetSummary summary = sut.getAssetSummary(USER_ID, null, null);

        // 부호로 갈랐을 때는 totalAssets=1,000,000 / totalDebt=50,000 이라 자산 화면(950,000)과
        // 홈이 정확히 50,000 어긋났다 — QA 실측 차이와 같은 값이다.
        assertThat(summary.totalAssets()).isEqualTo(950_000L);
        assertThat(summary.totalDebt()).isZero();
        assertThat(summary.netWorth()).isEqualTo(950_000L);
        assertThat(summary.netWorth()).isEqualTo(summary.totalBalance());
    }

    @Test
    @DisplayName("체크카드 — 부채군이 아니라 자산군이라 음수여도 총부채로 안 넘어간다")
    void checkCardCountsAsAsset() {
        Asset bank = asset(1L, AssetType.BANK_ACCOUNT);
        Asset checkCard = asset(2L, AssetType.CHECK_CARD);
        given(assetRepository.findByUser(USER_ID)).willReturn(List.of(bank, checkCard));

        java.util.Map<Long, AssetBalanceHistoryService.Split> balances = new java.util.HashMap<>();
        given(balanceHistoryService.balancesAt(anyCollection(), any(LocalDateTime.class)))
            .willReturn(balances);
        balances.put(1L, new AssetBalanceHistoryService.Split(1_000_000L, 0L));
        // 연결 계좌를 아직 안 고른 체크카드에는 지출 flow 가 카드 앞으로 쌓인다.
        balances.put(2L, new AssetBalanceHistoryService.Split(-7_000L, 0L));

        AssetServiceDto.AssetSummary summary = sut.getAssetSummary(USER_ID, null, null);

        assertThat(summary.totalAssets()).isEqualTo(993_000L);
        assertThat(summary.totalDebt()).isZero();
        assertThat(summary.netWorth()).isEqualTo(summary.totalBalance());
    }

    @Test
    @DisplayName("외화통장 — 원화 350만 + 달러 $1,000(환율 1,400) 이면 총자산 490만원")
    void foreignAccountConvertedIntoTotal() {
        Asset krw = asset(1L, AssetType.BANK_ACCOUNT);
        Asset usd = foreignAsset(2L, AssetType.BANK_ACCOUNT, "USD", "1400");
        given(assetRepository.findByUser(USER_ID)).willReturn(List.of(krw, usd));

        // 잔액은 이제 DB 집계로 온다 — 자산별 값을 그대로 돌려준다.
        java.util.Map<Long, AssetBalanceHistoryService.Split> balances = new java.util.HashMap<>();
        given(balanceHistoryService.balancesAt(anyCollection(), any(LocalDateTime.class)))
            .willReturn(balances);
        balances.put(1L, new AssetBalanceHistoryService.Split(3_500_000L, 0L));
        balances.put(2L, new AssetBalanceHistoryService.Split(1_000L, 0L));  // $1,000

        AssetServiceDto.AssetSummary summary = sut.getAssetSummary(USER_ID, null, null);

        // 환산이 없으면 3,501,000원이 나온다 — 달러가 원화처럼 더해진 값
        assertThat(summary.totalAssets()).isEqualTo(4_900_000L);
        assertThat(summary.netWorth()).isEqualTo(4_900_000L);
    }

    @Test
    @DisplayName("외화 대출 — $10,000 부채(환율 1,400)는 1,400만원 부채로 잡힌다")
    void foreignDebtConverted() {
        Asset krw = asset(1L, AssetType.BANK_ACCOUNT);
        Asset usdLoan = foreignAsset(2L, AssetType.LOAN, "USD", "1400");
        given(assetRepository.findByUser(USER_ID)).willReturn(List.of(krw, usdLoan));

        // 잔액은 이제 DB 집계로 온다 — 자산별 값을 그대로 돌려준다.
        java.util.Map<Long, AssetBalanceHistoryService.Split> balances = new java.util.HashMap<>();
        given(balanceHistoryService.balancesAt(anyCollection(), any(LocalDateTime.class)))
            .willReturn(balances);
        balances.put(1L, new AssetBalanceHistoryService.Split(20_000_000L, 0L));
        balances.put(2L, new AssetBalanceHistoryService.Split(-10_000L, 0L)); // -$10,000

        AssetServiceDto.AssetSummary summary = sut.getAssetSummary(USER_ID, null, null);

        assertThat(summary.totalAssets()).isEqualTo(20_000_000L);
        assertThat(summary.totalDebt()).isEqualTo(14_000_000L);
        assertThat(summary.netWorth()).isEqualTo(6_000_000L);
    }

    @Test
    @DisplayName("원화 자산만 있으면 환산 전과 값이 같다 — 기존 데이터 무해")
    void krwOnlyUnchanged() {
        Asset bank = asset(1L, AssetType.BANK_ACCOUNT);
        given(assetRepository.findByUser(USER_ID)).willReturn(List.of(bank));

        // 잔액은 이제 DB 집계로 온다 — 자산별 값을 그대로 돌려준다.
        java.util.Map<Long, AssetBalanceHistoryService.Split> balances = new java.util.HashMap<>();
        given(balanceHistoryService.balancesAt(anyCollection(), any(LocalDateTime.class)))
            .willReturn(balances);
        balances.put(1L, new AssetBalanceHistoryService.Split(1_234_567L, 0L));

        assertThat(sut.getAssetSummary(USER_ID, null, null).totalAssets()).isEqualTo(1_234_567L);
    }

    @Test
    @DisplayName("외화 투자 자산 — 순자산은 round(예수금 × 환율) + 보유 평가(원화), 전월 점도 같은 식")
    void foreignInvestmentSummaryUsesCashRateOnly() {
        Asset krw = asset(1L, AssetType.BANK_ACCOUNT);
        Asset usdInvest = foreignAsset(2L, AssetType.INVESTMENT, "USD", "1300");
        given(assetRepository.findByUser(USER_ID)).willReturn(List.of(krw, usdInvest));

        java.util.Map<Long, AssetBalanceHistoryService.Split> balances = new java.util.HashMap<>();
        given(balanceHistoryService.balancesAt(anyCollection(), any(LocalDateTime.class)))
            .willReturn(balances);
        balances.put(1L, new AssetBalanceHistoryService.Split(1_000_000L, 0L));
        // 예수금 $100 + 보유 평가 10,000,000원(원화)
        balances.put(2L, new AssetBalanceHistoryService.Split(100L, 10_000_000L));

        AssetServiceDto.AssetSummary summary = sut.getAssetSummary(USER_ID, null, null);

        // 종전 식 (100 + 10,000,000) × 1,300 = 13,000,130,000 이 아니라 130,000 + 10,000,000
        assertThat(summary.netWorth()).isEqualTo(1_000_000L + 10_130_000L);
        assertThat(summary.totalAssets()).isEqualTo(11_130_000L);
        // 두 시점이 같은 집계를 돌려주니 전월도 같은 식이면 증감은 0
        assertThat(summary.lastMonthNetWorth()).isEqualTo(11_130_000L);
        assertThat(summary.changeAmount()).isZero();
    }

    @Test
    @DisplayName("외화 투자 자산 — 순자산 추이 과거 점도 새 식이다")
    void foreignInvestmentTrendUsesNewFormula() {
        Asset usdInvest = foreignAsset(2L, AssetType.INVESTMENT, "USD", "1300");
        given(assetRepository.findByUser(USER_ID)).willReturn(List.of(usdInvest));

        // 3개월 점: 과거(보유 수동 5,000원) → 중간(보유 없음) → 현재(라이브 10,000,000원)
        given(balanceHistoryService.balancesAtPoints(anyCollection(), any()))
            .willReturn(java.util.Map.of(2L, List.of(
                new AssetBalanceHistoryService.Split(100L, 5_000L),
                new AssetBalanceHistoryService.Split(100L, 0L),
                new AssetBalanceHistoryService.Split(100L, 10_000_000L))));

        List<AssetServiceDto.NetWorthTrendPoint> points = sut.getNetWorthTrend(USER_ID, 3);

        assertThat(points).extracting(AssetServiceDto.NetWorthTrendPoint::netWorth)
            .containsExactly(135_000L, 130_000L, 10_130_000L);
    }

    @Test
    @DisplayName("자산 응답 — balanceInKrw 는 요약과 같은 식, balance·cashBalance·holdingBalance 는 그대로")
    void assetInfoCarriesBalanceInKrw() {
        Asset usdInvest = foreignAsset(2L, AssetType.INVESTMENT, "USD", "1300");
        ReflectionTestUtils.setField(usdInvest, "user", mock(io.groo.damda.user.domain.User.class));

        AssetServiceDto.AssetInfo info = AssetServiceDto.AssetInfo.from(usdInvest, List.of(),
            new AssetBalanceHistoryService.Split(100L, 5_000L));

        assertThat(info.balance()).isEqualTo(5_100L);
        assertThat(info.cashBalance()).isEqualTo(100L);
        assertThat(info.holdingBalance()).isEqualTo(5_000L);
        assertThat(info.balanceInKrw()).isEqualTo(135_000L);
        // 카드 상태를 붙여도 칸이 사라지지 않는다
        assertThat(info.withMonthlyUsedAmount(1L).balanceInKrw()).isEqualTo(135_000L);
        assertThat(info.withCardState(null, false, null, null).balanceInKrw()).isEqualTo(135_000L);
    }
}
