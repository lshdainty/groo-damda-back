package io.groo.damda.asset.service;

import io.groo.core.time.UserClock;
import io.groo.core.type.YNType;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.domain.AssetBalanceHistory;
import io.groo.damda.asset.domain.AssetTransfer;
import io.groo.damda.asset.repository.AssetBalanceHistoryRepository;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.asset.type.AssetType;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 이체 잔액 이력이 각 자산 통화로 적히는지 — recordTransfer 가 남기는 두 행의 금액.
 * 환율 USD 1,300 · EUR 1,450.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ForeignTransferHistoryTest {

    @Mock private AssetBalanceHistoryRepository repository;
    @Mock private UserClock userClock;
    @Mock private AssetRepository assetRepository;
    @InjectMocks private AssetBalanceHistoryService sut;

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 6, 10, 0);
    private User user;

    @BeforeEach
    void setUp() {
        user = User.createUser(1L, "tester", "테스터", "tester@example.com");
        ReflectionTestUtils.setField(user, "rowId", 2L);
    }

    private Asset asset(long rowId, String currency, String rate) {
        Asset a = Asset.createAsset(user, currency + rowId, AssetType.BANK_ACCOUNT, 0L, currency,
            rate != null ? new BigDecimal(rate) : null,
            null, null, null, 0, YNType.Y, null, null, null, null);
        ReflectionTestUtils.setField(a, "rowId", rowId);
        return a;
    }

    /** 이체를 적고 [출금 행, 입금 행] 금액을 돌려준다. */
    private List<Long> record(Asset from, Asset to, long amount, long fee) {
        AssetTransfer t = AssetTransfer.createTransfer(user, from, to, amount, fee, 0L, "이체", AT);
        ReflectionTestUtils.setField(t, "rowId", 50L);
        t.resolveToAmount(null);
        sut.recordTransfer(t);
        ArgumentCaptor<AssetBalanceHistory> captor = ArgumentCaptor.forClass(AssetBalanceHistory.class);
        verify(repository, times(2)).save(captor.capture());
        List<AssetBalanceHistory> rows = captor.getAllValues();
        assertThat(rows.get(0).getAsset()).isSameAs(from);
        assertThat(rows.get(1).getAsset()).isSameAs(to);
        return List.of(rows.get(0).getAmount(), rows.get(1).getAmount());
    }

    @Test
    @DisplayName("원 → 원 50,000 · 수수료 500: −50,500 · +50,000 (지금과 같다)")
    void krwToKrw() {
        assertThat(record(asset(1, "KRW", null), asset(2, "KRW", null), 50_000L, 500L))
            .containsExactly(-50_500L, 50_000L);
    }

    @Test
    @DisplayName("원 → USD 1,300,000원: 통장 −1,300,000 · 달러 계좌 +1,000")
    void krwToUsd() {
        assertThat(record(asset(1, "KRW", null), asset(2, "USD", "1300"), 1_300_000L, 0L))
            .containsExactly(-1_300_000L, 1_000L);
    }

    @Test
    @DisplayName("USD → 원 $1,000: 달러 계좌 −1,000 · 통장 +1,300,000")
    void usdToKrw() {
        assertThat(record(asset(1, "USD", "1300"), asset(2, "KRW", null), 1_000L, 0L))
            .containsExactly(-1_000L, 1_300_000L);
    }

    @Test
    @DisplayName("USD → EUR $1,000: −1,000 · +897")
    void usdToEur() {
        assertThat(record(asset(1, "USD", "1300"), asset(2, "EUR", "1450"), 1_000L, 0L))
            .containsExactly(-1_000L, 897L);
    }

    @Test
    @DisplayName("USD → USD $1,000: −1,000 · +1,000 (to_amount NULL)")
    void usdToUsd() {
        assertThat(record(asset(1, "USD", "1300"), asset(2, "USD", "1310"), 1_000L, 0L))
            .containsExactly(-1_000L, 1_000L);
    }

    @Test
    @DisplayName("retimeTransfer — 두 행의 금액은 그대로, 시각만 옮긴다")
    void retimeKeepsAmounts() {
        AssetBalanceHistory out = AssetBalanceHistory.of(user, asset(1, "KRW", null),
            io.groo.damda.asset.type.BalanceSourceType.TRANSFER, 50L, -1_300_000L, AT);
        AssetBalanceHistory in = AssetBalanceHistory.of(user, asset(2, "USD", "1350"),
            io.groo.damda.asset.type.BalanceSourceType.TRANSFER, 50L, 1_000L, AT);
        org.mockito.BDDMockito.given(repository.findActiveBySource(
                io.groo.damda.asset.type.BalanceSourceType.TRANSFER, 50L, io.groo.core.type.YNType.N))
            .willReturn(List.of(out, in));

        LocalDateTime later = AT.plusDays(1);
        assertThat(sut.retimeTransfer(50L, later)).isTrue();

        assertThat(out.getAmount()).isEqualTo(-1_300_000L);
        assertThat(in.getAmount()).isEqualTo(1_000L);      // 환율 1,350 이어도 963 으로 바뀌지 않는다
        assertThat(in.getEffectiveAt()).isEqualTo(later);
        verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }
}
