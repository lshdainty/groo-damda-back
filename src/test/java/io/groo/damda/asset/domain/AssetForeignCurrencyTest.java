package io.groo.damda.asset.domain;

import io.groo.core.type.YNType;
import io.groo.damda.asset.type.AssetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외화통장의 원화 환산 — 실제 계좌 잔고를 그대로 넣어 순자산이 맞는지 검증한다.
 *
 * <p>환산이 없으면 USD 1,000 잔고가 순자산에 1,000원으로 더해진다.
 * 원화 자산은 환산율이 1이라 결과가 종전과 같아야 한다(기존 데이터 무해).
 */
@DisplayName("외화통장 원화 환산")
class AssetForeignCurrencyTest {

    private Asset account(String currency, BigDecimal rate, long balance) {
        return Asset.createAsset(
            null, "외화통장", AssetType.BANK_ACCOUNT, balance, currency, rate,
            null, null, null, 0, YNType.Y, null, null, null, null);
    }

    @Nested
    @DisplayName("외화 계좌")
    class Foreign {

        @Test
        @DisplayName("달러통장 $1,000, 환율 1,400 — 순자산에 1,400,000원으로 잡힌다")
        void usdAccount() {
            Asset a = account("USD", new BigDecimal("1400"), 1_000L);

            assertThat(a.isForeignCurrency()).isTrue();
            assertThat(a.balanceInKrw(1_000L, 0L)).isEqualTo(1_400_000L);
        }

        @Test
        @DisplayName("엔화통장 ¥500,000, 환율 9.2 — 4,600,000원")
        void jpyAccount() {
            Asset a = account("JPY", new BigDecimal("9.2"), 500_000L);

            assertThat(a.balanceInKrw(500_000L, 0L)).isEqualTo(4_600_000L);
        }

        @Test
        @DisplayName("유로통장 €3,250, 환율 1,512.35 — 4,915,138원 (반올림)")
        void eurAccount() {
            Asset a = account("EUR", new BigDecimal("1512.35"), 3_250L);

            // 3,250 × 1,512.35 = 4,915,137.5 → 반올림 4,915,138
            assertThat(a.balanceInKrw(3_250L, 0L)).isEqualTo(4_915_138L);
        }

        @Test
        @DisplayName("잔고가 0이면 환율과 무관하게 0원")
        void zeroBalance() {
            assertThat(account("USD", new BigDecimal("1400"), 0L).balanceInKrw(0L, 0L)).isZero();
        }

        @Test
        @DisplayName("외화 마이너스 통장 -$200, 환율 1,400 — 순자산에서 280,000원 차감")
        void negativeBalance() {
            assertThat(account("USD", new BigDecimal("1400"), -200L).balanceInKrw(-200L, 0L))
                .isEqualTo(-280_000L);
        }

        @Test
        @DisplayName("환율이 바뀌면 같은 잔고의 환산액이 바뀐다 — 1,400 → 1,320")
        void rateChange() {
            Asset a = account("USD", new BigDecimal("1400"), 1_000L);
            assertThat(a.balanceInKrw(1_000L, 0L)).isEqualTo(1_400_000L);

            a.updateAsset("외화통장", AssetType.BANK_ACCOUNT, "USD", new BigDecimal("1320"),
                null, null, null, YNType.Y, null, null, null, null);

            assertThat(a.balanceInKrw(1_000L, 0L)).isEqualTo(1_320_000L);
        }
    }

    @Nested
    @DisplayName("원화 계좌 — 기존 데이터가 그대로여야 한다")
    class Krw {

        @Test
        @DisplayName("원화통장 3,500,000원 — 환산율 1, 값이 그대로다")
        void krwAccount() {
            Asset a = account("KRW", null, 3_500_000L);

            assertThat(a.isForeignCurrency()).isFalse();
            assertThat(a.getExchangeRate()).isEqualByComparingTo("1");
            assertThat(a.balanceInKrw(3_500_000L, 0L)).isEqualTo(3_500_000L);
        }

