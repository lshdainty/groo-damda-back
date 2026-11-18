package io.groo.damda.asset.service;

import io.groo.core.time.UserClock;
import io.groo.core.type.YNType;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.repository.AssetHoldingRepository;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.asset.service.dto.AssetServiceDto;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.repository.ExpenseRepository;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 체크카드의 이번 달 사용 합계(monthlyUsedAmount).
 *
 * <p>체크카드는 결제가 연결 계좌에서 즉시 빠져 잔액이 항상 0 이다 — 목록·상세가
 * "이 카드로 이번 달 얼마 썼는지" 를 보여줄 수 있게, 캘린더 월(1일~) 사용 합계를
 * 응답에 싣는다. 합산은 집계 규칙(예정 제외·환불 제외)을 그대로 따라야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckCardMonthlyUsedTest {

    @Mock private AssetRepository assetRepository;
    @Mock private AssetHoldingRepository assetHoldingRepository;
    @Mock private ExpenseRepository expenseRepository;
    @Mock private AssetBalanceHistoryService balanceHistoryService;
    @Mock private UserClock userClock;
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
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 12, 0);

    private User user;
    private Asset checkCard;
    private Asset bank;

    @BeforeEach
    void setUp() {
        user = User.createUser(1L, "tester", "테스터", "tester@hangroo.io");
        ReflectionTestUtils.setField(user, "rowId", USER_ID);
        given(userClock.now(USER_ID)).willReturn(NOW);

        checkCard = asset(20L, "국민체크카드", AssetType.CHECK_CARD);
        bank = asset(10L, "급여통장", AssetType.BANK_ACCOUNT);

        given(assetRepository.findByUser(USER_ID)).willReturn(List.of(bank, checkCard));
        given(assetRepository.findById(any())).willAnswer(inv -> {
            Long id = inv.getArgument(0);
            if (id.equals(checkCard.getRowId())) return Optional.of(checkCard);
            if (id.equals(bank.getRowId())) return Optional.of(bank);
            return Optional.empty();
        });
        given(assetHoldingRepository.findActiveByAssets(any())).willReturn(List.of());
        given(assetHoldingRepository.findActiveByAsset(any())).willReturn(List.of());
        given(balanceHistoryService.balancesAt(any(), any())).willReturn(Map.of());
        given(balanceHistoryService.balanceAt(any(), any()))
            .willReturn(new AssetBalanceHistoryService.Split(0L, 0L));
    }

    private Asset asset(long rowId, String name, AssetType type) {
        Asset a = Asset.createAsset(user, name, type, 0L, "KRW",
            null, null, null, null, 0, YNType.Y, null, null, null, null);
        ReflectionTestUtils.setField(a, "rowId", rowId);
        return a;
    }

    private Expense expense(Asset asset, ExpenseType type, long amount, LocalDateTime at) {
        Expense e = Expense.createExpense(user, null, asset, type, amount,
            "테스트", at, null, "CARD", null, null, null, null);
        return e;
    }

    @Test
    @DisplayName("체크카드 — 이번 달 사용 합계가 실린다 (환불 제외·예정 제외·타 자산 제외)")
    void monthlyUsedForCheckCard() {
        Expense spent1 = expense(checkCard, ExpenseType.EXPENSE, 30_000L, NOW.minusDays(3));
        Expense spent2 = expense(checkCard, ExpenseType.EXPENSE, 20_000L, NOW.minusDays(1));
        // 환불은 **원거래에 찍는 표식**이다 — 수입 행을 만들지 않고, 그 거래가 통째로 빠진다.
        Expense refunded = expense(checkCard, ExpenseType.EXPENSE, 5_000L, NOW.minusDays(1));
        refunded.markRefunded(NOW);
        // 아직 오지 않은 예정(반복거래 미리 생성분)은 안 센다.
        Expense future = expense(checkCard, ExpenseType.EXPENSE, 99_000L, NOW.plusDays(5));
        // 다른 자산(통장) 지출은 카드 사용액이 아니다.
        Expense other = expense(bank, ExpenseType.EXPENSE, 70_000L, NOW.minusDays(2));

        given(expenseRepository.findByDateRange(eq(USER_ID), any(LocalDate.class), any(LocalDate.class)))
            .willReturn(List.of(spent1, spent2, refunded, future, other));

        List<AssetServiceDto.AssetInfo> assets = sut.getAssets(USER_ID);

        AssetServiceDto.AssetInfo card = assets.stream()
            .filter(a -> a.assetType() == AssetType.CHECK_CARD).findFirst().orElseThrow();
        AssetServiceDto.AssetInfo account = assets.stream()
            .filter(a -> a.assetType() == AssetType.BANK_ACCOUNT).findFirst().orElseThrow();

        assertThat(card.monthlyUsedAmount()).isEqualTo(50_000L); // 30,000 + 20,000 (환불 5,000 은 아예 안 센다)
        assertThat(account.monthlyUsedAmount()).isNull();        // 체크카드가 아니면 없다
    }

    @Test
    @DisplayName("체크카드 단건 조회에도 같은 값이 실린다")
    void monthlyUsedOnSingleGet() {
        given(expenseRepository.findByDateRange(eq(USER_ID), any(LocalDate.class), any(LocalDate.class)))
            .willReturn(List.of(expense(checkCard, ExpenseType.EXPENSE, 12_345L, NOW.minusDays(1))));

        AssetServiceDto.AssetInfo card = sut.getAsset(checkCard.getRowId(), USER_ID);

        assertThat(card.monthlyUsedAmount()).isEqualTo(12_345L);
    }

    @Test
    @DisplayName("사용 내역이 없는 체크카드는 null — 화면이 0원 사용으로 그리면 된다")
    void noExpensesMeansNull() {
        given(expenseRepository.findByDateRange(eq(USER_ID), any(LocalDate.class), any(LocalDate.class)))
            .willReturn(List.of());

        List<AssetServiceDto.AssetInfo> assets = sut.getAssets(USER_ID);

        AssetServiceDto.AssetInfo card = assets.stream()
            .filter(a -> a.assetType() == AssetType.CHECK_CARD).findFirst().orElseThrow();
        assertThat(card.monthlyUsedAmount()).isNull();
    }
}
