package io.groo.damda.asset.repository;

import io.groo.core.type.YNType;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.domain.AssetBalanceHistory;
import io.groo.damda.asset.service.AssetBalanceHistoryService;
import io.groo.damda.asset.service.AssetBalanceHistoryService.Split;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.asset.type.BalanceChannel;
import io.groo.damda.asset.type.BalanceSourceType;
import io.groo.damda.common.config.database.JpaAuditingConfig;
import io.groo.damda.common.config.database.LoginUserAuditorAware;
import io.groo.damda.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 잔액 집계 쿼리가 기존 자바 계산과 <b>같은 값</b>을 내는지 대조한다.
 *
 * <p>기준은 테스트가 직접 들고 있는 단순 구현({@link #expected})이다. 프로덕션에는 자바 계산이
 * 없으므로(전부 SQL) 둘 다 같은 코드를 쓰면 대조가 아무것도 증명하지 못한다.
 *
 * <p>대조 대상은 값만이 아니라 규칙이다 — 마지막 절대 앵커가 그 앞을 덮는지, 같은 시각 앵커
 * 둘 중 뒤엣것을 쓰는지, 소급 입력된 행이 시각 순서대로 끼는지, 채널이 서로를 안 건드리는지.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, LoginUserAuditorAware.class})
@ActiveProfiles("test")
@DisplayName("잔액 집계 쿼리 ↔ 자바 계산 대조")
class BalanceAggregateParityTest {

    @Autowired private TestEntityManager em;
    @Autowired private AssetBalanceHistoryRepository repository;

    /**
     * 읽기 전용 집계만 쓰므로 저장소만 주면 된다 — 슬라이스에 없는 빈(UserClock 등)을
     * 끌어오지 않으려고 직접 만든다. recompute 계열은 이 테스트에서 호출하지 않는다.
     */
    private AssetBalanceHistoryService service;

