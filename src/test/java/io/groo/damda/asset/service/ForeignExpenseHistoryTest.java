package io.groo.damda.asset.service;

import io.groo.core.time.UserClock;
import io.groo.core.type.YNType;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.domain.AssetBalanceHistory;
import io.groo.damda.asset.repository.AssetBalanceHistoryRepository;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 수입 · 지출 잔액 이력이 계좌 통화로 적히는지 — 금액(expense.amount)은 원화다.
 * 환율 USD 1,300.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ForeignExpenseHistoryTest {

    @Mock private AssetBalanceHistoryRepository repository;
    @Mock private UserClock userClock;
    @Mock private AssetRepository assetRepository;
    @Mock private io.groo.damda.expense.repository.ExpenseRepository expenseRepository;
    @InjectMocks private AssetBalanceHistoryService sut;

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 6, 10, 0);
    private User user;

    @BeforeEach
    void setUp() {
        user = User.createUser(1L, "tester", "테스터", "tester@example.com");
        ReflectionTestUtils.setField(user, "rowId", 2L);
    }

    private Asset asset(long rowId, AssetType type, String currency, String rate, Asset payment) {
        Asset a = Asset.createAsset(user, currency + rowId, type, 0L, currency,
            rate != null ? new BigDecimal(rate) : null,
            null, null, null, 0, YNType.Y, null, null, null, payment);
        ReflectionTestUtils.setField(a, "rowId", rowId);
        return a;
    }

    private AssetBalanceHistory saved() {
        ArgumentCaptor<AssetBalanceHistory> captor = ArgumentCaptor.forClass(AssetBalanceHistory.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("원화 계좌 6,500원 지출 → −6,500 (지금과 같다, 해외 결제여도 원화 금액)")
    void krwAccountUnchanged() {
        sut.recordExpenseKrw(asset(1, AssetType.BANK_ACCOUNT, "KRW", null, null), 100L,
            ExpenseType.EXPENSE, 6_500L, new BigDecimal("5"), "USD", AT);
        assertThat(saved().getAmount()).isEqualTo(-6_500L);
    }

    @Test
    @DisplayName("USD 계좌 $5 지출(6,500원) → −5 (원 통화 금액을 그대로, 이중 환산 없음)")
    void usdAccountSameCurrency() {
        sut.recordExpenseKrw(asset(1, AssetType.BANK_ACCOUNT, "USD", "1300", null), 100L,
            ExpenseType.EXPENSE, 6_550L, new BigDecimal("5.0000"), "USD", AT);
        assertThat(saved().getAmount()).isEqualTo(-5L);
    }

    @Test
    @DisplayName("USD 투자 계좌 원화 수입 13,000원 → 예수금 +10")
    void usdInvestmentKrwIncome() {
        sut.recordExpenseKrw(asset(1, AssetType.INVESTMENT, "USD", "1300", null), 100L,
            ExpenseType.INCOME, 13_000L, null, null, AT);
        AssetBalanceHistory h = saved();
        assertThat(h.getAmount()).isEqualTo(10L);
    }

    @Test
    @DisplayName("USD 계좌 EUR 결제 14,500원 → 원화 ÷ 1,300 = 11.15 → −11")
    void usdAccountOtherCurrency() {
        sut.recordExpenseKrw(asset(1, AssetType.BANK_ACCOUNT, "USD", "1300", null), 100L,
            ExpenseType.EXPENSE, 14_500L, new BigDecimal("10"), "EUR", AT);
        assertThat(saved().getAmount()).isEqualTo(-11L);
    }

    @Test
    @DisplayName("USD 통장에 연결된 체크카드 1,300원 지출 → 통장에 −1 (연결 계좌 환율)")
    void checkCardUsesLinkedAccountRate() {
        Asset account = asset(10, AssetType.BANK_ACCOUNT, "USD", "1300", null);
        Asset card = asset(11, AssetType.CHECK_CARD, "KRW", null, account);
        sut.recordExpenseKrw(card, 100L, ExpenseType.EXPENSE, 1_300L, null, null, AT);
        AssetBalanceHistory h = saved();
        assertThat(h.getAsset()).isSameAs(account);
        assertThat(h.getAmount()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("자산 없음 · 금액 없음은 적지 않는다")
    void noAssetNoop() {
        sut.recordExpenseKrw(null, 100L, ExpenseType.EXPENSE, 1_000L, null, null, AT);
        sut.recordExpenseKrw(asset(1, AssetType.BANK_ACCOUNT, "USD", "1300", null), 100L,
            ExpenseType.EXPENSE, null, null, null, AT);
        verify(repository, never()).save(any());
    }

    private AssetBalanceHistory row(long rowId, Asset asset, long amount, boolean deleted) {
        AssetBalanceHistory h = AssetBalanceHistory.of(user, asset,
            io.groo.damda.asset.type.BalanceSourceType.EXPENSE, 100L, amount, AT);
        ReflectionTestUtils.setField(h, "rowId", rowId);
        if (deleted) {
            h.softDelete();
        }
        return h;
    }

    @Test
    @DisplayName("M1 retimeExpense — 메모만 고친 수정: 환율을 1,400 으로 고쳐도 −$10 그대로, 시각만 옮긴다")
    void retimeKeepsStoredAmount() {
        Asset usd = asset(1, AssetType.BANK_ACCOUNT, "USD", "1400", null);
        AssetBalanceHistory h = row(5L, usd, -10L, false);
        org.mockito.BDDMockito.given(repository.findActiveBySource(
                io.groo.damda.asset.type.BalanceSourceType.EXPENSE, 100L, YNType.N))
            .willReturn(java.util.List.of(h));

        assertThat(sut.retimeExpense(usd, 100L, AT.plusDays(2))).isTrue();

        assertThat(h.getAmount()).isEqualTo(-10L);          // 다시 계산하면 −9
        assertThat(h.getEffectiveAt()).isEqualTo(AT.plusDays(2));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("M1 retimeExpense — 행이 다른 계좌에 있으면(연결 변경 등) 옮기지 않고 false")
    void retimeRefusesOtherAsset() {
        Asset usd = asset(1, AssetType.BANK_ACCOUNT, "USD", "1300", null);
        Asset other = asset(2, AssetType.BANK_ACCOUNT, "KRW", null, null);
        org.mockito.BDDMockito.given(repository.findActiveBySource(
                io.groo.damda.asset.type.BalanceSourceType.EXPENSE, 100L, YNType.N))
            .willReturn(java.util.List.of(row(5L, other, -13_000L, false)));

        assertThat(sut.retimeExpense(usd, 100L, AT)).isFalse();
    }

    @Test
    @DisplayName("M1 restoreExpense — 환불 취소는 환불 때 걷은 −$10 을 그대로 되살린다(환율 1,400 이어도)")
    void restoreUsesLastRemovedRow() {
        Asset usd = asset(1, AssetType.BANK_ACCOUNT, "USD", "1400", null);
        org.mockito.BDDMockito.given(repository.findActiveBySource(
                io.groo.damda.asset.type.BalanceSourceType.EXPENSE, 100L, YNType.Y))
            .willReturn(java.util.List.of(row(3L, usd, -12L, true), row(5L, usd, -10L, true)));

        sut.restoreExpense(usd, 100L, ExpenseType.EXPENSE, 13_000L, null, null, AT);

        AssetBalanceHistory h = saved();
        assertThat(h.getAmount()).isEqualTo(-10L);          // 가장 나중 행(row 5), 다시 계산하면 −9
        assertThat(h.getAsset()).isSameAs(usd);
    }

    @Test
    @DisplayName("M1 restoreExpense — 되살릴 행이 없으면 지금 환율로 새로 적는다")
    void restoreFallsBackToRecord() {
        Asset usd = asset(1, AssetType.BANK_ACCOUNT, "USD", "1300", null);
        org.mockito.BDDMockito.given(repository.findActiveBySource(
                io.groo.damda.asset.type.BalanceSourceType.EXPENSE, 100L, YNType.Y))
            .willReturn(java.util.List.of());

        sut.restoreExpense(usd, 100L, ExpenseType.EXPENSE, 13_000L, null, null, AT);

        assertThat(saved().getAmount()).isEqualTo(-10L);
    }

    @Test
    @DisplayName("M2 체크카드 연결을 원화 통장 → USD 통장(1,300)으로 — 옛 13,000원 지출이 −$10 으로 옮겨 적힌다")
    void relinkConvertsToNewAccountCurrency() {
        Asset krwBank = asset(10, AssetType.BANK_ACCOUNT, "KRW", null, null);
        Asset usdBank = asset(11, AssetType.BANK_ACCOUNT, "USD", "1300", null);
        Asset card = asset(12, AssetType.CHECK_CARD, "KRW", null, krwBank);
        AssetBalanceHistory h = row(5L, krwBank, -13_000L, false);
        org.mockito.BDDMockito.given(repository.findActiveExpenseHistoryPaidWith(12L, YNType.N))
            .willReturn(java.util.List.of(h));
        io.groo.damda.expense.domain.Expense e = io.groo.damda.expense.domain.Expense.createExpense(
            user, null, card, ExpenseType.EXPENSE, 13_000L, "점심", AT, null, "CARD", null, null, null, null);
        org.mockito.BDDMockito.given(expenseRepository.findById(100L)).willReturn(java.util.Optional.of(e));

        sut.relinkCheckCardHistory(card, usdBank);

        assertThat(h.getAsset()).isSameAs(usdBank);
        assertThat(h.getAmount()).isEqualTo(-10L);
    }

    @Test
    @DisplayName("M2 원화 → 원화 연결 변경은 금액 그대로 옮긴다(지금 동작, 거래를 읽지 않음)")
    void relinkKrwKeepsAmount() {
        Asset a = asset(10, AssetType.BANK_ACCOUNT, "KRW", null, null);
        Asset b = asset(11, AssetType.BANK_ACCOUNT, "KRW", null, null);
        Asset card = asset(12, AssetType.CHECK_CARD, "KRW", null, a);
        AssetBalanceHistory h = row(5L, a, -13_000L, false);
        org.mockito.BDDMockito.given(repository.findActiveExpenseHistoryPaidWith(12L, YNType.N))
            .willReturn(java.util.List.of(h));

        sut.relinkCheckCardHistory(card, b);

        assertThat(h.getAsset()).isSameAs(b);
        assertThat(h.getAmount()).isEqualTo(-13_000L);
        verify(expenseRepository, never()).findById(any());
    }
}