        @Test
        @DisplayName("원화인데 환율 1,400 을 잘못 넣어도 1로 막는다 — 순자산이 1,400배 되지 않는다")
        void krwIgnoresRate() {
            Asset a = account("KRW", new BigDecimal("1400"), 3_500_000L);

            assertThat(a.balanceInKrw(3_500_000L, 0L)).isEqualTo(3_500_000L);
        }
    }

    @Nested
    @DisplayName("잘못된 환율 방어")
    class InvalidRate {

        @Test
        @DisplayName("외화인데 환율을 안 넣으면 1 — 환산 없이 그대로 더해진다(순자산 0 방지)")
        void nullRateFallsBackToOne() {
            Asset a = account("USD", null, 1_000L);

            assertThat(a.getExchangeRate()).isEqualByComparingTo("1");
            assertThat(a.balanceInKrw(1_000L, 0L)).isEqualTo(1_000L);
        }

        @Test
        @DisplayName("환율 0 은 잔고를 통째로 지운다 — 1로 막는다")
        void zeroRateBlocked() {
            assertThat(account("USD", BigDecimal.ZERO, 1_000L).balanceInKrw(1_000L, 0L))
                .isEqualTo(1_000L);
        }

        @Test
        @DisplayName("음수 환율은 자산을 부채로 뒤집는다 — 1로 막는다")
        void negativeRateBlocked() {
            assertThat(account("USD", new BigDecimal("-1400"), 1_000L).balanceInKrw(1_000L, 0L))
                .isEqualTo(1_000L);
        }
    }

    @Test
    @DisplayName("순자산 시나리오 — 원화 350만 + 달러 $1,000(1,400) + 엔화 ¥500,000(9.2) = 9,500,000원")
    void netWorthAcrossCurrencies() {
        long krw = account("KRW", null, 3_500_000L).balanceInKrw(3_500_000L, 0L);
        long usd = account("USD", new BigDecimal("1400"), 1_000L).balanceInKrw(1_000L, 0L);
        long jpy = account("JPY", new BigDecimal("9.2"), 500_000L).balanceInKrw(500_000L, 0L);

        assertThat(krw + usd + jpy).isEqualTo(9_500_000L);
    }

    @Nested
    @DisplayName("외화 투자 자산 — 예수금만 환율, 보유 평가는 원화 (round(cash × 환율) + holding)")
    class ForeignInvestment {

        @Test
        @DisplayName("예수금 $100 · 환율 1,300 · 라이브 보유 10,000,000원 → 10,130,000원")
        void liveHolding() {
            Asset a = account("USD", new BigDecimal("1300"), 100L);

            assertThat(a.balanceInKrw(100L, 10_000_000L)).isEqualTo(10_130_000L);
        }

        @Test
        @DisplayName("예수금 $100 · 환율 1,300 · 수동 보유 5,000원 → 135,000원")
        void manualHolding() {
            Asset a = account("USD", new BigDecimal("1300"), 100L);

            assertThat(a.balanceInKrw(100L, 5_000L)).isEqualTo(135_000L);
        }

        @Test
        @DisplayName("보유 없는 외화 예수금만 $100 → 130,000원 (종전과 같다)")
        void cashOnly() {
            Asset a = account("USD", new BigDecimal("1300"), 100L);

            assertThat(a.balanceInKrw(100L, 0L)).isEqualTo(130_000L);
        }

        @Test
        @DisplayName("원화 자산은 cash + holding 그대로 — 환율 1")
        void krwUnchanged() {
            assertThat(account("KRW", null, 0L).balanceInKrw(300_000L, 700_000L)).isEqualTo(1_000_000L);
            assertThat(account("USD", BigDecimal.ONE, 0L).balanceInKrw(300_000L, 700_000L)).isEqualTo(1_000_000L);
        }

        @Test
        @DisplayName("예수금 반올림은 예수금에만 — 3,250 × 1,512.35 = 4,915,138 (반올림) + 보유 1,000")
        void roundsCashOnly() {
            Asset a = account("EUR", new BigDecimal("1512.35"), 3_250L);

            assertThat(a.balanceInKrw(3_250L, 1_000L)).isEqualTo(4_916_138L);
        }
    }
}
