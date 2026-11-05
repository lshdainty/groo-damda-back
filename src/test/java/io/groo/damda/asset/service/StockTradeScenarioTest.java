package io.groo.damda.asset.service;

import io.groo.core.time.UserClock;
import io.groo.core.type.YNType;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.domain.AssetHolding;
import io.groo.damda.asset.domain.AssetTrade;
import io.groo.damda.asset.repository.AssetHoldingRepository;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.asset.repository.AssetTradeRepository;
import io.groo.damda.asset.service.dto.AssetServiceDto;
import io.groo.damda.asset.service.dto.AssetTradeServiceDto.CreateTradeCommand;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.asset.type.HoldingType;
import io.groo.damda.asset.type.TradeType;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.repository.ExpenseRepository;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 증권계좌를 현실 그대로 굴려본다 — 입금 → 매수 → 시세 변동 → 부분 매도 → 전량 매도.
 *
 * <p>매 단계에서 예수금·수량·원가·평단가·실현손익이 맞는지 본다. 총액만 맞고 칸이 틀리면
 * 화면 숫자가 어긋나므로(웹 목록은 예수금 + 라이브 평가액으로 총액을 다시 조립한다)
 * 칸별로 확인한다.
 *
 * <p>원가는 이동평균이고 수수료는 매수 시 원가에 들어간다. 매도 수수료는 대금에서 뺀다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockTradeScenarioTest {

    @Mock private AssetTradeRepository tradeRepository;
    @Mock private AssetHoldingRepository holdingRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExpenseRepository expenseRepository;
    @Mock private AssetBalanceHistoryService balanceHistoryService;
    @Mock private UserClock userClock;
    @Mock private AssetService assetService;

    /** 예수금 충당 이체를 흉내 낸다 — 통장은 빠지고 예수금은 들어온다. */
    /** amount = 통장에서 나간 원화, credited = 예수금에 들어온 자산 통화 금액(to_amount 가 있으면 그 값). */
    private record Funding(long rowId, long amount, long credited) {}

    private final List<Funding> transfers = new ArrayList<>();
    private final List<Long> deletedTransferIds = new ArrayList<>();
    /** recordTrade 가 거래마다 예수금에 남긴 flow — 취소는 이 값을 그대로 되돌린다. */
    private final java.util.Map<Long, Long> recordedCash = new java.util.HashMap<>();
    // 시장코드 확정은 mock 기본값(null) — 확정 못 한 경우와 같다.
    @Mock private io.groo.damda.stock.service.StockMasterResolver stockMasterResolver;
    @InjectMocks private AssetTradeServiceImpl sut;

    private static final long USER_ID = 1L;
    private static final long ASSET_ID = 11L;
    private static final String SAMSUNG = "005930";
    private static final long BANK_ID = 10L;

    private User user;
    private Asset account;
    /** 인메모리 보유 목록 — 저장소 대용. */
    private final List<AssetHolding> holdings = new ArrayList<>();
    private final List<AssetTrade> trades = new ArrayList<>();
    private final List<Expense> expenses = new ArrayList<>();
    /** 예수금 — recordTrade 가 flow 를 남기면 여기에 누적한다. */
    private long cash;
    /** HOLDING 채널 — recordValuation 이 통째로 갈아 끼운다(앵커). */
    private long holdingValuation;
    /** 결제 계좌(통장)로 나간 flow 누적 — 예수금과 따로 본다. */
    private long bankFlow;
    private Asset bank;

    @BeforeEach
    void setUp() {
        user = User.createUser(1L, "tester", "테스터", "tester@porest.cloud");
        ReflectionTestUtils.setField(user, "rowId", USER_ID);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(userClock.now(USER_ID)).willReturn(LocalDateTime.of(2026, 8, 31, 0, 0));

        account = Asset.createAsset(user, "주식계좌", AssetType.INVESTMENT, 0L, "KRW",
            null, null, null, null, 0, YNType.Y, null, null, null, null);
        ReflectionTestUtils.setField(account, "rowId", ASSET_ID);
        given(assetRepository.findById(ASSET_ID)).willReturn(Optional.of(account));

        bank = Asset.createAsset(user, "급여통장", AssetType.BANK_ACCOUNT, 0L, "KRW",
            null, null, null, null, 0, YNType.Y, null, null, null, null);
        ReflectionTestUtils.setField(bank, "rowId", BANK_ID);
        given(assetRepository.findById(BANK_ID)).willReturn(Optional.of(bank));

        given(holdingRepository.findActiveByAsset(ASSET_ID)).willAnswer(inv -> List.copyOf(holdings));
        willAnswer(inv -> {
            AssetHolding h = inv.getArgument(0);
            if (!holdings.contains(h)) {
                holdings.add(h);
            }
            return h;
        }).given(holdingRepository).save(any(AssetHolding.class));

        willAnswer(inv -> {
            AssetTrade t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "rowId", (long) (trades.size() + 1));
            trades.add(t);
            return t;
        }).given(tradeRepository).save(any(AssetTrade.class));
        given(tradeRepository.findById(any())).willAnswer(inv -> {
            Long id = inv.getArgument(0);
            return trades.stream().filter(t -> id.equals(t.getRowId())).findFirst();
        });
        // 재계산용 조회 — 그 종목의 활성 거래를 (거래일시, row_id) 오름차순으로.
        given(tradeRepository.findForReplay(any(), any(), any())).willAnswer(inv -> {
            Long holdingRowId = inv.getArgument(1);
            String key = inv.getArgument(2);
            return trades.stream()
                .filter(t -> t.getIsDeleted() == YNType.N)
                .filter(t -> (holdingRowId != null && holdingRowId.equals(t.getHoldingRowId()))
                    || (t.getHoldingRowId() == null && key != null && key.equals(t.getHoldingKey())))
                .sorted(java.util.Comparator.comparing(AssetTrade::getTradeDate)
                    .thenComparing(AssetTrade::getRowId))
                .toList();
        });

        willAnswer(inv -> {
            Expense e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "rowId", 900L + expenses.size());
            expenses.add(e);
            return e;
        }).given(expenseRepository).save(any(Expense.class));
        given(expenseRepository.findById(any())).willAnswer(inv -> {
            Long id = inv.getArgument(0);
            return expenses.stream().filter(e -> id.equals(e.getRowId())).findFirst();
        });

        // 예수금 flow 를 실제로 누적해 잔액을 따라간다 — 자산의 캐시도 함께 맞춰야
        // 다음 매수의 예수금 검사가 현실처럼 동작한다.
        willAnswer(inv -> {
            Asset target = inv.getArgument(0);
            Long tradeId = inv.getArgument(1);
            long delta = inv.getArgument(2);
            if (BANK_ID == target.getRowId()) {
                bankFlow += delta;
            } else {
                cash += delta;
                recordedCash.put(tradeId, delta);
            }
            return null;
        }).given(balanceHistoryService).recordTrade(any(), any(), any(Long.class), any());
        // 취소는 그 거래가 <b>저장한</b> flow 를 되돌린다 — 실제 removeTrade 가 그 행을 soft-delete 하듯,
        // 다시 계산하지 않고 적어 둔 값을 뺀다.
        willAnswer(inv -> {
            Long id = inv.getArgument(0);
            // 매매 flow 는 결제 계좌와 무관하게 언제나 예수금이다 — 결제 계좌는 이체로
            // 예수금을 채울 뿐이고, 그 이체는 deleteTransfer 가 따로 되돌린다.
            Long stored = recordedCash.remove(id);
            if (stored != null) {
                cash -= stored;
            }
            return null;
        }).given(balanceHistoryService).removeTrade(any());

        // 평가금액 앵커 — 서비스가 보유 상태에 맞춰 통째로 다시 찍는다.
        willAnswer(inv -> {
            holdingValuation = inv.getArgument(1);
            return null;
        }).given(balanceHistoryService).recordValuation(any(), anyLong(), any());

        // 예수금 충당 이체 — 실제 서비스가 하는 것과 같은 방향으로 두 자산을 움직인다.
        willAnswer(inv -> {
            AssetServiceDto.CreateTransferCommand c = inv.getArgument(0);
            long id = 900L + transfers.size();
            long credited = c.toAmount() != null ? c.toAmount() : c.amount();
            transfers.add(new Funding(id, c.amount(), credited));
            bankFlow -= c.amount();
            cash += credited;
            AssetServiceDto.TransferInfo info = org.mockito.Mockito.mock(AssetServiceDto.TransferInfo.class);
            org.mockito.Mockito.lenient().when(info.rowId()).thenReturn(id);
            return info;
        }).given(assetService).createTransfer(any());

        willAnswer(inv -> {
            Long id = inv.getArgument(0);
            deletedTransferIds.add(id);
            transfers.stream().filter(t -> id.equals(t.rowId())).findFirst().ifPresent(t -> {
                bankFlow += t.amount();
                cash -= t.credited();
            });
            return null;
        }).given(assetService).deleteTransfer(any(), any());
        // 예수금 검증이 캐시 대신 이력 집계를 본다 — 흉내 낸 예수금을 그대로 돌려준다.
        // 예수금 검증이 이력 집계를 본다 — 흉내 낸 예수금을 그대로 돌려준다(평가금액은 안 쓴다).
        willAnswer(inv -> new AssetBalanceHistoryService.Split(cash, 0L))
            .given(balanceHistoryService).balanceAt(any(), any());
    }

    // === 헬퍼 ==================================================================

    private void deposit(long amount) {
        cash += amount; // 통장 → 증권계좌 이체로 들어온 예수금
    }

    private void syncAsset(long holdingValuation) {
    }

    private CreateTradeCommand trade(TradeType type, String qty, long amount, long fee, int day) {
        return new CreateTradeCommand(USER_ID, ASSET_ID, type, HoldingType.STOCK,
            null, SAMSUNG, true,
            new BigDecimal(qty), amount, fee, LocalDateTime.of(2026, 8, day, 10, 0), null, null);
    }

    private AssetHolding samsung() {
        return holdings.stream()
            .filter(h -> SAMSUNG.equals(h.holdingKey()) && h.getIsDeleted() == YNType.N)
            .findFirst().orElse(null);
    }

    /** 저장된 거래를 id 로 다시 꺼낸다 — 재계산이 값을 갈아끼웠는지 보려면 원본을 봐야 한다. */
    private AssetTrade tradeOf(Long rowId) {
        return trades.stream().filter(t -> rowId.equals(t.getRowId())).findFirst().orElseThrow();
    }

    private Expense savedRealizedExpense() {
        ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository).save(captor.capture());
        return captor.getValue();
    }

    // === 현실 흐름 =============================================================

    @Nested
    @DisplayName("입금 → 매수 → 시세 변동 → 부분 매도 → 전량 매도")
    class FullCycle {

        @Test
        @DisplayName("매수 — 예수금에서 대금과 수수료가 빠지고 수량·원가가 선다")
        void buy() {
            deposit(10_000_000L);

            // 삼성전자 100주를 7,000,000원에 매수, 수수료 5,000원
            sut.createTrade(trade(TradeType.BUY, "100", 7_000_000L, 5_000L, 3));

            assertThat(cash).isEqualTo(2_995_000L);           // 1,000만 - (700만 + 5천)
            assertThat(samsung().getQuantity()).isEqualByComparingTo("100");
            assertThat(samsung().getTotalCost()).isEqualTo(7_005_000L); // 수수료는 취득원가
            assertThat(samsung().avgPrice()).isEqualByComparingTo("70050"); // 7,005,000 / 100
        }

        @Test
        @DisplayName("매수로 새로 생긴 보유에도 시장코드가 붙는다 — 매매는 심볼만 들고 온다")
        void buyFillsMarketCode() {
            deposit(10_000_000L);
            // 매매 화면은 시장을 안 물으므로 서버가 심볼로 해석한다. 모호하면 null 로 남는다.
            given(stockMasterResolver.confirmMarketCode(null, SAMSUNG)).willReturn("KOSPI");

            sut.createTrade(trade(TradeType.BUY, "100", 7_000_000L, 5_000L, 3));

            assertThat(samsung().getMarketCode()).isEqualTo("KOSPI");
        }

        @Test
        @DisplayName("추가 매수 — 평단가가 이동평균으로 섞인다")
        void buyMore() {
            deposit(15_000_000L);
            sut.createTrade(trade(TradeType.BUY, "100", 7_000_000L, 5_000L, 3));
            // 주가가 내려 50주를 3,000,000원에 추가 매수 (수수료 2,000)
            sut.createTrade(trade(TradeType.BUY, "50", 3_000_000L, 2_000L, 5));

            assertThat(cash).isEqualTo(4_993_000L);           // 1,500만 - 7,005,000 - 3,002,000
            assertThat(samsung().getQuantity()).isEqualByComparingTo("150");
            assertThat(samsung().getTotalCost()).isEqualTo(10_007_000L);
            // 평단가 = 10,007,000 / 150 — 70,050 과 60,040 이 수량 비율로 섞인다
            assertThat(samsung().avgPrice()).isEqualByComparingTo("66713.33333333");
        }

        @Test
        @DisplayName("시세가 올라도 예수금과 원가는 그대로다")
        void valuationDoesNotTouchCashOrCost() {
            deposit(10_000_000L);
            sut.createTrade(trade(TradeType.BUY, "100", 7_000_000L, 5_000L, 3));
            long cashAfterBuy = cash;
            long costAfterBuy = samsung().getTotalCost();

            syncAsset(8_000_000L); // 평가액만 800만으로 갱신 (시세 상승)

            // 시세가 움직여도 예수금과 원가는 안 건드린다 — 평가금액만 갈아엎힌다.
            assertThat(cash).isEqualTo(cashAfterBuy);
            assertThat(samsung().getTotalCost()).isEqualTo(costAfterBuy);
        }

        @Test
        @DisplayName("부분 매도 — 판 만큼의 원가만 빠지고 그 차액이 실현손익이 된다")
        void sellPartial() {
            deposit(10_000_000L);
            sut.createTrade(trade(TradeType.BUY, "100", 7_000_000L, 5_000L, 3)); // 원가 7,005,000
            long cashBefore = cash;

            // 40주를 3,200,000원에 매도, 수수료 3,000
            sut.createTrade(trade(TradeType.SELL, "40", 3_200_000L, 3_000L, 10));

            // 판 만큼의 원가 = 7,005,000 * 40/100 = 2,802,000
            // 실현손익 = (3,200,000 - 3,000) - 2,802,000 = 395,000
            assertThat(cash).isEqualTo(cashBefore + 3_197_000L);
            assertThat(samsung().getQuantity()).isEqualByComparingTo("60");
            assertThat(samsung().getTotalCost()).isEqualTo(4_203_000L);
            assertThat(samsung().avgPrice()).isEqualByComparingTo("70050"); // 평단가는 안 변한다

            Expense pl = savedRealizedExpense();
            assertThat(pl.getExpenseType()).isEqualTo(ExpenseType.INCOME);
            assertThat(pl.getAmount()).isEqualTo(395_000L);
        }

        @Test
        @DisplayName("전량 매도 — 보유가 사라지고 대금이 예수금으로 남는다")
        void sellAll() {
            deposit(10_000_000L);
            sut.createTrade(trade(TradeType.BUY, "100", 7_000_000L, 5_000L, 3));
            long cashBefore = cash;

            sut.createTrade(trade(TradeType.SELL, "100", 8_000_000L, 6_000L, 20));

            assertThat(cash).isEqualTo(cashBefore + 7_994_000L);
            assertThat(samsung()).isNull(); // 수량 0 이면 보유가 사라진다
            // 실현손익 = (8,000,000 - 6,000) - 7,005,000 = 989,000
            assertThat(savedRealizedExpense().getAmount()).isEqualTo(989_000L);
        }

        @Test
        @DisplayName("손실 매도 — 지출로 기록된다")
        void sellAtLoss() {
            deposit(10_000_000L);
            sut.createTrade(trade(TradeType.BUY, "100", 7_000_000L, 5_000L, 3));

            sut.createTrade(trade(TradeType.SELL, "100", 6_000_000L, 4_000L, 20));

            // (6,000,000 - 4,000) - 7,005,000 = -1,009,000
            Expense pl = savedRealizedExpense();
            assertThat(pl.getExpenseType()).isEqualTo(ExpenseType.EXPENSE);
            assertThat(pl.getAmount()).isEqualTo(1_009_000L);
        }

        @Test
        @DisplayName("본전 매도 — 손익이 0 이면 거래를 만들지 않는다")
        void sellAtBreakEven() {
            deposit(10_000_000L);
            sut.createTrade(trade(TradeType.BUY, "100", 7_000_000L, 0L, 3)); // 원가 7,000,000

            sut.createTrade(trade(TradeType.SELL, "100", 7_000_000L, 0L, 20));

            verify(expenseRepository, never()).save(any(Expense.class));
        }
    }

    // === 기초 보유 =============================================================

    @Nested
    @DisplayName("앱을 쓰기 전부터 갖고 있던 보유")
    class Opening {

        @Test
        @DisplayName("기초 보유는 예수금을 건드리지 않는다 — 돈이 오간 적이 없다")
        void openingDoesNotMoveCash() {
            sut.createTrade(trade(TradeType.OPENING, "100", 7_000_000L, 0L, 1));

            assertThat(cash).isZero();
            assertThat(samsung().getQuantity()).isEqualByComparingTo("100");
            assertThat(samsung().getTotalCost()).isEqualTo(7_000_000L);
            verify(balanceHistoryService, never())
                .recordTrade(any(), any(), any(Long.class), any());
        }

        @Test
        @DisplayName("기초 보유 뒤 매도해도 원가 기준으로 손익이 잡힌다")
        void sellAfterOpening() {
            sut.createTrade(trade(TradeType.OPENING, "100", 7_000_000L, 0L, 1));

            sut.createTrade(trade(TradeType.SELL, "100", 7_500_000L, 0L, 10));

            assertThat(cash).isEqualTo(7_500_000L);
            assertThat(savedRealizedExpense().getAmount()).isEqualTo(500_000L);
        }
    }

    // === 취소 ==================================================================

    @Nested
    @DisplayName("거래 취소")
    class Cancel {

        @Test
        @DisplayName("매수 취소 — 예수금·수량·원가가 매수 직전으로 돌아온다")
        void cancelBuy() {
            deposit(10_000_000L);
            var bought = sut.createTrade(trade(TradeType.BUY, "100", 7_000_000L, 5_000L, 3));

            sut.deleteTrade(bought.rowId(), USER_ID);

            assertThat(cash).isEqualTo(10_000_000L);
            assertThat(samsung()).isNull();
        }

        @Test
        @DisplayName("매도 취소 — 판 수량과 원가가 복원되고 손익 거래도 지워진다")
        void cancelSell() {
            deposit(10_000_000L);
            sut.createTrade(trade(TradeType.BUY, "100", 7_000_000L, 5_000L, 3));
            var sold = sut.createTrade(trade(TradeType.SELL, "40", 3_200_000L, 3_000L, 10));
            long cashAfterSell = cash;

            sut.deleteTrade(sold.rowId(), USER_ID);

            assertThat(cash).isEqualTo(cashAfterSell - 3_197_000L);
            assertThat(samsung().getQuantity()).isEqualByComparingTo("100");
            assertThat(samsung().getTotalCost()).isEqualTo(7_005_000L); // 판 만큼의 원가가 되돌아온다
            // 손익 거래는 soft delete 로 무른다 — 물리 삭제하면 그 행에 달아 둔
            // 카테고리·분할까지 흔적 없이 사라진다(다른 모든 거래와 같은 규약).
            verify(expenseRepository, never()).delete(any(Expense.class));
            assertThat(expenses).allMatch(e -> e.getIsDeleted() == YNType.Y);
        }
    }

    // === 막아야 할 입력 =========================================================

    @Nested
    @DisplayName("현실에서 불가능한 입력은 막는다")
    class Validation {

        @Test
        @DisplayName("예수금보다 많이 사도 막지 않는다 — 예수금이 마이너스로 쌓인다")
        void buyingOverCashIsAllowed() {
            deposit(1_000_000L);

            // 이건 기록용 앱이다. 입금을 안 적고 매수만 적는 사용자가 있고, 마이너스 통장처럼
            // 음수로 쌓이는 게 정상이다. 막으면 "실제로는 샀는데 앱에는 못 적는" 상태가 된다.
            sut.createTrade(trade(TradeType.BUY, "100", 7_000_000L, 0L, 3));

            assertThat(cash).isEqualTo(-6_000_000L);
        }

        @Test
        @DisplayName("보유 수량보다 많이 팔 수 없다")
        void cannotSellOverQuantity() {
            deposit(10_000_000L);
            sut.createTrade(trade(TradeType.BUY, "100", 7_000_000L, 0L, 3));

            assertThatThrownBy(() -> sut.createTrade(trade(TradeType.SELL, "200", 1_000_000L, 0L, 5)))
                .isInstanceOf(InvalidValueException.class);
        }

        @Test
        @DisplayName("보유하지 않은 종목은 팔 수 없다")
        void cannotSellUnheld() {
            deposit(10_000_000L);

            assertThatThrownBy(() -> sut.createTrade(trade(TradeType.SELL, "10", 100_000L, 0L, 5)))
                .isInstanceOf(InvalidValueException.class);
        }

        @Test
        @DisplayName("수량은 0보다 커야 한다")
        void quantityMustBePositive() {
            deposit(10_000_000L);

            assertThatThrownBy(() -> sut.createTrade(trade(TradeType.BUY, "0", 100_000L, 0L, 3)))
                .isInstanceOf(InvalidValueException.class);
        }

        @Test
        @DisplayName("투자 자산이 아니면 거래를 기록할 수 없다")
        void onlyInvestmentAssets() {
            Asset bank = Asset.createAsset(user, "급여통장", AssetType.BANK_ACCOUNT, 0L, "KRW",
                null, null, null, null, 0, YNType.Y, null, null, null, null);
            ReflectionTestUtils.setField(bank, "rowId", 99L);
            given(assetRepository.findById(99L)).willReturn(Optional.of(bank));

            var cmd = new CreateTradeCommand(USER_ID, 99L, TradeType.BUY, HoldingType.STOCK,
            null,
                SAMSUNG, true, BigDecimal.TEN, 100_000L, 0L,
                LocalDateTime.of(2026, 8, 3, 10, 0), null, null);

            assertThatThrownBy(() -> sut.createTrade(cmd))
                .isInstanceOf(InvalidValueException.class);
        }

        @Test
        @DisplayName("남의 자산에는 거래를 기록할 수 없다")
        void othersAssetRejected() {
            assertThatThrownBy(() -> sut.createTrade(
                new CreateTradeCommand(999L, ASSET_ID, TradeType.BUY, HoldingType.STOCK,
            null,
                    SAMSUNG, true, BigDecimal.TEN, 100_000L, 0L,
                    LocalDateTime.of(2026, 8, 3, 10, 0), null, null)))
                .isInstanceOf(RuntimeException.class);
        }
    }

    // === 결제 계좌 =============================================================

    @Nested
    @DisplayName("결제 계좌 매수 — 통장에서 예수금으로 채우고 예수금에서 산다")
    class SettlementAccount {

        private CreateTradeCommand viaBank(TradeType type, String qty, long amount, int day) {
            return new CreateTradeCommand(USER_ID, ASSET_ID, type, HoldingType.STOCK,
            null, SAMSUNG, true,
                new BigDecimal(qty), amount, 0L, LocalDateTime.of(2026, 8, day, 10, 0), null,
                BANK_ID);
        }

        @Test
        @DisplayName("예수금 0 에서 700만 매수 — 통장에서 700만 이체 후 예수금에서 결제")
        void fundsShortfallThenBuys() {
            sut.createTrade(viaBank(TradeType.BUY, "100", 7_000_000L, 3));

            // ① 이체: 통장 -700만 → 예수금 +700만  ② 매수: 예수금 -700만
            assertThat(transfers).hasSize(1);
            assertThat(transfers.get(0).amount()).isEqualTo(7_000_000L);
            assertThat(bankFlow).isEqualTo(-7_000_000L);
            assertThat(cash).isZero();
            assertThat(samsung().getTotalCost()).isEqualTo(7_000_000L);
        }

        @Test
        @DisplayName("예수금이 일부 있으면 모자란 만큼만 이체한다 — 400만 있고 1,000만 매수 → 600만")
        void fundsOnlyTheShortfall() {
            deposit(4_000_000L);

            sut.createTrade(viaBank(TradeType.BUY, "100", 10_000_000L, 3));

            assertThat(transfers).hasSize(1);
            assertThat(transfers.get(0).amount()).isEqualTo(6_000_000L);
            assertThat(bankFlow).isEqualTo(-6_000_000L);
            assertThat(cash).isZero();  // 400만 + 600만 - 1,000만
        }

        @Test
        @DisplayName("예수금이 충분하면 이체가 아예 생기지 않는다")
        void noTransferWhenCashIsEnough() {
            deposit(10_000_000L);

            sut.createTrade(viaBank(TradeType.BUY, "100", 7_000_000L, 3));

            assertThat(transfers).isEmpty();
            assertThat(bankFlow).isZero();
            assertThat(cash).isEqualTo(3_000_000L);
        }

        @Test
        @DisplayName("통장이 마이너스가 돼도 막지 않는다 — 기록용 앱이다")
        void bankMayGoNegative() {
            sut.createTrade(viaBank(TradeType.BUY, "100", 99_000_000L, 3));

            assertThat(bankFlow).isEqualTo(-99_000_000L);
        }

        @Test
        @DisplayName("매도 대금은 예수금에 남는다 — 팔았다고 통장으로 자동 이체되지 않는다")
        void sellKeepsProceedsInCash() {
            sut.createTrade(viaBank(TradeType.BUY, "100", 7_000_000L, 3));
            int transfersAfterBuy = transfers.size();

            sut.createTrade(viaBank(TradeType.SELL, "100", 8_000_000L, 20));

            assertThat(transfers).hasSize(transfersAfterBuy); // 매도는 이체를 만들지 않는다
            assertThat(bankFlow).isEqualTo(-7_000_000L);      // 매수 때 나간 그대로
            assertThat(cash).isEqualTo(8_000_000L);           // 대금은 예수금에
            assertThat(savedRealizedExpense().getAmount()).isEqualTo(1_000_000L);
        }

        @Test
        @DisplayName("취소 — 이체와 매수가 함께 되돌아 매수 직전으로 복귀한다")
        void cancelRestoresBothLegs() {
            deposit(4_000_000L);
            var bought = sut.createTrade(viaBank(TradeType.BUY, "100", 10_000_000L, 3));

            sut.deleteTrade(bought.rowId(), USER_ID);

            // 이체를 안 지우면 "통장에서 600만 빼서 예수금에 넣어 둔" 상태로 남는다.
            assertThat(deletedTransferIds).containsExactly(transfers.get(0).rowId());
            assertThat(bankFlow).isZero();
            assertThat(cash).isEqualTo(4_000_000L);
            assertThat(samsung()).isNull();
        }

        @Test
        @DisplayName("이체가 안 생긴 매수를 취소해도 지울 이체가 없다")
        void cancelWithoutTransfer() {
            deposit(10_000_000L);
            var bought = sut.createTrade(viaBank(TradeType.BUY, "100", 7_000_000L, 3));

            sut.deleteTrade(bought.rowId(), USER_ID);

            assertThat(deletedTransferIds).isEmpty();
            assertThat(cash).isEqualTo(10_000_000L);
        }
    }

    // === 금·코인 ===============================================================

    @Nested
    @DisplayName("금·코인도 같은 규칙으로 굴러간다")
    class NonStock {

        private CreateTradeCommand goldTrade(TradeType type, String qty, long amount, int day) {
            return new CreateTradeCommand(USER_ID, ASSET_ID, type, HoldingType.GOLD,
            null, "금 현물", false,
                new BigDecimal(qty), amount, 0L, LocalDateTime.of(2026, 8, day, 10, 0), null, null);
        }

        @Test
        @DisplayName("소수 수량 — 금 3.75g 을 사고 1.25g 을 판다")
        void fractionalQuantity() {
            deposit(10_000_000L);
            sut.createTrade(goldTrade(TradeType.BUY, "3.75", 3_000_000L, 3));

            sut.createTrade(goldTrade(TradeType.SELL, "1.25", 1_100_000L, 10));

            AssetHolding gold = holdings.stream()
                .filter(h -> "금 현물".equals(h.holdingKey()) && h.getIsDeleted() == YNType.N)
                .findFirst().orElseThrow();
            assertThat(gold.getQuantity()).isEqualByComparingTo("2.50");
            // 판 만큼의 원가 = 3,000,000 * 1.25/3.75 = 1,000,000 → 실현손익 100,000
            assertThat(gold.getTotalCost()).isEqualTo(2_000_000L);
            assertThat(savedRealizedExpense().getAmount()).isEqualTo(100_000L);
        }
    }

    @Nested
    @DisplayName("재계산 — 순서가 바뀌면 그 뒤 손익을 다시 쌓는다")
    class Replay {

        /**
         * 이동평균은 순서에 의존한다. 각 거래에 박아 둔 변동분은 "그때의" 값이라,
         * 앞선 거래가 사라지거나 과거 날짜 거래가 끼어들면 그대로는 어긋난다.
         */
        @Test
        @DisplayName("중간 매수를 지우면 그 뒤 매도의 실현손익이 다시 계산된다")
        void deletingMiddleBuyRecalculatesLaterSell() {
            deposit(50_000_000L);
            var first = sut.createTrade(trade(TradeType.BUY, "10", 700_000L, 0L, 2));   // 평단 70,000
            sut.createTrade(trade(TradeType.BUY, "10", 900_000L, 0L, 10));              // 평단 80,000
            var sold = sut.createTrade(trade(TradeType.SELL, "10", 1_000_000L, 0L, 20));

            // 20주 원가 1,600,000 중 절반을 팔았으니 800,000 → 손익 +200,000
            assertThat(tradeOf(sold.rowId()).getRealizedPl()).isEqualTo(200_000L);

            // "3월 매수는 사실 다른 증권사 거래였다" — 지운다.
            sut.deleteTrade(first.rowId(), USER_ID);

            // 남은 건 10주 900,000 뿐이고 그걸 1,000,000 에 팔았으니 +100,000 이어야 한다.
            assertThat(tradeOf(sold.rowId()).getRealizedPl()).isEqualTo(100_000L);
        }

        @Test
        @DisplayName("과거 날짜 매수를 뒤늦게 넣어도 그 뒤 매도가 다시 계산된다")
        void backdatedBuyRecalculatesLaterSell() {
            deposit(50_000_000L);
            sut.createTrade(trade(TradeType.BUY, "10", 900_000L, 0L, 10));
            var sold = sut.createTrade(trade(TradeType.SELL, "10", 1_000_000L, 0L, 20));

            assertThat(tradeOf(sold.rowId()).getRealizedPl()).isEqualTo(100_000L);

            // 2일자 매수를 8월 말에 뒤늦게 입력 — 평단이 섞여 손익이 달라져야 한다.
            sut.createTrade(trade(TradeType.BUY, "10", 700_000L, 0L, 2));

            assertThat(tradeOf(sold.rowId()).getRealizedPl()).isEqualTo(200_000L);
        }

        /**
         * 자산 편집 폼으로 담은 보유 — 매매 기록 없이 수량·원가만 있다. 다시 쌓기는 매매 기록만
         * 굴리므로 0 부터 쌓으면 이 몫이 사라졌다(QA 30 5). 이 몫은 맨 앞에 두고 쌓는다.
         */
        private AssetHolding formHolding(String qty, long cost) {
            AssetHolding h = AssetHolding.create(account, HoldingType.STOCK, YNType.Y, null, SAMSUNG,
                new BigDecimal(qty), null, null, cost, 0);
            org.springframework.test.util.ReflectionTestUtils.setField(h, "rowId", 500L);
            holdings.add(h);
            return h;
        }

        @Test
        @DisplayName("폼 보유 10주 + 매수 5주 뒤 과거 날짜 매수 2주 → 17주(폼 10주가 남는다)")
        void backdatedTradeKeepsFormHolding() {
            deposit(50_000_000L);
            formHolding("10", 700_000L);
            sut.createTrade(trade(TradeType.BUY, "5", 400_000L, 0L, 10));
            assertThat(samsung().getQuantity()).isEqualByComparingTo("15");

            sut.createTrade(trade(TradeType.BUY, "2", 150_000L, 0L, 2));

            assertThat(samsung().getQuantity()).isEqualByComparingTo("17");
            assertThat(samsung().getTotalCost()).isEqualTo(1_250_000L);
        }

        @Test
        @DisplayName("폼 보유 10주 + 매수 두 번 중 하나를 취소 → 폼 10주 + 남은 매수만")
        void cancelKeepsFormHolding() {
            deposit(50_000_000L);
            formHolding("10", 700_000L);
            var first = sut.createTrade(trade(TradeType.BUY, "5", 400_000L, 0L, 5));
            sut.createTrade(trade(TradeType.BUY, "3", 270_000L, 0L, 10));

            sut.deleteTrade(first.rowId(), USER_ID);

            assertThat(samsung().getQuantity()).isEqualByComparingTo("13");
            assertThat(samsung().getTotalCost()).isEqualTo(970_000L);
        }

        @Test
        @DisplayName("폼 보유 뒤 매도는 폼 원가까지 섞어 이동평균으로 판다")
        void sellAfterFormHoldingUsesFormCost() {
            deposit(50_000_000L);
            formHolding("10", 700_000L);                                         // 평단 70,000
            sut.createTrade(trade(TradeType.BUY, "10", 900_000L, 0L, 10));       // 20주 1,600,000
            var sold = sut.createTrade(trade(TradeType.SELL, "10", 1_000_000L, 0L, 20));
            assertThat(tradeOf(sold.rowId()).getRealizedPl()).isEqualTo(200_000L);

            // 과거 날짜 매수를 끼워 넣어도 폼 원가가 빠지지 않는다 — 다시 쌓아 30주 2,300,000 중 절반.
            sut.createTrade(trade(TradeType.BUY, "10", 700_000L, 0L, 2));

            assertThat(tradeOf(sold.rowId()).getRealizedPl()).isEqualTo(1_000_000L - 766_667L);
            assertThat(samsung().getQuantity()).isEqualByComparingTo("20");
        }

        @Test
        @DisplayName("맨 뒤에 붙는 거래는 다시 쌓지 않는다 — 이미 맞는 값이다")
        void appendingDoesNotChangeEarlier() {
            deposit(50_000_000L);
            sut.createTrade(trade(TradeType.BUY, "10", 700_000L, 0L, 2));
            var sold = sut.createTrade(trade(TradeType.SELL, "5", 500_000L, 0L, 10));
            long before = tradeOf(sold.rowId()).getRealizedPl();

            sut.createTrade(trade(TradeType.BUY, "10", 900_000L, 0L, 20));

            assertThat(tradeOf(sold.rowId()).getRealizedPl()).isEqualTo(before);
        }

        @Test
        @DisplayName("재계산 후 보유 수량·원가도 다시 쌓은 값으로 맞는다")
        void holdingFollowsReplay() {
            deposit(50_000_000L);
            var first = sut.createTrade(trade(TradeType.BUY, "10", 700_000L, 0L, 2));
            sut.createTrade(trade(TradeType.BUY, "10", 900_000L, 0L, 10));
            sut.createTrade(trade(TradeType.SELL, "10", 1_000_000L, 0L, 20));

            sut.deleteTrade(first.rowId(), USER_ID);

            // 10주 사서 10주 팔았으니 보유가 없어야 한다.
            assertThat(samsung()).isNull();
        }

        @Test
        @DisplayName("다른 종목은 건드리지 않는다 — 재계산 단위는 (자산, 종목) 하나다")
        void doesNotTouchOtherHoldings() {
            deposit(50_000_000L);
            var kakaoBuy = new CreateTradeCommand(USER_ID, ASSET_ID, TradeType.BUY,
                HoldingType.STOCK, null, "035720", true, new BigDecimal("5"), 500_000L, 0L,
                LocalDateTime.of(2026, 8, 5, 10, 0), null, null);
            sut.createTrade(kakaoBuy);
            var first = sut.createTrade(trade(TradeType.BUY, "10", 700_000L, 0L, 2));

            sut.deleteTrade(first.rowId(), USER_ID);

            AssetHolding kakao = holdings.stream()
                .filter(h -> "035720".equals(h.holdingKey()) && h.getIsDeleted() == YNType.N)
                .findFirst().orElse(null);
            assertThat(kakao).isNotNull();
            assertThat(kakao.getTotalCost()).isEqualTo(500_000L);
        }
    }

    @Nested
    @DisplayName("순자산 보존 — 매매는 돈의 자리만 바꾼다")
    class NetWorthPreserved {

        /** 예수금 + 평가금액. 매매 전후로 이 값이 변하면 순자산이 증발하거나 부풀었다는 뜻이다. */
        private long netWorth() {
            return cash + holdingValuation;
        }

        @Test
        @DisplayName("미연동 종목 매수 — 예수금이 준 만큼 평가금액이 는다")
        void buyKeepsNetWorth() {
            deposit(10_000L);
            long before = netWorth();

            sut.createTrade(trade(TradeType.BUY, "1", 10_000L, 0L, 3));

            assertThat(cash).isZero();
            assertThat(holdingValuation).isEqualTo(10_000L); // 시세를 모르니 산 값이 곧 현재 값
            assertThat(netWorth()).isEqualTo(before);        // 증발하지 않는다
        }

        @Test
        @DisplayName("매도 — 평가금액이 준 만큼 예수금이 는다(손익 제외)")
        void sellKeepsNetWorth() {
            deposit(10_000L);
            sut.createTrade(trade(TradeType.BUY, "10", 10_000L, 0L, 3));
            long before = netWorth();

            // 산 값 그대로 되판다 — 손익 0 이라 순자산이 그대로여야 한다.
            sut.createTrade(trade(TradeType.SELL, "10", 10_000L, 0L, 10));

            assertThat(cash).isEqualTo(10_000L);
            assertThat(holdingValuation).isZero();
            assertThat(netWorth()).isEqualTo(before);
        }

        @Test
        @DisplayName("평가액을 넣어 둔 보유에 더 사도 순자산이 그대로다")
        void buyOnValuedHoldingKeepsNetWorth() {
            deposit(24_000L);
            // 골드바를 이미 갖고 있고, 시세를 몰라 평가액을 손으로 18,000 넣어 둔 상태.
            sut.createTrade(trade(TradeType.BUY, "3", 18_000L, 0L, 3));
            // 시세를 몰라 사용자가 평가액만 손으로 넣은 상태를 만든다.
            ReflectionTestUtils.setField(samsung(), "holdingValue", 18_000L);
            long before = netWorth();

            sut.createTrade(trade(TradeType.BUY, "1", 6_000L, 0L, 5));

            // 평가액을 그대로 두면 예수금만 빠져 6,000 이 증발한다.
            assertThat(samsung().getHoldingValue()).isEqualTo(24_000L);
            assertThat(netWorth()).isEqualTo(before);
        }

        @Test
        @DisplayName("평가액을 넣어 둔 보유를 일부 팔면 판 비율만큼 준다")
        void sellOnValuedHoldingScalesValuation() {
            deposit(24_000L);
            sut.createTrade(trade(TradeType.BUY, "4", 24_000L, 0L, 3));
            ReflectionTestUtils.setField(samsung(), "holdingValue", 24_000L);

            sut.createTrade(trade(TradeType.SELL, "1", 6_000L, 0L, 10));

            // 4개 중 1개를 팔았으니 평가액도 3/4 만 남는다.
            assertThat(samsung().getHoldingValue()).isEqualTo(18_000L);
        }

        @Test
        @DisplayName("매수 취소 — 평가금액도 함께 되돌아온다")
        void cancelBuyRestores() {
            deposit(10_000L);
            long before = netWorth();
            var bought = sut.createTrade(trade(TradeType.BUY, "1", 10_000L, 0L, 3));

            sut.deleteTrade(bought.rowId(), USER_ID);

            assertThat(cash).isEqualTo(10_000L);
            assertThat(holdingValuation).isZero();
            assertThat(netWorth()).isEqualTo(before);
        }

        @Test
        @DisplayName("평가액이 원가와 다른 보유의 추가 매수 취소 — 산 값만큼 되돌아온다(비율이 아니다)")
        void cancelBuyOnValuedHoldingSubtractsCost() {
            deposit(24_000L);
            // 2개를 12,000 에 사 두고, 시세가 올라 평가액을 18,000 으로 손보정한 상태.
            // 평가액 ≠ 원가일 때만 비율 차감과 빼기가 갈라진다 — 같으면 우연히 답이 같아
            // 버그가 숨는다(24,000×¾ = 24,000−6,000).
            sut.createTrade(trade(TradeType.BUY, "2", 12_000L, 0L, 3));
            ReflectionTestUtils.setField(samsung(), "holdingValue", 18_000L);
            sut.createTrade(trade(TradeType.BUY, "1", 6_000L, 0L, 5)); // 18,000+6,000=24,000
            var bought = tradeOf(2L);
            long before = netWorth();

            sut.deleteTrade(bought.getRowId(), USER_ID);

            // 매수의 역은 빼기다 — 비율로 덜면 24,000×⅔=16,000 이 되어 2,000 이 증발한다.
            assertThat(samsung().getHoldingValue()).isEqualTo(18_000L);
            assertThat(netWorth()).isEqualTo(before);
        }

        @Test
        @DisplayName("평가액이 원가와 다른 보유의 매도 취소 — 판 비율만큼 되돌아온다(+원가가 아니다)")
        void cancelSellOnValuedHoldingRestoresRatio() {
            deposit(24_000L);
            // 4개를 24,000 에 사 두고, 시세가 올라 평가액 32,000 으로 손보정한 상태.
            sut.createTrade(trade(TradeType.BUY, "4", 24_000L, 0L, 3));
            ReflectionTestUtils.setField(samsung(), "holdingValue", 32_000L);
            var sold = sut.createTrade(trade(TradeType.SELL, "1", 8_000L, 0L, 10)); // 32,000×¾=24,000
            long before = netWorth();

            sut.deleteTrade(sold.rowId(), USER_ID);

            // 매도의 역은 역비율이다 — 원가를 도로 더하면 24,000+6,000=30,000 이 되어 2,000 이 샌다.
            assertThat(samsung().getHoldingValue()).isEqualTo(32_000L);
            assertThat(netWorth()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("종목 식별자 정규화 — 한 종목이 두 행으로 갈라지지 않게")
    class HoldingKeyNormalization {

        private CreateTradeCommand withKey(TradeType type, String key, Boolean linked,
                                           String qty, long amount, int day) {
            return new CreateTradeCommand(USER_ID, ASSET_ID, type, HoldingType.STOCK,
                null, key, linked,
                new BigDecimal(qty), amount, 0L, LocalDateTime.of(2026, 8, day, 10, 0), null, null);
        }

        @Test
        @DisplayName("대소문자만 다른 매수는 기존 보유에 붙는다 — 새 행이 생기지 않는다")
        void lowercaseSymbolAttachesToExistingHolding() {
            deposit(10_000_000L);
            sut.createTrade(withKey(TradeType.BUY, "AAPL", true, "10", 1_000_000L, 3));
            assertThat(holdings).hasSize(1);

            // DB 콜레이션(utf8mb4_unicode_ci)은 "aapl" 과 "AAPL" 을 같은 값으로 본다. 자바만
            // 대소문자를 가리면 여기서 새 행이 생기고, 유일성이 붙는 순간 이 매수가 통째로 실패한다.
            sut.createTrade(withKey(TradeType.BUY, " aapl ", true, "5", 500_000L, 4));

            assertThat(holdings).hasSize(1);
            AssetHolding held = holdings.get(0);
            assertThat(held.getSymbol()).isEqualTo("AAPL");
            assertThat(held.getQuantity()).isEqualByComparingTo("15");
            assertThat(held.getTotalCost()).isEqualTo(1_500_000L);
        }

        @Test
        @DisplayName("종목코드는 대문자·앞뒤공백을 뗀 채로 저장된다")
        void symbolStoredNormalized() {
            deposit(10_000_000L);

            sut.createTrade(withKey(TradeType.BUY, "  tsla ", true, "1", 300_000L, 3));

            assertThat(holdings.get(0).getSymbol()).isEqualTo("TSLA");
            assertThat(trades.get(0).getHoldingKey()).isEqualTo("TSLA");
        }

        @Test
        @DisplayName("미연동 항목명은 앞뒤공백만 떼고 대소문자는 사용자가 친 그대로 남는다")
        void manualNameKeepsCase() {
            deposit(10_000_000L);

            sut.createTrade(withKey(TradeType.BUY, "  Gold Bar ", false, "1", 300_000L, 3));

            assertThat(holdings.get(0).getHoldingName()).isEqualTo("Gold Bar");
            // 저장은 그대로여도 비교는 콜레이션과 같이 대소문자를 안 가려야 한다.
            sut.createTrade(withKey(TradeType.BUY, "gold bar", false, "1", 300_000L, 4));
            assertThat(holdings).hasSize(1);
            assertThat(holdings.get(0).getQuantity()).isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("연동 매매인데 종목코드가 비면 400 — 식별자 없는 보유는 유일성이 안 걸린다")
        void rejectsLinkedTradeWithoutSymbol() {
            deposit(10_000_000L);

            assertThatThrownBy(() -> sut.createTrade(withKey(TradeType.BUY, "   ", true, "1", 100_000L, 3)))
                .isInstanceOf(InvalidValueException.class);
            assertThatThrownBy(() -> sut.createTrade(withKey(TradeType.BUY, null, true, "1", 100_000L, 3)))
                .isInstanceOf(InvalidValueException.class);

            assertThat(holdings).isEmpty();
            verify(holdingRepository, never()).save(any());
        }

        @Test
        @DisplayName("미연동 매매도 항목명이 비면 보유를 만들지 않는다")
        void rejectsManualTradeWithoutName() {
            deposit(10_000_000L);

            assertThatThrownBy(() -> sut.createTrade(withKey(TradeType.BUY, "  ", false, "1", 100_000L, 3)))
                .isInstanceOf(InvalidValueException.class);

            assertThat(holdings).isEmpty();
        }
    }

    // === 외화 계좌 =============================================================

    /**
     * USD 증권계좌(환율 1,300) — 대금 · 수수료는 원화로 들어오고, 예수금은 달러로 움직인다.
     * 원가(totalCost) · 보유 평가(HOLDING) · 실현손익은 원화 그대로다.
     */
    @Nested
    @DisplayName("외화(USD · 1,300) 증권계좌 — 예수금 증감 = round(원화 ÷ 환율)")
    class ForeignAccount {

        private Asset usd;

        @BeforeEach
        void usdAccount() {
            usd = Asset.createAsset(user, "달러 계좌", AssetType.INVESTMENT, 0L, "USD",
                new BigDecimal("1300"), null, null, null, 0, YNType.Y, null, null, null, null);
            ReflectionTestUtils.setField(usd, "rowId", ASSET_ID);
            given(assetRepository.findById(ASSET_ID)).willReturn(Optional.of(usd));
        }

        private CreateTradeCommand usdTrade(TradeType type, String qty, long amount, long fee, Long settlement) {
            return new CreateTradeCommand(USER_ID, ASSET_ID, type, HoldingType.STOCK,
                null, "AAPL", true, new BigDecimal(qty), amount, fee,
                LocalDateTime.of(2026, 8, 3, 10, 0), null, settlement);
        }

        @Test
        @DisplayName("회귀 — 예수금 $2,000 에서 1,300,000원 매수 → 예수금 $1,000 · 보유 1,300,000원 · 원화 합계 2,600,000")
        void buyKeepsNetWorth() {
            deposit(2_000L);

            sut.createTrade(usdTrade(TradeType.BUY, "10", 1_300_000L, 0L, null));

            assertThat(cash).isEqualTo(1_000L);
            assertThat(holdingValuation).isEqualTo(1_300_000L);   // 원가 = 원화
            assertThat(usd.balanceInKrw(cash, holdingValuation)).isEqualTo(2_600_000L);
            // 매수 전과 같다 — 돈이 자산 안에서 자리만 옮겼다(2,000 × 1,300).
            assertThat(usd.balanceInKrw(2_000L, 0L)).isEqualTo(2_600_000L);
        }

        @Test
        @DisplayName("매수 수수료는 합친 뒤 한 번만 나눈다 — 1,000,000 + 5,000 = 1,005,000 ÷ 1,300 = 773.08 → $773")
        void buyWithFeeRounds() {
            deposit(2_000L);

            sut.createTrade(usdTrade(TradeType.BUY, "10", 1_000_000L, 5_000L, null));

            assertThat(cash).isEqualTo(2_000L - 773L);
            AssetHolding h = holdings.get(0);
            assertThat(h.getTotalCost()).isEqualTo(1_005_000L);   // 원가는 원화 그대로
        }

        @Test
        @DisplayName("매도 — (1,300,000 − 1,000) ÷ 1,300 = 999.23 → +$999, 실현손익은 원화(−1,000원)")
        void sellAddsDollars() {
            deposit(2_000L);
            sut.createTrade(usdTrade(TradeType.BUY, "10", 1_300_000L, 0L, null));
            assertThat(cash).isEqualTo(1_000L);

            sut.createTrade(usdTrade(TradeType.SELL, "10", 1_300_000L, 1_000L, null));

            assertThat(cash).isEqualTo(1_999L);
            AssetTrade sell = trades.get(1);
            assertThat(sell.getRealizedPl()).isEqualTo(-1_000L);
        }

        @Test
        @DisplayName("HALF_UP — 650원 매도 → +$1(0.5 올림), 1,950원 매수 → −$2(1.5 올림)")
        void halfUp() {
            assertThat(AssetTrade.cashDeltaOf(usd, TradeType.SELL, 650L, 0L)).isEqualTo(1L);
            assertThat(AssetTrade.cashDeltaOf(usd, TradeType.BUY, 1_950L, 0L)).isEqualTo(-2L);
            assertThat(AssetTrade.cashDeltaOf(usd, TradeType.OPENING, 1_950L, 0L)).isZero();
            // 원화 계좌는 지금 식 그대로
            assertThat(AssetTrade.cashDeltaOf(account, TradeType.BUY, 1_950L, 50L)).isEqualTo(-2_000L);
            assertThat(AssetTrade.cashDeltaOf(account, TradeType.SELL, 1_950L, 50L)).isEqualTo(1_900L);
        }

        @Test
        @DisplayName("취소는 저장한 달러 증감을 되돌린다 — 그 사이 환율을 1,400 으로 고쳐도 예수금이 매수 전 $2,000 으로")
        void deleteRestoresStoredCash() {
            deposit(2_000L);
            var info = sut.createTrade(usdTrade(TradeType.BUY, "10", 1_300_000L, 0L, null));
            assertThat(cash).isEqualTo(1_000L);
            // 계좌 환율을 고친다 — 다시 계산하면 1,300,000 ÷ 1,400 = 929 를 되돌려 $1,929 가 된다.
            ReflectionTestUtils.setField(usd, "exchangeRate", new BigDecimal("1400"));

            sut.deleteTrade(info.rowId(), USER_ID);

            assertThat(cash).isEqualTo(2_000L);
            assertThat(recordedCash).isEmpty();
        }

        @Test
        @DisplayName("원화 통장 결제 — 예수금 $100 에서 1,300,000원 매수: 통장 −1,170,000원 · 예수금 +$900 → $0")
        void settlementFromKrwBank() {
            deposit(100L);

            sut.createTrade(usdTrade(TradeType.BUY, "10", 1_300_000L, 0L, BANK_ID));

            assertThat(transfers).hasSize(1);
            assertThat(transfers.get(0).amount()).isEqualTo(1_170_000L);   // 900 × 1,300 원화
            assertThat(transfers.get(0).credited()).isEqualTo(900L);       // 받는 금액을 못 박는다
            assertThat(bankFlow).isEqualTo(-1_170_000L);
            assertThat(cash).isZero();
        }

        /** 결제 계좌를 다른 통화 · 환율의 통장으로 바꿔 끼운다(같은 BANK_ID). */
        private Asset bankIn(String currency, String rate) {
            Asset b = Asset.createAsset(user, currency + " 통장", AssetType.BANK_ACCOUNT, 0L, currency,
                new BigDecimal(rate), null, null, null, 0, YNType.Y, null, null, null, null);
            ReflectionTestUtils.setField(b, "rowId", BANK_ID);
            given(assetRepository.findById(BANK_ID)).willReturn(Optional.of(b));
            return b;
        }

        @Test
        @DisplayName("같은 통화 충당 — USD 통장(1,310) → USD 계좌(1,300): 부족분 $900 그대로 보내고 받는다(to_amount 없음)")
        void settlementSameCurrency() {
            bankIn("USD", "1310");
            deposit(100L);

            sut.createTrade(usdTrade(TradeType.BUY, "10", 1_300_000L, 0L, BANK_ID));

            assertThat(transfers).hasSize(1);
            assertThat(transfers.get(0).amount()).isEqualTo(900L);    // 보내는 계좌 통화(USD)
            assertThat(transfers.get(0).credited()).isEqualTo(900L);  // NULL → 보낸 만큼
            assertThat(bankFlow).isEqualTo(-900L);
            assertThat(cash).isZero();
        }

        @Test
        @DisplayName("다른 외화 충당 — EUR 통장(1,450) → USD 계좌: 900 × 1,300 ÷ 1,450 = 806.9 → €807 보내고 $900 받는다")
        void settlementFromEur() {
            bankIn("EUR", "1450");
            deposit(100L);

            sut.createTrade(usdTrade(TradeType.BUY, "10", 1_300_000L, 0L, BANK_ID));

            assertThat(transfers.get(0).amount()).isEqualTo(807L);
            assertThat(transfers.get(0).credited()).isEqualTo(900L);
            assertThat(bankFlow).isEqualTo(-807L);
            assertThat(cash).isZero();
        }

        @Test
        @DisplayName("환율 1 미만 같은 통화 충당(VND 0.055) — 부족분을 환산하지 않아 1단위도 새지 않는다")
        void settlementLowRateSameCurrency() {
            Asset vnd = Asset.createAsset(user, "VND 계좌", AssetType.INVESTMENT, 0L, "VND",
                new BigDecimal("0.055"), null, null, null, 0, YNType.Y, null, null, null, null);
            ReflectionTestUtils.setField(vnd, "rowId", ASSET_ID);
            given(assetRepository.findById(ASSET_ID)).willReturn(Optional.of(vnd));
            bankIn("VND", "0.055");

            // 55원 → round(55 ÷ 0.055) = 1,000 VND 매수, 예수금 0 → 부족분 1,000 VND
            sut.createTrade(usdTrade(TradeType.BUY, "1", 55L, 0L, BANK_ID));

            assertThat(transfers.get(0).amount()).isEqualTo(1_000L);
            assertThat(cash).isZero();
        }

        @Test
        @DisplayName("미리보기 — 저장과 같은 식: 예수금 변동 −$1,000 · 거래 후 $0 · 통장에서 1,170,000원 · 통화 USD / KRW")
        void previewMatches() {
            deposit(100L);

            var preview = sut.previewTrade(usdTrade(TradeType.BUY, "10", 1_300_000L, 0L, BANK_ID));

            assertThat(preview.cashDelta()).isEqualTo(-1_000L);
            assertThat(preview.cashAfter()).isZero();
            assertThat(preview.fundingAmount()).isEqualTo(1_170_000L);
            assertThat(preview.cashCurrency()).isEqualTo("USD");
            assertThat(preview.fundingCurrency()).isEqualTo("KRW");
        }

        @Test
        @DisplayName("미리보기 EUR 통장 — 결제 계좌를 실제로 읽어 €807 · EUR (원화로 가정하면 1,170,000 이 나온다)")
        void previewReadsSettlementAccount() {
            bankIn("EUR", "1450");
            deposit(100L);

            var preview = sut.previewTrade(usdTrade(TradeType.BUY, "10", 1_300_000L, 0L, BANK_ID));

            assertThat(preview.fundingAmount()).isEqualTo(807L);
            assertThat(preview.fundingCurrency()).isEqualTo("EUR");
            assertThat(preview.cashAfter()).isZero();
        }

        @Test
        @DisplayName("미리보기 결제 계좌 없음 — 예수금 $100 에서 1,300,000원 매수 → 거래 후 −$900")
        void previewWithoutSettlement() {
            deposit(100L);

            var preview = sut.previewTrade(usdTrade(TradeType.BUY, "10", 1_300_000L, 0L, null));

            assertThat(preview.cashDelta()).isEqualTo(-1_000L);
            assertThat(preview.cashAfter()).isEqualTo(-900L);
            assertThat(preview.fundingAmount()).isZero();
            assertThat(preview.fundingCurrency()).isNull();
        }
    }
}