    private User user;
    private Asset asset;
    private Asset other;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 23, 59);

    @BeforeEach
    void setUp() {
        service = new AssetBalanceHistoryService(repository, null, null, null);
        user = em.persist(User.createUser(null, "tester", "테스터", "tester@porest.com"));
        asset = persistAsset("증권계좌");
        other = persistAsset("급여통장");
    }

    private Asset persistAsset(String name) {
        return em.persist(Asset.createAsset(user, name, AssetType.BANK_ACCOUNT, 0L, "KRW",
            null, null, null, null, 0, YNType.Y, null, null, null, null));
    }

    private AssetBalanceHistory row(Asset target, BalanceSourceType type, BalanceChannel channel,
                                    long amount, LocalDateTime at) {
        return em.persist(AssetBalanceHistory.of(user, target, type, channel, null, amount, at));
    }

    private LocalDateTime at(int month, int day) {
        return LocalDateTime.of(2026, month, day, 12, 0);
    }

    /**
     * 같은 이력을 두 경로로 계산해 비교한다.
     * 자바 쪽은 실제 운영과 같은 정렬(effective_at, row_id 오름차순)로 넣는다.
     */
    private void assertParity(Asset... targets) {
        em.flush();
        em.clear();

        List<Long> ids = java.util.Arrays.stream(targets).map(Asset::getRowId).toList();

        Map<Long, Split> queried = toSplits(repository.aggregateBalances(ids, NOW));

        for (Long id : ids) {
            assertThat(queried.getOrDefault(id, Split.ZERO))
                .as("자산 %d — 집계 쿼리가 규칙과 다른 값을 냈다", id)
                .isEqualTo(expected(id, NOW));
        }
    }

    /**
     * 대조용 기준 구현 — 규칙을 가장 단순하게 옮긴 것.
     *
     * <p>프로덕션에는 이 계산이 없다(전부 SQL 이다). 그래서 테스트가 자기 기준을 들고
     * 쿼리와 맞춰 본다 — 둘 다 같은 코드를 쓰면 대조가 아무것도 증명하지 못한다.
     *
     * <p>규칙: (기준시각 이하 마지막 절대 앵커) + (그 앵커 이후 flow 합). 채널은 서로 독립.
     */
    private Split expected(Long assetRowId, LocalDateTime at) {
        List<AssetBalanceHistory> rows = repository.findActiveByAssetIds(List.of(assetRowId), YNType.N);
        long cash = 0, holding = 0;
        for (AssetBalanceHistory h : rows) { // effective_at, row_id 오름차순으로 온다
            if (h.getEffectiveAt().isAfter(at)) {
                break;
            }
            boolean anchor = h.isAbsolute();
            if (h.isHolding()) {
                holding = anchor ? h.getAmount() : holding + h.getAmount();
            } else {
                cash = anchor ? h.getAmount() : cash + h.getAmount();
            }
        }
        return new Split(cash, holding);
    }


    private Map<Long, Split> toSplits(List<Object[]> rows) {
        Map<Long, long[]> acc = new LinkedHashMap<>();
        for (Object[] r : rows) {
            long assetId = ((Number) r[0]).longValue();
            BalanceChannel channel = BalanceChannel.valueOf((String) r[1]);
            long amount = ((Number) r[2]).longValue();
            long[] pair = acc.computeIfAbsent(assetId, k -> new long[2]);
            if (channel == BalanceChannel.HOLDING) {
                pair[1] = amount;
            } else {
                pair[0] = amount;
            }
        }
        Map<Long, Split> out = new LinkedHashMap<>();
        acc.forEach((k, v) -> out.put(k, new Split(v[0], v[1])));
        return out;
    }

    @Nested
    @DisplayName("일반 계좌")
    class PlainAccount {

        @Test
        @DisplayName("이력이 없으면 0")
        void empty() {
            assertParity(asset);
        }

        @Test
        @DisplayName("초기 잔액 + 지출 몇 건")
        void initAndExpenses() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 3_000_000L, at(1, 1));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -45_000L, at(2, 12));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -1_200_000L, at(3, 5));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, 2_800_000L, at(3, 25)); // 급여
            assertParity(asset);
        }

        @Test
        @DisplayName("수동 조정이 그 앞을 덮는다 — 안 적은 지출이 있어도 그 시점부터 맞는다")
        void manualAnchorOverridesPast() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 3_000_000L, at(1, 1));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -45_000L, at(2, 12));
            row(asset, BalanceSourceType.MANUAL, BalanceChannel.CASH, 2_500_000L, at(3, 1));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -30_000L, at(4, 12));
            assertParity(asset);
        }

        @Test
        @DisplayName("수동 조정이 여러 번 — 마지막 것만 유효하다")
        void multipleManualAnchors() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 1_000_000L, at(1, 1));
            row(asset, BalanceSourceType.MANUAL, BalanceChannel.CASH, 900_000L, at(2, 1));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -10_000L, at(2, 15));
            row(asset, BalanceSourceType.MANUAL, BalanceChannel.CASH, 850_000L, at(3, 1));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -20_000L, at(3, 15));
            assertParity(asset);
        }

        @Test
        @DisplayName("앵커가 하나도 없으면 flow 전부 합산")
        void flowsWithoutAnchor() {
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -10_000L, at(2, 1));
            row(asset, BalanceSourceType.TRANSFER, BalanceChannel.CASH, 500_000L, at(3, 1));
            assertParity(asset);
        }

        @Test
        @DisplayName("잔액이 마이너스로 내려가도 같다")
        void negative() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 0L, at(1, 1));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -448_600L, at(3, 12));
            assertParity(asset);
        }
    }

    @Nested
    @DisplayName("투자 계좌 — 예수금/평가금액 두 채널")
    class Investment {

        @Test
        @DisplayName("입금 후 평가 스냅샷이 돌아도 예수금이 남는다")
        void cashSurvivesValuation() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 0L, at(8, 1));
            row(asset, BalanceSourceType.VALUATION, BalanceChannel.HOLDING, 800_000L, at(8, 3));
            row(asset, BalanceSourceType.TRANSFER, BalanceChannel.CASH, 1_000_000L, at(8, 4));
            row(asset, BalanceSourceType.VALUATION, BalanceChannel.HOLDING, 800_000L, at(8, 5));
            assertParity(asset);
        }

        @Test
        @DisplayName("매수·매도가 섞인 흐름")
        void buyAndSell() {
            row(asset, BalanceSourceType.TRANSFER, BalanceChannel.CASH, 1_000_000L, at(8, 1));
            row(asset, BalanceSourceType.TRADE, BalanceChannel.CASH, -600_000L, at(8, 2));
            row(asset, BalanceSourceType.VALUATION, BalanceChannel.HOLDING, 600_000L, at(8, 2));
            row(asset, BalanceSourceType.VALUATION, BalanceChannel.HOLDING, 750_000L, at(8, 5));
            row(asset, BalanceSourceType.TRADE, BalanceChannel.CASH, 750_000L, at(8, 6));
            row(asset, BalanceSourceType.VALUATION, BalanceChannel.HOLDING, 0L, at(8, 6));
            assertParity(asset);
        }

        @Test
        @DisplayName("예수금 앵커는 평가금액을 안 건드린다 — 채널이 서로 독립")
        void channelsAreIndependent() {
            row(asset, BalanceSourceType.VALUATION, BalanceChannel.HOLDING, 5_000_000L, at(8, 1));
            row(asset, BalanceSourceType.TRANSFER, BalanceChannel.CASH, 300_000L, at(8, 2));
            row(asset, BalanceSourceType.MANUAL, BalanceChannel.CASH, 250_000L, at(8, 3));
            assertParity(asset);
        }

        @Test
        @DisplayName("평가금액만 있고 예수금 기록이 없어도 같다")
        void holdingOnly() {
            row(asset, BalanceSourceType.VALUATION, BalanceChannel.HOLDING, 800_000L, at(8, 1));
            assertParity(asset);
        }
    }

    @Nested
    @DisplayName("순서·경계")
    class Ordering {

        @Test
        @DisplayName("같은 시각에 앵커가 둘이면 뒤에 저장된 쪽이 이긴다")
        void sameInstantAnchorsTakeLater() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 1_000_000L, at(1, 1));
            row(asset, BalanceSourceType.MANUAL, BalanceChannel.CASH, 500_000L, at(3, 1));
            row(asset, BalanceSourceType.MANUAL, BalanceChannel.CASH, 700_000L, at(3, 1)); // 같은 시각
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -50_000L, at(4, 1));
            assertParity(asset);
        }

        @Test
        @DisplayName("앵커와 flow 가 같은 시각이면 저장 순서대로")
        void sameInstantAnchorAndFlow() {
            row(asset, BalanceSourceType.MANUAL, BalanceChannel.CASH, 1_000_000L, at(3, 1));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -20_000L, at(3, 1));
            assertParity(asset);
        }

        @Test
        @DisplayName("INIT 와 같은 시각의 flow — 자산 생성 직후 같은 분에 기록한 거래가 포함된다")
        void sameInstantInitAndFlow() {
            // 벽시계 거래는 분 단위(:00)이고 INIT 앵커도 분 시작으로 내려 저장된다
            // (recordInit). 같은 시각 tie 는 row_id 로 갈라 INIT 뒤의 flow 가 산다 —
            // dev 에서 계좌 생성 직후의 이체 입금이 잔액에서 빠졌던 케이스의 고정.
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 500_000L, at(3, 1));
            row(asset, BalanceSourceType.TRANSFER, BalanceChannel.CASH, 100_000L, at(3, 1));
            assertParity(asset);

            Map<Long, Split> got = toSplits(repository.aggregateBalances(List.of(asset.getRowId()), NOW));
            assertThat(got.get(asset.getRowId()).cash()).isEqualTo(600_000L);
        }

        @Test
        @DisplayName("소급 입력 — 1월 지출을 8월에 넣어도 시각 순서대로 끼어든다")
        void backdatedRow() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 3_000_000L, at(1, 1));
            row(asset, BalanceSourceType.MANUAL, BalanceChannel.CASH, 2_500_000L, at(3, 1));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -30_000L, at(4, 12));
            // 뒤늦게 입력 — row_id 는 가장 크지만 effective_at 은 앵커보다 앞이라 묻혀야 한다
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -50_000L, at(1, 15));
            assertParity(asset);
        }

        @Test
        @DisplayName("소급 입력이 마지막 앵커 뒤에 떨어지면 반영된다")
        void backdatedAfterAnchor() {
            row(asset, BalanceSourceType.MANUAL, BalanceChannel.CASH, 2_500_000L, at(3, 1));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -30_000L, at(6, 12));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -50_000L, at(4, 15)); // 뒤늦게
            assertParity(asset);
        }

        @Test
        @DisplayName("기준시각 이후 기록은 빠진다")
        void futureRowsExcluded() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 1_000_000L, at(1, 1));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -10_000L, at(8, 30));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -999_999L,
                LocalDateTime.of(2026, 9, 5, 12, 0)); // 기준시각(8/31) 이후
            assertParity(asset);
        }

        @Test
        @DisplayName("기준시각 이후의 앵커도 빠진다 — 미래 앵커가 현재를 덮으면 안 된다")
        void futureAnchorExcluded() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 1_000_000L, at(1, 1));
            row(asset, BalanceSourceType.MANUAL, BalanceChannel.CASH, 7_777L,
                LocalDateTime.of(2026, 9, 5, 12, 0));
            assertParity(asset);
        }
    }

    @Nested
    @DisplayName("삭제·다중 자산")
    class DeletedAndMulti {

        @Test
        @DisplayName("soft-delete 된 행은 양쪽 모두 무시한다")
        void softDeletedIgnored() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 1_000_000L, at(1, 1));
            AssetBalanceHistory removed =
                row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -500_000L, at(2, 1));
            removed.softDelete();
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -10_000L, at(3, 1));
            assertParity(asset);
        }

        @Test
        @DisplayName("삭제된 앵커는 앵커 노릇을 못 한다 — 그 앞 flow 가 되살아난다")
        void softDeletedAnchorIgnored() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 1_000_000L, at(1, 1));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -100_000L, at(2, 1));
            AssetBalanceHistory anchor =
                row(asset, BalanceSourceType.MANUAL, BalanceChannel.CASH, 500_000L, at(3, 1));
            anchor.softDelete();
            assertParity(asset);
        }

        @Test
        @DisplayName("자산 여러 개를 한 번에 — 서로 섞이지 않는다")
        void multipleAssets() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 1_000_000L, at(1, 1));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -10_000L, at(2, 1));
            row(other, BalanceSourceType.INIT, BalanceChannel.CASH, 3_000_000L, at(1, 1));
            row(other, BalanceSourceType.MANUAL, BalanceChannel.CASH, 2_500_000L, at(3, 1));
            row(other, BalanceSourceType.TRANSFER, BalanceChannel.CASH, -1_000_000L, at(4, 1));
            assertParity(asset, other);
        }

        @Test
        @DisplayName("이력이 있는 자산과 없는 자산이 섞여도 같다")
        void mixedEmptyAndFilled() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 1_000_000L, at(1, 1));
            assertParity(asset, other);
        }
    }

    @Test
    @DisplayName("무작위 이력 200건 — 두 경로가 항상 같은 값을 낸다")
    void randomizedParity() {
        seedRandomHistory();
        assertParity(asset, other);
    }

    /** 씨앗 고정 무작위 이력 — 실패하면 항상 같은 이력으로 재현된다. */
    private void seedRandomHistory() {
        // 씨앗 고정 — 실패하면 항상 같은 이력으로 재현된다.
        long seed = 20260805L;
        List<BalanceSourceType> types = List.of(
            BalanceSourceType.EXPENSE, BalanceSourceType.TRANSFER, BalanceSourceType.TRADE,
            BalanceSourceType.MANUAL, BalanceSourceType.VALUATION);
        List<Asset> targets = List.of(asset, other);
        List<LocalDateTime> instants = new ArrayList<>();
        for (int m = 1; m <= 8; m++) {
            for (int d = 1; d <= 28; d += 7) {
                instants.add(at(m, d));
            }
        }
        instants.sort(Comparator.naturalOrder());

        long state = seed;
        for (int i = 0; i < 200; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            // 상위 비트를 31비트로 잘라 음수를 원천 차단 — Math.abs(Integer.MIN_VALUE) 는 음수다.
            int r = (int) ((state >>> 16) & 0x7fffffffL);
            BalanceSourceType type = types.get(r % types.size());
            Asset target = targets.get((r / 7) % targets.size());
            // 평가금액은 HOLDING, 나머지는 CASH — 운영에서 나오는 조합 그대로.
            BalanceChannel channel = type == BalanceSourceType.VALUATION
                ? BalanceChannel.HOLDING : BalanceChannel.CASH;
            long amount = (r % 2 == 0 ? 1 : -1) * (long) (r % 900_000 + 1_000);
            if (type == BalanceSourceType.MANUAL || type == BalanceSourceType.VALUATION) {
                amount = Math.abs(amount); // 앵커는 절대 잔액이라 음수로 두지 않는다
            }
            // 시각은 무작위로 골라 소급 입력이 자연스럽게 섞이게 한다.
            row(target, type, channel, amount, instants.get((r / 13) % instants.size()));
        }
    }

    // === 추이(여러 시점) 대조 ====================================================

    /** 시점 여러 개를 자바 계산과 나란히 비교한다 — 추이 그래프가 쓰는 경로. */
    private void assertTrendParity(List<LocalDateTime> points, Asset... targets) {
        em.flush();
        em.clear();
        List<Long> ids = java.util.Arrays.stream(targets).map(Asset::getRowId).toList();

        Map<Long, List<Split>> queried = service.balancesAtPoints(
            java.util.Arrays.asList(targets), points);

        for (Long id : ids) {
            List<Split> sql = queried.getOrDefault(id, List.of());
            assertThat(sql).as("자산 %d — 시점 개수가 다르다", id).hasSize(points.size());
            for (int i = 0; i < points.size(); i++) {
                assertThat(sql.get(i))
                    .as("자산 %d, 시점 %s — 집계 쿼리가 규칙과 다른 값을 냈다", id, points.get(i))
                    .isEqualTo(expected(id, points.get(i)));
            }
        }
    }

    /** 월말 시점들 — 순자산 추이가 쓰는 모양. */
    private List<LocalDateTime> monthEnds(int fromMonth, int toMonth) {
        List<LocalDateTime> points = new ArrayList<>();
        for (int m = fromMonth; m <= toMonth; m++) {
            points.add(java.time.LocalDate.of(2026, m, 1)
                .with(java.time.temporal.TemporalAdjusters.lastDayOfMonth())
                .atTime(java.time.LocalTime.MAX));
        }
        return points;
    }

    @Nested
    @DisplayName("추이 — 여러 시점")
    class Trend {

        @Test
        @DisplayName("초기 잔액 + 매달 지출")
        void monthlyExpenses() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 3_000_000L, at(1, 1));
            for (int m = 2; m <= 8; m++) {
                row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -100_000L * m, at(m, 10));
            }
            assertTrendParity(monthEnds(1, 8), asset);
        }

        @Test
        @DisplayName("중간에 수동 조정이 끼면 그 시점부터 값이 갈아엎힌다")
        void manualAnchorMidTrend() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 3_000_000L, at(1, 1));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -500_000L, at(2, 10));
            row(asset, BalanceSourceType.MANUAL, BalanceChannel.CASH, 1_000_000L, at(4, 15));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -70_000L, at(5, 10));
            assertTrendParity(monthEnds(1, 8), asset);
        }

        @Test
        @DisplayName("같은 날 앵커와 flow 가 섞여도 같다 — 앵커 앞의 flow 는 묻힌다")
        void anchorAndFlowSameDay() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 1_000_000L, at(1, 1));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -30_000L,
                LocalDateTime.of(2026, 4, 15, 9, 0));   // 앵커 앞 — 묻힘
            row(asset, BalanceSourceType.MANUAL, BalanceChannel.CASH, 800_000L,
                LocalDateTime.of(2026, 4, 15, 12, 0));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -20_000L,
                LocalDateTime.of(2026, 4, 15, 18, 0));  // 앵커 뒤 — 반영
            assertTrendParity(monthEnds(1, 8), asset);
        }

        @Test
        @DisplayName("투자 계좌 — 예수금과 평가금액이 각자 흐른다")
        void investmentTrend() {
            row(asset, BalanceSourceType.TRANSFER, BalanceChannel.CASH, 1_000_000L, at(2, 1));
            row(asset, BalanceSourceType.VALUATION, BalanceChannel.HOLDING, 600_000L, at(2, 2));
            row(asset, BalanceSourceType.TRADE, BalanceChannel.CASH, -600_000L, at(2, 2));
            row(asset, BalanceSourceType.VALUATION, BalanceChannel.HOLDING, 750_000L, at(4, 1));
            row(asset, BalanceSourceType.VALUATION, BalanceChannel.HOLDING, 900_000L, at(6, 1));
            assertTrendParity(monthEnds(1, 8), asset);
        }

        @Test
        @DisplayName("첫 시점보다 앞선 이력은 시작값에 접혀 들어간다")
        void historyBeforeWindow() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 5_000_000L,
                LocalDateTime.of(2025, 3, 1, 12, 0));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -1_000_000L,
                LocalDateTime.of(2025, 8, 1, 12, 0));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -50_000L, at(3, 10));
            assertTrendParity(monthEnds(1, 8), asset);
        }

        @Test
        @DisplayName("자산 여러 개의 추이를 한 번에")
        void multiAssetTrend() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 1_000_000L, at(1, 1));
            row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -100_000L, at(3, 1));
            row(other, BalanceSourceType.INIT, BalanceChannel.CASH, 3_000_000L, at(1, 1));
            row(other, BalanceSourceType.MANUAL, BalanceChannel.CASH, 2_000_000L, at(5, 1));
            row(other, BalanceSourceType.TRANSFER, BalanceChannel.CASH, -500_000L, at(7, 1));
            assertTrendParity(monthEnds(1, 8), asset, other);
        }

        @Test
        @DisplayName("주간 시점 26개 — 자산 잔액 추이가 쓰는 모양")
        void weeklyPoints() {
            row(asset, BalanceSourceType.INIT, BalanceChannel.CASH, 2_000_000L, at(1, 1));
            for (int m = 2; m <= 8; m++) {
                row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -33_000L, at(m, 7));
                row(asset, BalanceSourceType.EXPENSE, BalanceChannel.CASH, -47_000L, at(m, 21));
            }
            row(asset, BalanceSourceType.MANUAL, BalanceChannel.CASH, 1_500_000L, at(5, 15));

            List<LocalDateTime> weeks = new ArrayList<>();
            java.time.LocalDate monday = java.time.LocalDate.of(2026, 3, 2);
            for (int i = 0; i < 26; i++) {
                weeks.add(monday.plusWeeks(i).plusDays(6).atTime(java.time.LocalTime.MAX));
            }
            assertTrendParity(weeks, asset);
        }

        @Test
        @DisplayName("무작위 이력 200건의 추이도 같다")
        void randomizedTrend() {
            seedRandomHistory();
            assertTrendParity(monthEnds(1, 8), asset, other);
        }
    }
}
