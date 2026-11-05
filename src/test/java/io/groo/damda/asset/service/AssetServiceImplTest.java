package io.groo.damda.asset.service;

import io.groo.core.exception.ForbiddenException;
import io.groo.core.type.YNType;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.domain.AssetTransfer;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.asset.repository.AssetTransferRepository;
import io.groo.damda.asset.service.dto.AssetServiceDto;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.card.domain.CardBilling;
import io.groo.damda.card.repository.CardCatalogRepository;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.subscription.service.SubscriptionEntitlementService;
import io.groo.damda.securities.service.SecuritiesCredentialService;
import io.groo.damda.securities.service.SecuritiesPriceProvider;
import io.groo.damda.securities.service.SecuritiesPriceProviders;
import io.groo.damda.securities.service.dto.InstrumentRef;
import io.groo.damda.securities.service.dto.PriceQuote;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import io.groo.core.time.ServiceClock;
import io.groo.core.time.UserClock;

/**
 * 자산 서비스 소유권 가드 회귀 방지 단위 테스트 — 남의 자산/이체는 조회·수정·삭제·이체할 수 없다.
 */
@ExtendWith(MockitoExtension.class)
class AssetServiceImplTest {

    @Mock private AssetRepository assetRepository;
    @Mock private io.groo.damda.asset.repository.AssetHoldingRepository assetHoldingRepository;
    @Mock private AssetTransferRepository assetTransferRepository;
    @Mock private UserRepository userRepository;
    @Mock private CardCatalogRepository cardCatalogRepository;
    @Mock private io.groo.damda.card.repository.CardBillingRepository cardBillingRepository;
    // 이체 삭제 시 이자로 만든 지출 거래를 함께 무르므로 필요 — mock 이 없으면 NPE.
    @Mock private io.groo.damda.expense.repository.ExpenseRepository expenseRepository;
    @Mock private AssetBalanceHistoryService balanceHistoryService;
    @Mock private SubscriptionEntitlementService entitlementService;
    @Mock private SecuritiesCredentialService securitiesCredentialService;
    @Mock private SecuritiesPriceProviders priceProviders;
    @Mock private SecuritiesPriceProvider priceProvider;
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


    private User user(long rowId) {
        User u = User.createUser(null, "tester", "테스터", "tester@porest.com");
        ReflectionTestUtils.setField(u, "rowId", rowId);
        return u;
    }

    private Asset assetOwnedBy(long ownerRowId) {
        Asset a = mock(Asset.class);
        // 자동 생성 이체처럼 소유권 검사 전에 막히는 경로도 있어 lenient 로 둔다.
        lenient().when(a.getUser()).thenReturn(user(ownerRowId));
        return a;
    }

