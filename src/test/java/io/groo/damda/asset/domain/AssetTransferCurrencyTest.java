package io.groo.damda.asset.domain;

import io.groo.core.type.YNType;
import io.groo.damda.asset.type.AssetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 통화가 다른 두 자산 사이 이체 — 금액(amount · fee · interest)은 <b>보내는 계좌 통화</b>이고, 받는 쪽
 * 금액은 통화가 다를 때만 to_amount(받는 쪽 통화)로 박아 둔다.
 *
 * <p>환율: USD 1,300 · EUR 1,450. 원화는 1.
 */
class AssetTransferCurrencyTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 6, 10, 0);

    private static Asset asset(String currency, String rate) {
        return asset(AssetType.BANK_ACCOUNT, currency, rate);
    }

    private static Asset asset(AssetType type, String currency, String rate) {
        return Asset.createAsset(null, currency + "계좌", type, 0L, currency,
            rate != null ? new BigDecimal(rate) : null,
            null, null, null, 0, YNType.Y, null, null, null, null);
    }

    private static AssetTransfer transfer(Asset from, Asset to, long amount, long fee, long interest) {
        AssetTransfer t = AssetTransfer.createTransfer(null, from, to, amount, fee, interest, "이체", AT);
        t.resolveToAmount(null);
        return t;
    }

    @Nested
    @DisplayName("Asset 환산 헬퍼")
    class Helpers {

        @Test
        @DisplayName("원화 → 자산 통화: HALF_UP 정수, 부호 유지, 원화 · 환율 없음은 그대로")
        void krwToAssetCurrency() {
            Asset usd = asset("USD", "1300");
            assertThat(usd.krwToAssetCurrency(1_300_000L)).isEqualTo(1_000L);
            assertThat(usd.krwToAssetCurrency(1_000_000L)).isEqualTo(769L);   // 769.23
            assertThat(usd.krwToAssetCurrency(650L)).isEqualTo(1L);           // 0.5 → 1
            assertThat(usd.krwToAssetCurrency(-1_950L)).isEqualTo(-2L);       // −1.5 → −2(크기 기준 올림)
            assertThat(asset("KRW", null).krwToAssetCurrency(1_234_567L)).isEqualTo(1_234_567L);
            // 환율을 안 적은 외화(서버가 1 로 저장) · 0 이하 환율(1 로 막힘)은 나누지 않는다.
            assertThat(asset("USD", null).krwToAssetCurrency(5_000L)).isEqualTo(5_000L);
            assertThat(asset("USD", "0").krwToAssetCurrency(5_000L)).isEqualTo(5_000L);
            assertThat(asset("USD", "-3").krwToAssetCurrency(5_000L)).isEqualTo(5_000L);
        }

        @Test
        @DisplayName("자산 통화 → 원화: round(units × 환율)")
        void toKrw() {
            assertThat(asset("USD", "1300").toKrw(900L)).isEqualTo(1_170_000L);
            assertThat(asset("JPY", "9.15").toKrw(1_001L)).isEqualTo(9_159L);   // 9,159.15
            assertThat(asset("KRW", null).toKrw(900L)).isEqualTo(900L);
        }

        @Test
        @DisplayName("자산 통화 → 다른 자산 통화: 원화를 거쳐 반올림은 마지막 한 번")
        void convertTo() {
            Asset usd = asset("USD", "1300");
            Asset eur = asset("EUR", "1450");
            Asset krw = asset("KRW", null);
            assertThat(usd.convertTo(1_000L, eur)).isEqualTo(897L);          // 896.55
            assertThat(usd.convertTo(1_000L, krw)).isEqualTo(1_300_000L);
            assertThat(krw.convertTo(1_300_000L, usd)).isEqualTo(1_000L);
            assertThat(usd.convertTo(1_000L, asset("USD", "1310"))).isEqualTo(1_000L); // 같은 통화는 그대로
            assertThat(krw.convertTo(5_000L, krw)).isEqualTo(5_000L);
        }

        @Test
        @DisplayName("거래 금액: 같은 통화의 원 통화 금액이 있으면 그 값, 아니면 원화 ÷ 계좌 환율")
        void expenseAmount() {
            Asset usd = asset("USD", "1300");
            // $5 결제(거래 환율 1,310 → 6,550원) — 계좌 환율로 다시 나누지 않고 $5.
            assertThat(usd.expenseAmountInAssetCurrency(6_550L, new BigDecimal("5.0000"), "USD")).isEqualTo(5L);
            assertThat(usd.expenseAmountInAssetCurrency(6_825L, new BigDecimal("5.2500"), "usd")).isEqualTo(5L);
            // 원화 결제 13,000원 → $10
            assertThat(usd.expenseAmountInAssetCurrency(13_000L, null, null)).isEqualTo(10L);
            // EUR 결제(14,500원)를 USD 계좌로 → 원화 ÷ 1,300 = 11.15 → 11
            assertThat(usd.expenseAmountInAssetCurrency(14_500L, new BigDecimal("10"), "EUR")).isEqualTo(11L);
            // 원화 계좌는 해외 결제여도 원화 금액 그대로(지금과 같음)
            assertThat(asset("KRW", null).expenseAmountInAssetCurrency(6_550L, new BigDecimal("5"), "USD"))
                .isEqualTo(6_550L);
        }
    }

    @Nested
    @DisplayName("이체 조합 — 금액은 보내는 계좌 통화")
    class Combos {

        @Test
        @DisplayName("원 → 원: to_amount NULL, 출금 −(amount+fee), 입금 +amount — 지금과 같다")
        void krwToKrw() {
            AssetTransfer t = transfer(asset("KRW", null), asset("KRW", null), 50_000L, 500L, 0L);
            assertThat(t.getToAmount()).isNull();
            assertThat(t.fromBalanceDelta()).isEqualTo(-50_500L);
            assertThat(t.toBalanceDelta()).isEqualTo(50_000L);
        }

        @Test
        @DisplayName("원 → USD 1,300,000원 · 수수료 1,000원: to_amount 1,000, 통장 −1,301,000원")
        void krwToUsd() {
            AssetTransfer t = transfer(asset("KRW", null), asset("USD", "1300"), 1_300_000L, 1_000L, 0L);
            assertThat(t.getToAmount()).isEqualTo(1_000L);
            assertThat(t.fromBalanceDelta()).isEqualTo(-1_301_000L);
            assertThat(t.toBalanceDelta()).isEqualTo(1_000L);
        }

        @Test
        @DisplayName("USD → 원 $1,000 · 수수료 $1: 달러 계좌 −$1,001, 통장 +1,300,000원")
        void usdToKrw() {
            AssetTransfer t = transfer(asset("USD", "1300"), asset("KRW", null), 1_000L, 1L, 0L);
            assertThat(t.getToAmount()).isEqualTo(1_300_000L);
            assertThat(t.fromBalanceDelta()).isEqualTo(-1_001L);
            assertThat(t.toBalanceDelta()).isEqualTo(1_300_000L);
        }

        @Test
        @DisplayName("USD → EUR $1,000: −$1,000 · +€897 (1,000 × 1,300 ÷ 1,450 = 896.55)")
        void usdToEur() {
            AssetTransfer t = transfer(asset("USD", "1300"), asset("EUR", "1450"), 1_000L, 0L, 0L);
            assertThat(t.getToAmount()).isEqualTo(897L);
            assertThat(t.fromBalanceDelta()).isEqualTo(-1_000L);
            assertThat(t.toBalanceDelta()).isEqualTo(897L);
        }

        @Test
        @DisplayName("USD → USD(환율이 다른 계좌) $1,000: to_amount NULL, 보낸 만큼 받는다")
        void usdToUsdDifferentRate() {
            AssetTransfer t = transfer(asset("USD", "1300"), asset("USD", "1310"), 1_000L, 0L, 0L);
            assertThat(t.getToAmount()).isNull();
            assertThat(t.fromBalanceDelta()).isEqualTo(-1_000L);
            assertThat(t.toBalanceDelta()).isEqualTo(1_000L);
        }

        @Test
        @DisplayName("대출 상환(원 → 원): 입금은 원금만 — 500,000 중 이자 150,000 은 빠진다(지금과 같다)")
        void loanInterestUnchanged() {
            AssetTransfer t = transfer(asset("KRW", null), asset(AssetType.LOAN, "KRW", null), 500_000L, 0L, 150_000L);
            assertThat(t.getToAmount()).isNull();
            assertThat(t.fromBalanceDelta()).isEqualTo(-500_000L);
            assertThat(t.toBalanceDelta()).isEqualTo(350_000L);
        }

        @Test
        @DisplayName("원 → USD 대출 1,000,000 · 이자 650: 원금을 한 번에 환산 round(999,350 ÷ 1,300 = 768.73) = 769")
        void foreignLoanInterestRoundsOnce() {
            AssetTransfer t = transfer(asset("KRW", null), asset(AssetType.LOAN, "USD", "1300"),
                1_000_000L, 0L, 650L);
            assertThat(t.getToAmount()).isEqualTo(769L);
            assertThat(t.toBalanceDelta()).isEqualTo(769L);   // 두 번 반올림하면 769 − 1 = 768
        }

        @Test
        @DisplayName("서버가 받는 금액을 못 박으면(매수 충당 · 카드 결제) 그 값을 쓴다 — 같은 통화면 무시하고 NULL")
        void override() {
            AssetTransfer cross = AssetTransfer.createTransfer(null, asset("KRW", null), asset("USD", "1300"),
                1_170_000L, 0L, 0L, "충당", AT);
            cross.resolveToAmount(900L);
            assertThat(cross.getToAmount()).isEqualTo(900L);
            assertThat(cross.toBalanceDelta()).isEqualTo(900L);

            AssetTransfer same = AssetTransfer.createTransfer(null, asset("KRW", null), asset("KRW", null),
                10_000L, 0L, 0L, "충당", AT);
            same.resolveToAmount(10_000L);
            assertThat(same.getToAmount()).isNull();
        }

        @Test
        @DisplayName("수정해 같은 통화로 바꾸면 to_amount 가 NULL 로 돌아간다")
        void updateClearsToAmount() {
            Asset krw = asset("KRW", null);
            AssetTransfer t = transfer(krw, asset("USD", "1300"), 1_300_000L, 0L, 0L);
            assertThat(t.getToAmount()).isEqualTo(1_000L);

            t.updateTransfer(krw, asset("KRW", null), 70_000L, 0L, 0L, "이체", AT);
            t.resolveToAmount(null);
            assertThat(t.getToAmount()).isNull();
            assertThat(t.toBalanceDelta()).isEqualTo(70_000L);
        }

        @Test
        @DisplayName("sameMoneyAs — 두 계좌 · 금액 · 수수료 · 이자가 같을 때만 true")
        void sameMoney() {
            Asset krw = asset("KRW", null);
            Asset usd = asset("USD", "1300");
            org.springframework.test.util.ReflectionTestUtils.setField(krw, "rowId", 1L);
            org.springframework.test.util.ReflectionTestUtils.setField(usd, "rowId", 2L);
            AssetTransfer t = transfer(krw, usd, 1_300_000L, 0L, 0L);
            assertThat(t.sameMoneyAs(krw, usd, 1_300_000L, null, 0L)).isTrue();
            assertThat(t.sameMoneyAs(krw, usd, 1_300_001L, 0L, 0L)).isFalse();
            assertThat(t.sameMoneyAs(krw, usd, 1_300_000L, 1L, 0L)).isFalse();
            assertThat(t.sameMoneyAs(usd, krw, 1_300_000L, 0L, 0L)).isFalse();
        }
    }
}
