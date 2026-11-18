package io.groo.damda.asset.service;

import io.groo.damda.common.patch.Patch;
import io.groo.core.time.UserClock;
import io.groo.core.type.YNType;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.domain.AssetHolding;
import io.groo.damda.asset.repository.AssetBalanceHistoryRepository;
import io.groo.damda.asset.repository.AssetHoldingRepository;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.asset.repository.AssetTransferRepository;
import io.groo.damda.asset.service.dto.AssetServiceDto;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.asset.type.HoldingType;
import io.groo.damda.card.repository.CardBillingRepository;
import io.groo.damda.card.repository.CardCatalogRepository;
import io.groo.damda.expense.repository.ExpenseRepository;
import io.groo.damda.subscription.service.SubscriptionEntitlementService;
import io.groo.damda.securities.service.SecuritiesCredentialService;
import io.groo.damda.securities.service.SecuritiesPriceProviders;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.assertj.core.api.Assertions;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 투자 자산을 편집할 때 예수금이 조용히 사라지지 않는지.
 *
 * <p>실제 상황: 주식을 전량 매도하면 보유는 없어지고 그 대금이 예수금으로 남는다.
 * 사용자는 화면에서 보유를 지우고 잔액칸에 매도 대금을 적는다 — 그게 유일한 입력 수단이다.
 * 그런데 빈 보유 목록도 "보유를 함께 보냈다"로 판정돼 예수금 반영 가지가 통째로 건너뛰어지면,
 * 적어 넣은 금액이 버려지고 평가액만 0 이 돼 자산이 통째로 증발한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvestmentCashPreservedTest {

    @Mock private AssetRepository assetRepository;
    @Mock private AssetHoldingRepository assetHoldingRepository;
    @Mock private AssetTransferRepository assetTransferRepository;
    @Mock private UserRepository userRepository;
    @Mock private CardCatalogRepository cardCatalogRepository;
    @Mock private CardBillingRepository cardBillingRepository;
    @Mock private ExpenseRepository expenseRepository;
    @Mock private AssetBalanceHistoryService balanceHistoryService;
    @Mock private UserClock userClock;
    @Mock private SubscriptionEntitlementService entitlementService;
    @Mock private SecuritiesCredentialService securitiesCredentialService;
    @Mock private SecuritiesPriceProviders priceProviders;
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
    private static final long ASSET_ID = 11L;
    private User user;

    @BeforeEach
    void setUp() {
        user = User.createUser(1L, "tester", "테스터", "tester@hangroo.io");
        ReflectionTestUtils.setField(user, "rowId", USER_ID);
        given(userClock.now(USER_ID)).willReturn(LocalDateTime.of(2026, 8, 10, 12, 0));
        given(assetHoldingRepository.findActiveByAsset(ASSET_ID)).willReturn(List.of());
    }

    /** 예수금 0 + 보유 평가 4,800만 인 주식계좌 (전량 매도 직전 상태). */
    private Asset brokerageWithHoldings() {
        Asset a = Asset.createAsset(user, "주식계좌", AssetType.INVESTMENT, 0L, "KRW",
            null, null, null, null, 0, YNType.Y, null, null, null, null);
        ReflectionTestUtils.setField(a, "rowId", ASSET_ID);
        // 잔액은 이력 집계로 온다 — 예수금 0 / 평가금액 4,800만 상태를 그대로 흉내 낸다.
        given(balanceHistoryService.balanceAt(any(), any()))
            .willReturn(new AssetBalanceHistoryService.Split(0L, 48_000_000L));
        given(assetRepository.findById(ASSET_ID)).willReturn(Optional.of(a));
        return a;
    }

    private AssetServiceDto.UpdateAssetCommand command(Long balance,
                                                       List<AssetServiceDto.HoldingCommand> holdings) {
        return new AssetServiceDto.UpdateAssetCommand(
            Patch.set("주식계좌"), Patch.set(AssetType.INVESTMENT), Patch.set(balance), Patch.absent(), Patch.set("KRW"), Patch.absent(),
            Patch.absent(), Patch.set("삼성증권"), Patch.absent(), Patch.set(YNType.Y),
            Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), holdings);
    }

    @Test
    @DisplayName("전량 매도 — 보유를 비우고 매도 대금을 적으면 예수금으로 남는다")
    void sellAllKeepsProceedsAsCash() {
        Asset invest = brokerageWithHoldings();

        // 화면에서 보유를 전부 지우고 잔액칸에 매도 대금 4,800만을 적었다.
        sut.updateAsset(ASSET_ID, USER_ID, command(48_000_000L, List.of()));

        verify(balanceHistoryService).recordValuation(eq(invest), eq(0L), any()); // 평가액 0
        verify(balanceHistoryService).recordManual(eq(invest), eq(48_000_000L), any()); // 예수금 반영
    }

    @Test
    @DisplayName("전량 매도 — 매도 대금이 기존 총액과 같아도 예수금으로 옮겨진다")
    void sellAllWhenProceedsEqualOldTotal() {
        // 총액(4,800만)과 입력값이 같다고 '안 바뀐 것'으로 보면 안 된다 —
        // 같은 숫자지만 평가금액에서 예수금으로 칸이 바뀐 것이다.
        Asset invest = brokerageWithHoldings();

        sut.updateAsset(ASSET_ID, USER_ID, command(48_000_000L, List.of()));

        verify(balanceHistoryService).recordManual(eq(invest), eq(48_000_000L), any());
    }

    @Test
    @DisplayName("보유가 남아 있으면 잔액 입력으로 예수금을 덮어쓰지 않는다")
    void keepsCashUntouchedWhileHoldingsRemain() {
        Asset invest = brokerageWithHoldings();
        var holding = new AssetServiceDto.HoldingCommand(
            null,
            null, false, null, null, null, "삼성전자", 48_000_000L, null, null);

        sut.updateAsset(ASSET_ID, USER_ID, command(48_000_000L, List.of(holding)));

        // 보유가 있는 동안 총액을 예수금 앵커로 찍으면 평가금액과 이중 계상된다.
        verify(balanceHistoryService, never()).recordManual(any(), any(Long.class), any());
    }

    @Test
    @DisplayName("편집으로 보유를 저장해도 매수로 쌓은 원가는 남는다")
    void editKeepsCostBasis() {
        Asset invest = brokerageWithHoldings();
        // 매수로 원가 700만이 쌓여 있는 보유. 편집 폼은 원가를 입력받지 않아 안 보낸다.
        var existing = AssetHolding.create(invest, HoldingType.STOCK, YNType.N, null, null,
            new java.math.BigDecimal("100"), "삼성전자", 8_000_000L, 7_000_000L, 0);
        given(assetHoldingRepository.findActiveByAsset(ASSET_ID)).willReturn(List.of(existing));

        var sent = new AssetServiceDto.HoldingCommand(
            null,
            HoldingType.STOCK, false, null, null, new java.math.BigDecimal("100"),
            "삼성전자", 8_500_000L, null, null); // totalCost 미전송

        sut.updateAsset(ASSET_ID, USER_ID, command(null, List.of(sent)));

        // rowId 를 안 실어 보내도 같은 종목이면 그 행을 제자리에서 고친다 — 새 행을 만들면
        // row_id 가 바뀌어 거래(asset_trade) 연결이 끊기고, 한 자산에 같은 종목이 두 줄로 남는다.
        verify(assetHoldingRepository, never()).save(any());
        // 원가가 0 으로 날아가면 다음 매도에서 대금 전액이 이익으로 잡힌다.
        assertThat(existing.getTotalCost()).isEqualTo(7_000_000L);
        assertThat(existing.getHoldingValue()).isEqualTo(8_500_000L);
        assertThat(existing.getIsDeleted()).isEqualTo(YNType.N);
    }

    @Test
    @DisplayName("보유를 안 보낸 편집(이름만 변경)은 예수금을 건드리지 않는다")
    void renameOnlyDoesNotTouchCash() {
        Asset invest = brokerageWithHoldings();

        sut.updateAsset(ASSET_ID, USER_ID, command(null, null));

        verify(balanceHistoryService, never()).recordManual(any(), any(Long.class), any());
        verify(balanceHistoryService, never()).recordValuation(any(), any(Long.class), any());
    }
}