    @Test
    @DisplayName("getAsset — 남의 자산은 조회 불가")
    void getRejectsOthers() {
        Asset asset = assetOwnedBy(999L);
        given(assetRepository.findById(5L)).willReturn(Optional.of(asset));

        assertThatThrownBy(() -> sut.getAsset(5L, USER_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("updateAsset — 남의 자산은 수정 불가")
    void updateRejectsOthers() {
        Asset asset = assetOwnedBy(999L);
        given(assetRepository.findById(5L)).willReturn(Optional.of(asset));

        assertThatThrownBy(() -> sut.updateAsset(5L, USER_ID, null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("deleteAsset — 남의 자산은 삭제 불가")
    void deleteRejectsOthers() {
        Asset asset = assetOwnedBy(999L);
        given(assetRepository.findById(5L)).willReturn(Optional.of(asset));

        assertThatThrownBy(() -> sut.deleteAsset(5L, USER_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── 결제계좌를 지우면(QA 30 6) ────────────────────────────────────────────
    // 삭제가 카드의 참조를 안 끊어서, 그 카드의 [지금 결제]·자동 결제·카드 수정이 지운 계좌를
    // 찾다 404 "자산을 찾을 수 없어요" 로 막혔다.

    private Asset realAsset(long rowId, String name, io.groo.damda.asset.type.AssetType type, Asset payment) {
        Asset a = Asset.createAsset(user(USER_ID), name, type, 0L, "KRW", null, null, null, null, 0,
            io.groo.core.type.YNType.Y, null, null, 25, payment);
        ReflectionTestUtils.setField(a, "rowId", rowId);
        return a;
    }

    @Test
    @DisplayName("deleteAsset — 그 계좌를 결제·연결 계좌로 쓰던 카드는 연결을 끊는다, 다른 카드는 그대로")
    void deleteDetachesCardsUsingIt() {
        Asset bank = realAsset(10L, "급여통장", io.groo.damda.asset.type.AssetType.BANK_ACCOUNT, null);
        Asset other = realAsset(11L, "생활비통장", io.groo.damda.asset.type.AssetType.BANK_ACCOUNT, null);
        Asset credit = realAsset(20L, "신한", io.groo.damda.asset.type.AssetType.CREDIT_CARD, bank);
        Asset check = realAsset(21L, "체크", io.groo.damda.asset.type.AssetType.CHECK_CARD, bank);
        Asset untouched = realAsset(22L, "현대", io.groo.damda.asset.type.AssetType.CREDIT_CARD, other);
        given(assetRepository.findById(10L)).willReturn(Optional.of(bank));
        given(assetRepository.findByUser(USER_ID)).willReturn(List.of(bank, other, credit, check, untouched));

        sut.deleteAsset(10L, USER_ID);

        assertThat(bank.getIsDeleted()).isEqualTo(io.groo.core.type.YNType.Y);
        assertThat(credit.getPaymentAsset()).isNull();
        assertThat(check.getPaymentAsset()).isNull();
        assertThat(untouched.getPaymentAsset()).isSameAs(other);
    }

    @Test
    @DisplayName("이미 지운 계좌를 가리키는 카드 — 응답엔 결제계좌가 없다(옛 폼이 그 id 를 되싣지 않게)")
    void deletedPaymentAccountIsHiddenInResponse() {
        Asset bank = realAsset(10L, "급여통장", io.groo.damda.asset.type.AssetType.BANK_ACCOUNT, null);
        Asset card = realAsset(20L, "신한", io.groo.damda.asset.type.AssetType.CREDIT_CARD, bank);
        bank.deleteAsset(); // 삭제가 참조를 안 끊던 때 남은 데이터

        assertThat(card.getUsablePaymentAsset()).isNull();
        assertThat(io.groo.damda.asset.service.dto.AssetServiceDto.AssetInfo.from(card).paymentAssetRowId()).isNull();
    }

    /** 결제계좌 칸만 실은 수정 — 카드 수정 폼이 안 바꾼 결제계좌를 그대로 되싣는 모양. */
    private AssetServiceDto.UpdateAssetCommand paymentAccountOnly(String name, Long paymentAssetRowId) {
        return new AssetServiceDto.UpdateAssetCommand(
            io.groo.damda.common.patch.Patch.set(name), io.groo.damda.common.patch.Patch.absent(),
            io.groo.damda.common.patch.Patch.absent(), io.groo.damda.common.patch.Patch.absent(),
            io.groo.damda.common.patch.Patch.absent(), io.groo.damda.common.patch.Patch.absent(),
            io.groo.damda.common.patch.Patch.absent(), io.groo.damda.common.patch.Patch.absent(),
            io.groo.damda.common.patch.Patch.absent(), io.groo.damda.common.patch.Patch.absent(),
            io.groo.damda.common.patch.Patch.absent(), io.groo.damda.common.patch.Patch.absent(),
            io.groo.damda.common.patch.Patch.absent(), io.groo.damda.common.patch.Patch.absent(),
            io.groo.damda.common.patch.Patch.set(paymentAssetRowId), null);
    }

    @Test
    @DisplayName("updateAsset — 지우기 전에 연 폼이 지운 계좌 id 를 되실어 와도 '연결 없음' 으로 저장한다")
    void staleFormWithDeletedPaymentAccountSaves() {
        // 삭제가 이미 연결을 끊었다 — 카드는 결제계좌가 없고, 폼만 옛 id(10) 를 들고 있다.
        Asset card = realAsset(20L, "신한", io.groo.damda.asset.type.AssetType.CREDIT_CARD, null);
        given(assetRepository.findById(20L)).willReturn(Optional.of(card));
        given(assetRepository.existsDeletedOwnedBy(10L, USER_ID)).willReturn(true);
        given(balanceHistoryService.balanceAt(any(), any())).willReturn(AssetBalanceHistoryService.Split.ZERO);

        sut.updateAsset(20L, USER_ID, paymentAccountOnly("신한 새이름", 10L));

        assertThat(card.getAssetName()).isEqualTo("신한 새이름");
        assertThat(card.getPaymentAsset()).isNull();
        verify(assetRepository, never()).findById(10L);
    }

    @Test
    @DisplayName("updateAsset — 지운 게 아닌 없는 id·남의 계좌는 종전대로 막는다")
    void unknownPaymentAccountStillRejected() {
        Asset card = realAsset(20L, "신한", io.groo.damda.asset.type.AssetType.CREDIT_CARD, null);
        given(assetRepository.findById(20L)).willReturn(Optional.of(card));
        given(assetRepository.existsDeletedOwnedBy(anyLong(), eq(USER_ID))).willReturn(false);
        given(assetRepository.findById(99L)).willReturn(Optional.empty());
        Asset othersBank = assetOwnedBy(999L);
        given(assetRepository.findById(98L)).willReturn(Optional.of(othersBank));

        assertThatThrownBy(() -> sut.updateAsset(20L, USER_ID, paymentAccountOnly("신한", 99L)))
            .isInstanceOf(io.groo.core.exception.EntityNotFoundException.class);
        assertThatThrownBy(() -> sut.updateAsset(20L, USER_ID, paymentAccountOnly("신한", 98L)))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("createAsset — 지우기 전에 연 추가 폼이 지운 계좌를 골라 와도 카드는 연결 없이 만든다")
    void createWithDeletedPaymentAccountCreatesUnlinked() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));
        given(assetRepository.existsDeletedOwnedBy(10L, USER_ID)).willReturn(true);
        lenient().when(balanceHistoryService.balanceAt(any(), any())).thenReturn(AssetBalanceHistoryService.Split.ZERO);
        var cmd = new AssetServiceDto.CreateAssetCommand(
            USER_ID, "신한", io.groo.damda.asset.type.AssetType.CREDIT_CARD, 0L, null, "KRW",
            null, null, null, null, 0,
            YNType.Y, YNType.N, null, null, 25, 10L, null);

        sut.createAsset(cmd);

        org.mockito.ArgumentCaptor<Asset> saved = org.mockito.ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).save(saved.capture());
        assertThat(saved.getValue().getPaymentAsset()).isNull();
        verify(assetRepository, never()).findById(10L);
    }

    @Test
    @DisplayName("createTransfer — 출금 자산이 남의 것이면 이체 불가")
    void transferRejectsOthersFromAsset() {
        Asset fromAsset = assetOwnedBy(999L);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));
        given(assetRepository.findById(10L)).willReturn(Optional.of(fromAsset));

        var cmd = new AssetServiceDto.CreateTransferCommand(
                USER_ID, 10L, 11L, 50_000L, 0L, 0L, "이체", LocalDate.of(2026, 6, 1).atStartOfDay(), null);

        assertThatThrownBy(() -> sut.createTransfer(cmd))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("createTransfer — 입금 자산이 남의 것이면 이체 불가")
    void transferRejectsOthersToAsset() {
        Asset fromAsset = assetOwnedBy(USER_ID);
        Asset toAsset = assetOwnedBy(999L);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));
        given(assetRepository.findById(10L)).willReturn(Optional.of(fromAsset));
        given(assetRepository.findById(11L)).willReturn(Optional.of(toAsset));

        var cmd = new AssetServiceDto.CreateTransferCommand(
                USER_ID, 10L, 11L, 50_000L, 0L, 0L, "이체", LocalDate.of(2026, 6, 1).atStartOfDay(), null);

        assertThatThrownBy(() -> sut.createTransfer(cmd))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("createTransfer — 같은 자산으로의 이체는 불가")
    void transferRejectsSameAsset() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));

        var cmd = new AssetServiceDto.CreateTransferCommand(
                USER_ID, 10L, 10L, 50_000L, 0L, 0L, "이체", LocalDate.of(2026, 6, 1).atStartOfDay(), null);

        assertThatThrownBy(() -> sut.createTransfer(cmd))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("createTransfer — 이체 금액이 0 이하면 불가(음수 자금 역류 방지)")
    void transferRejectsNonPositiveAmount() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));

