package io.groo.damda.asset.service;

import io.groo.core.time.UserClock;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.domain.AssetBalanceHistory;
import io.groo.damda.asset.repository.AssetBalanceHistoryRepository;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.asset.type.BalanceSourceType;
import io.groo.core.type.YNType;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 체크카드는 연결 계좌에서 즉시 빠져나간다 — 잔액 flow 가 카드가 아니라 그 계좌 앞으로 쌓이는지.
 *
 * <p>체크카드는 계좌에 1:1 매핑된 결제 수단이라 자체 잔액이 없다. 신용카드처럼 결제일에
 * 몰아서 정산하는 게 아니라 긁는 즉시 계좌에서 빠지므로, 이력을 카드 앞으로 쌓으면
 * 통장 잔액이 실제보다 많게 남는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckCardBalanceLinkTest {

    @Mock private AssetBalanceHistoryRepository repository;
    @Mock private UserClock userClock;
    @Mock private AssetRepository assetRepository;
    @InjectMocks private AssetBalanceHistoryService sut;

    private static final LocalDateTime AT = LocalDateTime.of(2026, 8, 3, 10, 5);

    private User user;

    @BeforeEach
    void setUp() {
        user = User.createUser(1L, "tester", "테스터", "tester@hangroo.io");
        ReflectionTestUtils.setField(user, "rowId", 2L);
        given(userClock.nowIn(any())).willReturn(AT.plusDays(1));
        given(repository.findActiveByAssetIds(anyList(), any())).willReturn(List.of());
    }

    private Asset asset(long rowId, AssetType type, Asset paymentAsset) {
        Asset a = Asset.createAsset(user, "자산" + rowId, type, 0L, "KRW",
            null, null, null, null,
            0, YNType.Y, null, null, null, paymentAsset);
        ReflectionTestUtils.setField(a, "rowId", rowId);
        return a;
    }

    private Asset savedHistoryAsset() {
        ArgumentCaptor<AssetBalanceHistory> captor = ArgumentCaptor.forClass(AssetBalanceHistory.class);
        verify(repository).save(captor.capture());
        return captor.getValue().getAsset();
    }

    @Test
    @DisplayName("체크카드 지출 — 잔액 이력이 연결 계좌 앞으로 쌓인다")
    void checkCardExpenseGoesToLinkedAccount() {
        Asset account = asset(10L, AssetType.BANK_ACCOUNT, null);
        Asset card = asset(19L, AssetType.CHECK_CARD, account);

        sut.recordExpense(card, 100L, ExpenseType.EXPENSE, 12_000L, AT);

        assertThat(savedHistoryAsset()).isSameAs(account);
    }

    @Test
    @DisplayName("연결 계좌가 없는 체크카드 — 종전대로 카드 앞으로 (지정 전 데이터 보존)")
    void checkCardWithoutLinkKeepsOldBehavior() {
        Asset card = asset(19L, AssetType.CHECK_CARD, null);

        sut.recordExpense(card, 100L, ExpenseType.EXPENSE, 12_000L, AT);

        assertThat(savedHistoryAsset()).isSameAs(card);
    }

    @Test
    @DisplayName("신용카드는 결제일에 이체로 정산하므로 카드 앞으로 그대로 쌓인다")
    void creditCardStillRecordsOnCard() {
        Asset account = asset(10L, AssetType.BANK_ACCOUNT, null);
        Asset card = asset(20L, AssetType.CREDIT_CARD, account);

        sut.recordExpense(card, 100L, ExpenseType.EXPENSE, 12_000L, AT);

        assertThat(savedHistoryAsset()).isSameAs(card);
    }

    @Test
    @DisplayName("연결 계좌 지정 — 그 카드로 쓴 기존 지출 이력이 새 계좌로 옮겨진다")
    void relinkMovesExistingHistory() {
        Asset account = asset(10L, AssetType.BANK_ACCOUNT, null);
        Asset card = asset(19L, AssetType.CHECK_CARD, account);
        // 연결 전에 카드 앞으로 쌓여 있던 지출
        AssetBalanceHistory old = AssetBalanceHistory.of(
            user, card, BalanceSourceType.EXPENSE, 100L, -12_000L, AT);
        given(repository.findActiveExpenseHistoryPaidWith(19L, YNType.N)).willReturn(List.of(old));

        sut.relinkCheckCardHistory(card, account);

        assertThat(old.getAsset()).isSameAs(account);
    }

    @Test
    @DisplayName("연결 계좌를 A→B 로 바꾸면 이미 A 로 옮겨둔 이력도 B 로 따라간다")
    void relinkFollowsAccountChange() {
        Asset accountA = asset(10L, AssetType.BANK_ACCOUNT, null);
        Asset accountB = asset(11L, AssetType.BANK_ACCOUNT, null);
        Asset card = asset(19L, AssetType.CHECK_CARD, accountB);
        // 이력의 소속은 이미 A — 이력 기준으로 찾으면 못 찾고, 거래 기준이라 찾힌다.
        AssetBalanceHistory moved = AssetBalanceHistory.of(
            user, accountA, BalanceSourceType.EXPENSE, 100L, -12_000L, AT);
        given(repository.findActiveExpenseHistoryPaidWith(19L, YNType.N)).willReturn(List.of(moved));

        sut.relinkCheckCardHistory(card, accountB);

        assertThat(moved.getAsset()).isSameAs(accountB);
    }

    @Test
    @DisplayName("체크카드 지출을 삭제하면 연결 계좌에서 빠졌던 금액이 되돌아온다")
    void deletingExpenseRestoresLinkedAccount() {
        Asset account = asset(10L, AssetType.BANK_ACCOUNT, null);
        Asset card = asset(19L, AssetType.CHECK_CARD, account);
        // 지출이 남긴 이력은 카드가 아니라 통장 앞에 걸려 있다.
        AssetBalanceHistory flow = AssetBalanceHistory.of(
            user, account, BalanceSourceType.EXPENSE, 100L, -12_000L, AT);
        given(repository.findActiveBySource(BalanceSourceType.EXPENSE, 100L, YNType.N))
            .willReturn(List.of(flow));
        sut.removeExpense(100L);

        // 잔액은 조회할 때 이력에서 집계하므로, 이력 행이 지워졌는지만 보면 된다.
        // 그 행이 카드가 아니라 연결 계좌 앞으로 쌓여 있었다는 게 이 테스트의 요점이다.
        assertThat(flow.getIsDeleted()).isEqualTo(YNType.Y);
        assertThat(flow.getAsset()).isSameAs(account);
    }
}