        var cmd = new AssetServiceDto.CreateTransferCommand(
                USER_ID, 10L, 11L, -50_000L, 0L, 0L, "이체", LocalDate.of(2026, 6, 1).atStartOfDay(), null);

        assertThatThrownBy(() -> sut.createTransfer(cmd))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("createTransfer — 성공 시 잔액 이력(recordTransfer)을 기록한다")
    void transferRecordsBalanceHistory() {
        Asset fromAsset = assetOwnedBy(USER_ID);
        Asset toAsset = assetOwnedBy(USER_ID);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));
        given(assetRepository.findById(10L)).willReturn(Optional.of(fromAsset));
        given(assetRepository.findById(11L)).willReturn(Optional.of(toAsset));

        var cmd = new AssetServiceDto.CreateTransferCommand(
                USER_ID, 10L, 11L, 30_000L, 1_000L, 0L, "이체", LocalDate.of(2026, 6, 1).atStartOfDay(), null);
        sut.createTransfer(cmd);

        verify(balanceHistoryService).recordTransfer(any(AssetTransfer.class));
    }

    @Test
    @DisplayName("createTransfer — 체크카드는 이체 대상이 될 수 없다(잔액을 들지 않는 자산)")
    void transferRejectsCheckCard() {
        Asset fromAsset = assetOwnedBy(USER_ID);
        Asset checkCard = assetOwnedBy(USER_ID);
        given(checkCard.getAssetType()).willReturn(AssetType.CHECK_CARD);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));
        given(assetRepository.findById(10L)).willReturn(Optional.of(fromAsset));
        given(assetRepository.findById(11L)).willReturn(Optional.of(checkCard));

        var cmd = new AssetServiceDto.CreateTransferCommand(
                USER_ID, 10L, 11L, 30_000L, 0L, 0L, "이체", LocalDate.of(2026, 6, 1).atStartOfDay(), null);

        assertThatThrownBy(() -> sut.createTransfer(cmd))
                .isInstanceOf(InvalidValueException.class);
        verify(balanceHistoryService, never()).recordTransfer(any(AssetTransfer.class));
    }

    @Test
    @DisplayName("createTransfer — 신용카드는 결제일 자동이체 대상이라 그대로 허용한다")
    void transferAllowsCreditCard() {
        Asset fromAsset = assetOwnedBy(USER_ID);
        Asset creditCard = assetOwnedBy(USER_ID);
        given(creditCard.getAssetType()).willReturn(AssetType.CREDIT_CARD);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));
        given(assetRepository.findById(10L)).willReturn(Optional.of(fromAsset));
        given(assetRepository.findById(11L)).willReturn(Optional.of(creditCard));

        var cmd = new AssetServiceDto.CreateTransferCommand(
                USER_ID, 10L, 11L, 30_000L, 0L, 0L, "카드 결제", LocalDate.of(2026, 6, 1).atStartOfDay(), null);
        sut.createTransfer(cmd);

        verify(balanceHistoryService).recordTransfer(any(AssetTransfer.class));
    }

    @Test
    @DisplayName("reorderAssets — 남의 자산 순서는 변경 불가(소유권 검증 누락 보강)")
    void reorderRejectsOthers() {
        Asset others = assetOwnedBy(999L);
        given(assetRepository.findById(10L)).willReturn(Optional.of(others));

        var items = List.of(new AssetServiceDto.ReorderItem(10L, 1));

        assertThatThrownBy(() -> sut.reorderAssets(USER_ID, items))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("deleteTransfer — 카드 결제 이체를 지우면 그 청구 회차도 함께 취소된다")
    void deleteTransferCancelsLinkedCardBilling() {
        AssetTransfer payment = mock(AssetTransfer.class);
        given(payment.getUser()).willReturn(user(USER_ID));
        given(assetTransferRepository.findById(900L)).willReturn(Optional.of(payment));
        CardBilling billing = mock(CardBilling.class);
        given(cardBillingRepository.findAllActiveByTransfer(900L)).willReturn(List.of(billing));

        sut.deleteTransfer(900L, USER_ID);

        // 청구가 COMPLETED 로 남으면 '이미 냈다'로 집계돼 다음 청구액이 0 이 되고,
        // 카드 부채가 영원히 안 갚아진다.
        verify(billing).cancel();
        verify(balanceHistoryService).removeTransfer(900L);
    }

    @Test
    @DisplayName("deleteTransfer — 일반 이체는 청구와 무관하므로 그냥 지운다")
    void deleteOrdinaryTransferTouchesNoBilling() {
        AssetTransfer plain = mock(AssetTransfer.class);
        given(plain.getUser()).willReturn(user(USER_ID));
        given(assetTransferRepository.findById(901L)).willReturn(Optional.of(plain));
        given(cardBillingRepository.findAllActiveByTransfer(901L)).willReturn(List.of());

        sut.deleteTransfer(901L, USER_ID);

        verify(balanceHistoryService).removeTransfer(901L);
    }

    @Test
    @DisplayName("deleteTransfer — 남의 이체는 삭제 불가")
    void deleteTransferRejectsOthers() {
        AssetTransfer transfer = mock(AssetTransfer.class);
        given(transfer.getUser()).willReturn(user(999L));
        given(assetTransferRepository.findById(7L)).willReturn(Optional.of(transfer));

        assertThatThrownBy(() -> sut.deleteTransfer(7L, USER_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("getNetWorthTrend — 음수 months 는 거부")
    void netWorthTrendRejectsNegativeMonths() {
        assertThatThrownBy(() -> sut.getNetWorthTrend(USER_ID, -1))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("getAssetBalanceTrend — 음수 weeks 는 거부")
    void balanceTrendRejectsNegativeWeeks() {
        assertThatThrownBy(() -> sut.getAssetBalanceTrend(5L, USER_ID, -1))
                .isInstanceOf(InvalidValueException.class);
    }

    // === 토스 연결 게이트 ===

    // === 토스 평가액 스냅샷 (추이) ===

    private Asset tossLinked(long ownerRowId, String symbol, long quantity) {
        Asset a = mock(Asset.class);
        given(a.getUser()).willReturn(user(ownerRowId));
        given(a.isSecuritiesLinked()).willReturn(true);
        given(a.getSymbol()).willReturn(symbol);
        given(a.getQuantity()).willReturn(quantity);
        return a;
    }

    @Test
    @DisplayName("snapshotSecuritiesValuations — 시세×수량을 VALUATION 으로 적재")
    void snapshotRecordsValuation() {
        Asset linked = tossLinked(USER_ID, "005930", 10L);
        given(assetRepository.findAllByType(io.groo.damda.asset.type.AssetType.INVESTMENT))
                .willReturn(List.of(linked));
        given(entitlementService.hasFeature(USER_ID, "SECURITIES")).willReturn(true);
        given(securitiesCredentialService.hasAnyConnection(USER_ID)).willReturn(true);
        given(priceProviders.forUser(USER_ID)).willReturn(priceProvider);
        given(priceProvider.getPrices(USER_ID, List.of(InstrumentRef.of("005930")))).willReturn(List.of(
                PriceQuote.of("005930", new java.math.BigDecimal("70000"), "KRW")));

        sut.snapshotSecuritiesValuations();

        verify(balanceHistoryService).recordValuation(eq(linked), eq(700_000L), any());
    }

    @Test
    @DisplayName("snapshotSecuritiesValuations — 프로 미보유 사용자는 스냅샷하지 않음")
    void snapshotSkipsNonPro() {
        Asset linked = mock(Asset.class);
        given(linked.getUser()).willReturn(user(USER_ID));
        given(linked.isSecuritiesLinked()).willReturn(true);
        given(assetRepository.findAllByType(io.groo.damda.asset.type.AssetType.INVESTMENT))
                .willReturn(List.of(linked));
        given(entitlementService.hasFeature(USER_ID, "SECURITIES")).willReturn(false);

        sut.snapshotSecuritiesValuations();

        verify(balanceHistoryService, never()).recordValuation(any(), anyLong(), any());
    }

    @Nested
    @DisplayName("이체 수수료 부호 — 음수면 없던 돈이 생긴다")
    class TransferFeeSign {

        @Test
        @DisplayName("음수 수수료는 거부한다 — 출금은 줄고 입금은 그대로라 돈이 늘어난다")
        void rejectsNegativeFee() {
            User u = user(USER_ID);
            // 수수료 검증이 자산 조회보다 먼저 도므로 자산 스텁은 lenient 로 둔다.
            Asset from = mock(Asset.class);
            Asset to = mock(Asset.class);
            lenient().when(from.getUser()).thenReturn(u);
            lenient().when(to.getUser()).thenReturn(u);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
            lenient().when(assetRepository.findById(10L)).thenReturn(Optional.of(from));
            lenient().when(assetRepository.findById(11L)).thenReturn(Optional.of(to));

            // amount 100,000 / fee -50,000 → 출금 -(100,000-50,000)= -50,000, 입금 +100,000.
            // 순자산이 50,000 늘어난다.
            var cmd = new AssetServiceDto.CreateTransferCommand(
                USER_ID, 10L, 11L, 100_000L, -50_000L, 0L, "이체",
                LocalDate.of(2026, 6, 1).atStartOfDay(), null);

            // 코드까지 본다 — 예외 타입만 보면 "금액이 0 이하" 로 거부해도 통과한다.
            // 실제로 이 자리는 한동안 ASSET_005(금액)를 돌려 쓰고 있었다(#335 가 갈랐다).
            assertThatThrownBy(() -> sut.createTransfer(cmd))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).getErrorCode())
                .isEqualTo(DeskErrorCode.ASSET_TRANSFER_INVALID_FEE);
        }

        @Test
        @DisplayName("수수료 0 은 정상 — 대부분의 이체가 수수료가 없다")
        void allowsZeroFee() {
            User u = user(USER_ID);
            Asset from = mock(Asset.class);
            Asset to = mock(Asset.class);
            given(from.getUser()).willReturn(u);
            given(to.getUser()).willReturn(u);
            given(from.getAssetType()).willReturn(AssetType.BANK_ACCOUNT);
            given(to.getAssetType()).willReturn(AssetType.BANK_ACCOUNT);
            given(assetRepository.findById(10L)).willReturn(Optional.of(from));
            given(assetRepository.findById(11L)).willReturn(Optional.of(to));
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
            given(assetTransferRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            var cmd = new AssetServiceDto.CreateTransferCommand(
                USER_ID, 10L, 11L, 100_000L, 0L, 0L, "이체",
                LocalDate.of(2026, 6, 1).atStartOfDay(), null);

            org.assertj.core.api.Assertions.assertThatCode(() -> sut.createTransfer(cmd))
                .doesNotThrowAnyException();
        }
    }

    // ── 이체 수정 ──────────────────────────────────────────────────

    private AssetTransfer transferOf(long rowId, Asset from, Asset to, long amount, String autoSource) {
        AssetTransfer t = AssetTransfer.createTransfer(user(USER_ID), from, to, amount, 0L, 0L,
            "이체", LocalDate.of(2026, 6, 1).atStartOfDay());
        ReflectionTestUtils.setField(t, "rowId", rowId);
        if (autoSource != null) {
            t.markAutoGenerated(autoSource);
        }
        return t;
    }

    @Test
    @DisplayName("updateTransfer — 옛 잔액 이력을 걷어내고 새 값으로 다시 만든다")
    void updateTransferRebuildsSideEffects() {
        Asset from = assetOwnedBy(USER_ID);
        ReflectionTestUtils.setField(from, "rowId", 10L);
        Asset to = assetOwnedBy(USER_ID);
        ReflectionTestUtils.setField(to, "rowId", 11L);
        AssetTransfer transfer = transferOf(5L, from, to, 50_000L, null);

        given(assetTransferRepository.findById(5L)).willReturn(Optional.of(transfer));
        given(assetRepository.findById(10L)).willReturn(Optional.of(from));
        given(assetRepository.findById(11L)).willReturn(Optional.of(to));

        var cmd = new AssetServiceDto.CreateTransferCommand(
            USER_ID, 10L, 11L, 80_000L, 0L, 0L, "고친 이체",
            LocalDate.of(2026, 6, 2).atStartOfDay(), null);
        sut.updateTransfer(5L, cmd);

        // 필드만 바꾸면 옛 flow 가 남아 잔액이 어긋난다 — 걷어내고 다시 찍어야 한다.
        verify(balanceHistoryService).removeTransfer(5L);
        verify(balanceHistoryService).recordTransfer(transfer);
        assertThat(transfer.getAmount()).isEqualTo(80_000L);
        assertThat(transfer.getRowId()).isEqualTo(5L);  // rowId 는 유지 — 참조가 안 끊긴다
    }

    @Test
    @DisplayName("updateTransfer — 매수가 만든 충당 이체는 못 고친다")
    void updateTransferRejectsAutoGenerated() {
        Asset from = assetOwnedBy(USER_ID);
        Asset to = assetOwnedBy(USER_ID);
        AssetTransfer transfer = transferOf(5L, from, to, 50_000L, "TRADE_SETTLEMENT");
        given(assetTransferRepository.findById(5L)).willReturn(Optional.of(transfer));

        var cmd = new AssetServiceDto.CreateTransferCommand(
            USER_ID, 10L, 11L, 80_000L, 0L, 0L, "고침",
            LocalDate.of(2026, 6, 2).atStartOfDay(), null);

        assertThatThrownBy(() -> sut.updateTransfer(5L, cmd))
            .isInstanceOf(InvalidValueException.class);
        // 아무것도 건드리지 않는다
        verify(balanceHistoryService, never()).removeTransfer(anyLong());
    }

    @Test
    @DisplayName("deleteTransferByUser — 시스템이 만든 이체는 사용자가 못 지운다")
    void userDeleteRejectsAutoGenerated() {
        Asset from = assetOwnedBy(USER_ID);
        Asset to = assetOwnedBy(USER_ID);
        AssetTransfer transfer = transferOf(5L, from, to, 50_000L, "TRADE_SETTLEMENT");
        given(assetTransferRepository.findById(5L)).willReturn(Optional.of(transfer));

        assertThatThrownBy(() -> sut.deleteTransferByUser(5L, USER_ID))
            .isInstanceOf(InvalidValueException.class);
        assertThat(transfer.getIsDeleted()).isEqualTo(YNType.N);
    }

    @Test
    @DisplayName("deleteTransfer(내부) — 매수 취소가 충당 이체를 지우는 길은 막지 않는다")
    void internalDeleteAllowsAutoGenerated() {
        Asset from = assetOwnedBy(USER_ID);
        Asset to = assetOwnedBy(USER_ID);
        AssetTransfer transfer = transferOf(5L, from, to, 50_000L, "TRADE_SETTLEMENT");
        given(assetTransferRepository.findById(5L)).willReturn(Optional.of(transfer));

        sut.deleteTransfer(5L, USER_ID);

        assertThat(transfer.getIsDeleted()).isEqualTo(YNType.Y);
    }

    @Test
    @DisplayName("updateTransfer — 카드 환급 이체는 못 고친다(고치면 잔액이 다시 양수로 뜬다)")
    void updateTransferRejectsCardRefund() {
        Asset from = assetOwnedBy(USER_ID);
        Asset to = assetOwnedBy(USER_ID);
        AssetTransfer transfer = transferOf(5L, from, to, 228_600L, "CARD_REFUND");
        given(assetTransferRepository.findById(5L)).willReturn(Optional.of(transfer));

        var cmd = new AssetServiceDto.CreateTransferCommand(
            USER_ID, 10L, 11L, 80_000L, 0L, 0L, "고침",
            LocalDate.of(2026, 6, 2).atStartOfDay(), null);

        assertThatThrownBy(() -> sut.updateTransfer(5L, cmd))
            .isInstanceOf(InvalidValueException.class)
            // 카드 결제·일반 자동생성과 구분되는 안내가 나가야 한다 — 셋이 같은 메시지면
            // "카드 청구 화면에서 취소하세요" 같은 엉뚱한 안내가 환급에도 나간다
            .hasMessageContaining(DeskErrorCode.ASSET_TRANSFER_CARD_REFUND_READONLY.getMessageKey());
        verify(balanceHistoryService, never()).removeTransfer(anyLong());
    }

    @Test
    @DisplayName("deleteTransferByUser — 카드 환급 이체는 사용자가 못 지운다")
    void userDeleteRejectsCardRefund() {
        Asset from = assetOwnedBy(USER_ID);
        Asset to = assetOwnedBy(USER_ID);
        AssetTransfer transfer = transferOf(5L, from, to, 228_600L, "CARD_REFUND");
        given(assetTransferRepository.findById(5L)).willReturn(Optional.of(transfer));

        assertThatThrownBy(() -> sut.deleteTransferByUser(5L, USER_ID))
            .isInstanceOf(InvalidValueException.class);
        assertThat(transfer.getIsDeleted()).isEqualTo(YNType.N);
    }

    // ── 통화가 다른 이체 — 받는 금액(to_amount) ─────────────────────────

    @Nested
    @DisplayName("통화가 다른 두 자산 이체 — 받는 쪽 금액을 받는 쪽 환율로 박아 둔다")
    class CrossCurrencyTransfer {

        private Asset real(long rowId, String currency, String rate) {
            Asset a = Asset.createAsset(user(USER_ID), currency + rowId, AssetType.BANK_ACCOUNT, 0L, currency,
                rate != null ? new java.math.BigDecimal(rate) : null,
                null, null, null, 0, YNType.Y, null, null, null, null);
            ReflectionTestUtils.setField(a, "rowId", rowId);
            given(assetRepository.findById(rowId)).willReturn(Optional.of(a));
            return a;
        }

        @Test
        @DisplayName("createTransfer 원 → USD(1,300) 1,300,000원 — to_amount 1,000 이 저장 · 응답에 실린다")
        void createStoresToAmount() {
            real(10L, "KRW", null);
            real(11L, "USD", "1300");
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));
            org.mockito.ArgumentCaptor<AssetTransfer> captor = org.mockito.ArgumentCaptor.forClass(AssetTransfer.class);

            var info = sut.createTransfer(new AssetServiceDto.CreateTransferCommand(
                USER_ID, 10L, 11L, 1_300_000L, 0L, 0L, "환전",
                LocalDate.of(2026, 11, 6).atStartOfDay(), null));

            verify(assetTransferRepository).save(captor.capture());
            assertThat(captor.getValue().getToAmount()).isEqualTo(1_000L);
            assertThat(info.toAmount()).isEqualTo(1_000L);
            assertThat(info.amount()).isEqualTo(1_300_000L);  // 보낸 금액(원화)은 그대로
        }

        @Test
        @DisplayName("createTransfer USD → 원 $1,000 — 금액은 보내는 계좌 통화, to_amount 1,300,000원")
        void createFromForeignSender() {
            real(10L, "USD", "1300");
            real(11L, "KRW", null);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));

            var info = sut.createTransfer(new AssetServiceDto.CreateTransferCommand(
                USER_ID, 10L, 11L, 1_000L, 0L, 0L, "환전",
                LocalDate.of(2026, 11, 6).atStartOfDay(), null));

            assertThat(info.amount()).isEqualTo(1_000L);
            assertThat(info.toAmount()).isEqualTo(1_300_000L);
        }

        @Test
        @DisplayName("updateTransfer 메모만 고침(H1) — 그 사이 환율을 1,350 으로 고쳐도 to_amount 1,000 · 잔액 기록 그대로, 시각만 옮김")
        void memoOnlyUpdateKeepsMoney() {
            Asset krw = real(10L, "KRW", null);
            Asset usd = real(11L, "USD", "1300");
            AssetTransfer transfer = transferOf(5L, krw, usd, 1_300_000L, null);
            transfer.resolveToAmount(null);
            given(assetTransferRepository.findById(5L)).willReturn(Optional.of(transfer));
            ReflectionTestUtils.setField(usd, "exchangeRate", new java.math.BigDecimal("1350"));
            java.time.LocalDateTime newDate = LocalDate.of(2026, 6, 3).atStartOfDay();
            given(balanceHistoryService.retimeTransfer(5L, newDate)).willReturn(true);

            sut.updateTransfer(5L, new AssetServiceDto.CreateTransferCommand(
                USER_ID, 10L, 11L, 1_300_000L, 0L, 0L, "메모만 고침", newDate, null));

            assertThat(transfer.getToAmount()).isEqualTo(1_000L);     // 963 으로 바뀌지 않는다
            assertThat(transfer.getDescription()).isEqualTo("메모만 고침");
            verify(balanceHistoryService).retimeTransfer(5L, newDate);
            verify(balanceHistoryService, never()).removeTransfer(anyLong());
            verify(balanceHistoryService, never()).recordTransfer(any(AssetTransfer.class));
        }

        @Test
        @DisplayName("createTransfer 원 → 원 — to_amount NULL(지금 해석)")
        void createSameCurrencyKeepsNull() {
            real(10L, "KRW", null);
            real(11L, "KRW", null);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));

            var info = sut.createTransfer(new AssetServiceDto.CreateTransferCommand(
                USER_ID, 10L, 11L, 30_000L, 0L, 0L, "이체",
                LocalDate.of(2026, 11, 6).atStartOfDay(), null));

            assertThat(info.toAmount()).isNull();
        }

        @Test
        @DisplayName("updateTransfer — 받는 쪽을 USD 에서 원화 계좌로 바꾸면 to_amount 가 NULL, 금액을 바꾸면 다시 환산")
        void updateRecomputesToAmount() {
            Asset krw = real(10L, "KRW", null);
            Asset usd = real(11L, "USD", "1300");
            real(12L, "KRW", null);
            AssetTransfer transfer = transferOf(5L, krw, usd, 1_300_000L, null);
            transfer.resolveToAmount(null);
            assertThat(transfer.getToAmount()).isEqualTo(1_000L);
            given(assetTransferRepository.findById(5L)).willReturn(Optional.of(transfer));

            sut.updateTransfer(5L, new AssetServiceDto.CreateTransferCommand(
                USER_ID, 10L, 11L, 2_600_000L, 0L, 0L, "환전",
                LocalDate.of(2026, 11, 6).atStartOfDay(), null));
            assertThat(transfer.getToAmount()).isEqualTo(2_000L);

            sut.updateTransfer(5L, new AssetServiceDto.CreateTransferCommand(
                USER_ID, 10L, 12L, 50_000L, 0L, 0L, "이체",
                LocalDate.of(2026, 11, 6).atStartOfDay(), null));
            assertThat(transfer.getToAmount()).isNull();
            // 금액 · 계좌가 바뀐 수정은 옛 기록을 걷고 새 값으로 다시 적는다.
            verify(balanceHistoryService, org.mockito.Mockito.times(2)).removeTransfer(5L);
            verify(balanceHistoryService, org.mockito.Mockito.times(2)).recordTransfer(transfer);
        }
    }

    @Test
    @DisplayName("getAssetBalanceTrend(M3) — USD 예수금 $100 + 보유 10,000,000원: balance 10,000,100(옛 뜻) · balanceInKrw 10,130,000")
    void assetTrendCarriesKrwPoint() {
        Asset usd = Asset.createAsset(user(USER_ID), "달러 증권", AssetType.INVESTMENT, 0L, "USD",
            new java.math.BigDecimal("1300"), null, null, null, 0, YNType.Y, null, null, null, null);
        ReflectionTestUtils.setField(usd, "rowId", 5L);
        given(assetRepository.findById(5L)).willReturn(Optional.of(usd));
        given(balanceHistoryService.balancesAtPoints(any(), any())).willReturn(java.util.Map.of(
            5L, List.of(new AssetBalanceHistoryService.Split(100L, 10_000_000L))));

        var points = sut.getAssetBalanceTrend(5L, USER_ID, 1);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).balance()).isEqualTo(10_000_100L);
        assertThat(points.get(0).balanceInKrw()).isEqualTo(10_130_000L);
    }

    // ── 통화 규칙(H2 · L7) ─────────────────────────────────────────

    @Nested
    @DisplayName("자산 통화 규칙 — 카드는 원화만, 외화 자산은 환율 > 0")
    class CurrencyRules {

        @Test
        @DisplayName("validateCurrency — 원화는 무엇이든 통과, 외화 계좌는 환율 > 0, 카드는 외화 거절")
        void rules() {
            AssetServiceImpl.validateCurrency(AssetType.CREDIT_CARD, "KRW", null);
            AssetServiceImpl.validateCurrency(AssetType.BANK_ACCOUNT, null, null);
            AssetServiceImpl.validateCurrency(AssetType.INVESTMENT, "USD", new java.math.BigDecimal("1300"));

            assertThatThrownBy(() -> AssetServiceImpl.validateCurrency(AssetType.INVESTMENT, "USD", null))
                .isInstanceOf(InvalidValueException.class)
                .hasMessageContaining(DeskErrorCode.ASSET_FOREIGN_RATE_REQUIRED.getMessageKey());
            assertThatThrownBy(() -> AssetServiceImpl.validateCurrency(AssetType.BANK_ACCOUNT, "EUR",
                    java.math.BigDecimal.ZERO))
                .hasMessageContaining(DeskErrorCode.ASSET_FOREIGN_RATE_REQUIRED.getMessageKey());
            assertThatThrownBy(() -> AssetServiceImpl.validateCurrency(AssetType.BANK_ACCOUNT, "usd",
                    new java.math.BigDecimal("-1")))
                .hasMessageContaining(DeskErrorCode.ASSET_FOREIGN_RATE_REQUIRED.getMessageKey());
            assertThatThrownBy(() -> AssetServiceImpl.validateCurrency(AssetType.CREDIT_CARD, "USD",
                    new java.math.BigDecimal("1300")))
                .hasMessageContaining(DeskErrorCode.ASSET_CARD_FOREIGN_CURRENCY.getMessageKey());
            assertThatThrownBy(() -> AssetServiceImpl.validateCurrency(AssetType.CHECK_CARD, "JPY",
                    new java.math.BigDecimal("9.1")))
                .hasMessageContaining(DeskErrorCode.ASSET_CARD_FOREIGN_CURRENCY.getMessageKey());
        }

        @Test
        @DisplayName("createAsset — USD 신용카드는 저장 전에 거절")
        void createRejectsForeignCard() {
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));
            var cmd = new AssetServiceDto.CreateAssetCommand(
                USER_ID, "해외카드", AssetType.CREDIT_CARD, 0L, null, "USD",
                new java.math.BigDecimal("1300"), null, null, null, 0,
                YNType.Y, YNType.N, null, null, 15, null, null);

            assertThatThrownBy(() -> sut.createAsset(cmd))
                .hasMessageContaining(DeskErrorCode.ASSET_CARD_FOREIGN_CURRENCY.getMessageKey());
            verify(assetRepository, never()).save(any());
        }

        @Test
        @DisplayName("createAsset — 환율 없는 USD 통장은 거절")
        void createRejectsForeignWithoutRate() {
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));
            var cmd = new AssetServiceDto.CreateAssetCommand(
                USER_ID, "달러 통장", AssetType.BANK_ACCOUNT, 100L, null, "USD",
                null, null, null, null, 0,
                YNType.Y, YNType.N, null, null, null, null, null);

            assertThatThrownBy(() -> sut.createAsset(cmd))
                .hasMessageContaining(DeskErrorCode.ASSET_FOREIGN_RATE_REQUIRED.getMessageKey());
        }

        @Test
        @DisplayName("updateAsset — 원화 체크카드의 통화를 USD 로 바꾸면 거절, 카드 필드는 그대로")
        void updateRejectsForeignCard() {
            Asset card = Asset.createAsset(user(USER_ID), "체크", AssetType.CHECK_CARD, 0L, "KRW",
                null, null, null, null, 0, YNType.Y, null, null, null, null);
            ReflectionTestUtils.setField(card, "rowId", 7L);
            given(assetRepository.findById(7L)).willReturn(Optional.of(card));
            var cmd = new AssetServiceDto.UpdateAssetCommand(
                io.groo.damda.common.patch.Patch.absent(), io.groo.damda.common.patch.Patch.absent(),
                io.groo.damda.common.patch.Patch.absent(), io.groo.damda.common.patch.Patch.absent(),
                io.groo.damda.common.patch.Patch.set("USD"),
                io.groo.damda.common.patch.Patch.set(new java.math.BigDecimal("1300")),
                io.groo.damda.common.patch.Patch.absent(), io.groo.damda.common.patch.Patch.absent(),
                io.groo.damda.common.patch.Patch.absent(), io.groo.damda.common.patch.Patch.absent(),
                io.groo.damda.common.patch.Patch.absent(), io.groo.damda.common.patch.Patch.absent(),
                io.groo.damda.common.patch.Patch.absent(), io.groo.damda.common.patch.Patch.absent(),
                io.groo.damda.common.patch.Patch.absent(), null);

            assertThatThrownBy(() -> sut.updateAsset(7L, USER_ID, cmd))
                .hasMessageContaining(DeskErrorCode.ASSET_CARD_FOREIGN_CURRENCY.getMessageKey());
            assertThat(card.getCurrency()).isEqualTo("KRW");
        }
    }
}
