package io.groo.damda.expense.service;

import io.groo.core.type.YNType;
import io.groo.damda.asset.service.AssetService;
import io.groo.damda.asset.service.dto.AssetServiceDto;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.type.RecurringFrequency;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.asset.service.AssetBalanceHistoryService;
import io.groo.damda.expense.domain.ExpenseCategory;
import io.groo.damda.expense.domain.RecurringTransaction;
import io.groo.damda.expense.repository.ExpenseCategoryRepository;
import io.groo.damda.expense.repository.ExpenseRepository;
import io.groo.damda.expense.repository.RecurringTransactionRepository;
import io.groo.damda.expense.service.dto.RecurringTransactionServiceDto;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.expense.type.TxKind;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import io.groo.core.time.ServiceClock;
import io.groo.core.time.UserClock;

/**
 * 반복 거래 정책 회귀 방지 단위 테스트 — 거래와 동일하게 leaf 카테고리만, 소유권 검증.
 */
@ExtendWith(MockitoExtension.class)
class RecurringTransactionServiceImplTest {

    @Mock private RecurringTransactionRepository recurringTransactionRepository;
    @Mock private ExpenseCategoryRepository expenseCategoryRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private ExpenseRepository expenseRepository;
    @Mock private UserRepository userRepository;
    @Mock private AssetBalanceHistoryService balanceHistoryService;
    @Mock private AssetService assetService;
    // 날짜 판정용 — mock 이면 null 이 흘러 NPE. 실물을 주입하되 사용자 조회는 비어
    // 서비스 기준(Asia/Seoul)으로 폴백한다.
    @Spy private UserClock userClock = new UserClock(rowId -> null, new ServiceClock("Asia/Seoul"));
    // 배치 기준 날짜 — 실물을 준다. 다음 실행일은 오늘이 아니라 <b>규칙의 nextExecutionDate</b>
    // 에서 계산되므로 이 값에 물리지 않는다(테스트가 today()±N 에 의존하지 않게).
    @Spy private ServiceClock serviceClock = new ServiceClock("Asia/Seoul");

    // 배치는 건마다 새 트랜잭션을 연다 — 단위 테스트에서는 상태만 돌려주고 커밋은 no-op.
    @Mock private PlatformTransactionManager transactionManager;

    /**
     * 참조 해석(카테고리·자산)은 <b>진짜</b>를 준다 — 목으로 바꾸면 "남의 카테고리 거절"
     * 같은 단언이 스텁을 확인하는 셈이 되어 아무것도 안 지킨다. 리포지토리 목을 그대로
     * 물리므로 기존 스텁·단언의 뜻이 유지된다.
     */
    private RecurringTransactionServiceImpl sut;

    @BeforeEach
    void buildSut() {
        sut = new RecurringTransactionServiceImpl(
            recurringTransactionRepository, userClock, expenseCategoryRepository,
            assetRepository, expenseRepository, userRepository, balanceHistoryService,
            assetService, serviceClock,
            new ReservationRefs(expenseCategoryRepository, assetRepository),
            transactionManager);
    }

    @BeforeEach
    void givenTransaction() {
        lenient().when(transactionManager.getTransaction(any()))
            .thenReturn(new SimpleTransactionStatus());
    }

    private static final long USER_ID = 1L;

    private User user(long rowId) {
        User u = User.createUser(null, "tester", "테스터", "tester@porest.com");
        ReflectionTestUtils.setField(u, "rowId", rowId);
        return u;
    }

    private ExpenseCategory category(long rowId, User owner) {
        ExpenseCategory c = ExpenseCategory.createCategory(owner, "식비", "tag", "#fff", ExpenseType.EXPENSE, null);
        ReflectionTestUtils.setField(c, "rowId", rowId);
        return c;
    }

    private RecurringTransactionServiceDto.CreateCommand createCmd(long categoryRowId) {
        return new RecurringTransactionServiceDto.CreateCommand(
                USER_ID, categoryRowId, null, null, null, null, null, TxKind.EXPENSE, 10_000L,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private RecurringTransactionServiceDto.UpdateCommand updateCmd(long categoryRowId) {
        return new RecurringTransactionServiceDto.UpdateCommand(
                categoryRowId, null, null, null, null, TxKind.EXPENSE, 10_000L,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("createRecurring — 자식 보유(상위) 카테고리에는 반복 거래 불가")
    void createRejectsNonLeafCategory() {
        User u = user(USER_ID);
        ExpenseCategory parent = category(10L, u);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(parent));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(true);

        assertThatThrownBy(() -> sut.createRecurring(createCmd(10L)))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("createRecurring — 자동으로 만들어진 거래(이월 등)는 원본이 될 수 없다")
    void createRejectsAutoGeneratedSource() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        // 이월로 반복을 만들면 매달 "이전 미결제 사용액" 이라는 일반 지출이 새로 생긴다.
        Expense carryover = org.mockito.Mockito.mock(Expense.class);
        given(carryover.getUser()).willReturn(u);
        given(carryover.isAutoGenerated()).willReturn(true);
        given(expenseRepository.findById(77L)).willReturn(Optional.of(carryover));

        var cmd = new RecurringTransactionServiceDto.CreateCommand(
                USER_ID, 10L, null, null, null, null, 77L, TxKind.EXPENSE, 10_000L,
                null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> sut.createRecurring(cmd))
                .isInstanceOf(InvalidValueException.class)
                .satisfies(e -> assertThat(((InvalidValueException) e).getErrorCode())
                        .isEqualTo(DeskErrorCode.EXPENSE_AUTO_GENERATED_NO_DERIVE));
    }

    @Test
    @DisplayName("createRecurring — 남의 카테고리에는 반복 거래 불가")
    void createRejectsOthersCategory() {
        User u = user(USER_ID);
        ExpenseCategory othersCategory = category(20L, user(999L));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(20L)).willReturn(Optional.of(othersCategory));

        assertThatThrownBy(() -> sut.createRecurring(createCmd(20L)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("updateRecurring — 자식 보유(상위) 카테고리로 변경 불가")
    void updateRejectsNonLeafCategory() {
        User u = user(USER_ID);
        RecurringTransaction recurring = mock(RecurringTransaction.class);
        given(recurring.getUser()).willReturn(u);
        ExpenseCategory parent = category(30L, u);
        given(recurringTransactionRepository.findById(5L)).willReturn(Optional.of(recurring));
        given(expenseCategoryRepository.findById(30L)).willReturn(Optional.of(parent));
        given(expenseCategoryRepository.hasChildren(30L)).willReturn(true);

        assertThatThrownBy(() -> sut.updateRecurring(5L, USER_ID, updateCmd(30L)))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("updateRecurring — 남의 반복 거래는 수정 불가")
    void updateRejectsOthersRecurring() {
        RecurringTransaction recurring = mock(RecurringTransaction.class);
        given(recurring.getUser()).willReturn(user(999L));
        given(recurringTransactionRepository.findById(5L)).willReturn(Optional.of(recurring));

        assertThatThrownBy(() -> sut.updateRecurring(5L, USER_ID, updateCmd(30L)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("updateRecurring — 남의 자산으로 변경 불가(소유권 검증 누락 보강)")
    void updateRejectsOthersAsset() {
        User u = user(USER_ID);
        RecurringTransaction recurring = mock(RecurringTransaction.class);
        given(recurring.getUser()).willReturn(u);
        given(recurringTransactionRepository.findById(5L)).willReturn(Optional.of(recurring));
        Asset othersAsset = mock(Asset.class);
        given(othersAsset.getUser()).willReturn(user(999L));
        given(assetRepository.findById(20L)).willReturn(Optional.of(othersAsset));

        var cmd = new RecurringTransactionServiceDto.UpdateCommand(
                null, 20L, null, null, null, TxKind.EXPENSE, 10_000L,
                null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> sut.updateRecurring(5L, USER_ID, cmd))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("getRecurrings — 음수 limit 은 거부(전체 반환 방지)")
    void getRecurringsRejectsNegativeLimit() {
        assertThatThrownBy(() -> sut.getRecurrings(USER_ID, false, -1))
                .isInstanceOf(InvalidValueException.class);
    }

    @Nested
    @DisplayName("금액 부호 — 실행될 때마다 잔액이 거꾸로 간다")
    class AmountSign {

        private RecurringTransactionServiceDto.CreateCommand cmdAmount(Long amount) {
            return new RecurringTransactionServiceDto.CreateCommand(
                USER_ID, 1L, null, null, null, null, null, TxKind.EXPENSE, amount,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Test
        @DisplayName("음수 금액으로 반복거래를 만들 수 없다 — 매달 잔액이 늘어난다")
        void rejectsNegative() {
            assertThatThrownBy(() -> sut.createRecurring(cmdAmount(-10_000L)))
                .isInstanceOf(InvalidValueException.class);
        }

        @Test
        @DisplayName("0원 반복거래도 막는다")
        void rejectsZero() {
            assertThatThrownBy(() -> sut.createRecurring(cmdAmount(0L)))
                .isInstanceOf(InvalidValueException.class);
        }

        @Test
        @DisplayName("수정에서도 음수를 막는다")
        void rejectsNegativeOnUpdate() {
            var cmd = new RecurringTransactionServiceDto.UpdateCommand(
                1L, null, null, null, null, TxKind.EXPENSE, -5_000L,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
            assertThatThrownBy(() -> sut.updateRecurring(1L, USER_ID, cmd))
                .isInstanceOf(InvalidValueException.class);
        }

        @Test
        @DisplayName("100억 초과도 막는다 — 반복 설정이 거래 상한을 우회하는 경로였다(QA #54)")
        void rejectsOverTxLimit() {
            assertThatThrownBy(() -> sut.createRecurring(cmdAmount(10_000_000_001L)))
                .isInstanceOf(InvalidValueException.class);
        }

        @Test
        @DisplayName("수정에서도 100억 초과를 막는다")
        void rejectsOverTxLimitOnUpdate() {
            var cmd = new RecurringTransactionServiceDto.UpdateCommand(
                1L, null, null, null, null, TxKind.EXPENSE, 10_000_000_001L,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
            assertThatThrownBy(() -> sut.updateRecurring(1L, USER_ID, cmd))
                .isInstanceOf(InvalidValueException.class);
        }
    }

    @Nested
    @DisplayName("이체 반복")
    class Transfer {

        private Asset asset(long rowId, AssetType type, User owner) {
            Asset a = Asset.createAsset(owner, "자산" + rowId, type, 0L, "KRW", null,
                null, null, null, null, YNType.Y, null, null, null, null);
            ReflectionTestUtils.setField(a, "rowId", rowId);
            return a;
        }

        /** 보내는 2 · 받는 3 을 주고 나머지 칸만 바꿔 가며 부른다. */
        private RecurringTransactionServiceDto.CreateCommand transferCmd(
                Long toAssetRowId, Long amount, Long fee, Long interest, Long categoryRowId) {
            return new RecurringTransactionServiceDto.CreateCommand(
                USER_ID, categoryRowId, 2L, toAssetRowId, fee, interest, null,
                TxKind.TRANSFER, amount, "적금 이체", null, null,
                RecurringFrequency.MONTHLY, 1, null, 15, null,
                LocalDate.of(2026, 9, 15), null, null, null, null);
        }

        private void givenAssets(AssetType from, AssetType to) {
            User u = user(USER_ID);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
            given(assetRepository.findById(2L)).willReturn(Optional.of(asset(2L, from, u)));
            given(assetRepository.findById(3L)).willReturn(Optional.of(asset(3L, to, u)));
        }

        @Test
        @DisplayName("정상 — 예금에서 적금으로 매달 보내는 규칙이 저장된다")
        void savesTransferRule() {
            givenAssets(AssetType.BANK_ACCOUNT, AssetType.SAVINGS);

            var info = sut.createRecurring(transferCmd(3L, 100_000L, 500L, null, null));

            assertThat(info.expenseType()).isEqualTo(TxKind.TRANSFER);
            assertThat(info.toAssetRowId()).isEqualTo(3L);
            assertThat(info.fee()).isEqualTo(500L);
            then(recurringTransactionRepository).should().save(any(RecurringTransaction.class));
        }

        @Test
        @DisplayName("고정 표식을 달아 보내도 이체는 늘 변동이다 — 이체는 수입·지출 통계에 안 잡힌다")
        void transferIgnoresFixedFlag() {
            givenAssets(AssetType.BANK_ACCOUNT, AssetType.SAVINGS);
            var base = transferCmd(3L, 100_000L, null, null, null);
            var cmd = new RecurringTransactionServiceDto.CreateCommand(
                base.userRowId(), base.categoryRowId(), base.assetRowId(), base.toAssetRowId(), base.fee(),
                base.interestAmount(), base.sourceExpenseRowId(), base.expenseType(), base.amount(),
                base.description(), base.merchant(), base.paymentMethod(), base.frequency(),
                base.intervalValue(), base.dayOfWeek(), base.dayOfMonth(), base.executionTime(),
                base.startDate(), base.endDate(), base.maxOccurrences(), base.autoLog(),
                base.notifyDayBefore(), true);

            var info = sut.createRecurring(cmd);

            assertThat(info.isFixed()).isFalse();
        }

        @Test
        @DisplayName("신용카드는 이체 상대가 될 수 없다 — 카드 결제일 자동이체와 이중으로 빠진다")
        void rejectsCreditCard() {
            givenAssets(AssetType.BANK_ACCOUNT, AssetType.CREDIT_CARD);

            assertThatThrownBy(() -> sut.createRecurring(transferCmd(3L, 100_000L, null, null, null)))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).getErrorCode())
                .isEqualTo(DeskErrorCode.RECURRING_TRANSFER_CARD_NOT_ALLOWED);
        }

        @Test
        @DisplayName("체크카드도 막는다 — 보내는 쪽이어도 마찬가지")
        void rejectsCheckCardOnSendingSide() {
            givenAssets(AssetType.CHECK_CARD, AssetType.BANK_ACCOUNT);

            assertThatThrownBy(() -> sut.createRecurring(transferCmd(3L, 100_000L, null, null, null)))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).getErrorCode())
                .isEqualTo(DeskErrorCode.RECURRING_TRANSFER_CARD_NOT_ALLOWED);
        }

        @Test
        @DisplayName("받는 계좌가 없으면 저장되지 않는다 — 자정마다 조용히 실패할 규칙이 된다")
        void rejectsMissingToAsset() {
            User u = user(USER_ID);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
            given(assetRepository.findById(2L)).willReturn(Optional.of(asset(2L, AssetType.BANK_ACCOUNT, u)));

            assertThatThrownBy(() -> sut.createRecurring(transferCmd(null, 100_000L, null, null, null)))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).getErrorCode())
                .isEqualTo(DeskErrorCode.REQUIRED_VALUE_MISSING);
        }

        @Test
        @DisplayName("이자는 받는 계좌가 대출일 때만 — 적금에 이자를 붙이면 거절한다")
        void rejectsInterestOnNonLoan() {
            givenAssets(AssetType.BANK_ACCOUNT, AssetType.SAVINGS);

            assertThatThrownBy(() -> sut.createRecurring(transferCmd(3L, 100_000L, null, 10_000L, null)))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).getErrorCode())
                .isEqualTo(DeskErrorCode.ASSET_TRANSFER_INVALID_INTEREST);
        }

        @Test
        @DisplayName("이자는 상환액을 넘을 수 없다 — 공용 이체 규칙과 같은 한 벌을 쓴다")
        void rejectsInterestOverAmount() {
            givenAssets(AssetType.BANK_ACCOUNT, AssetType.LOAN);

            assertThatThrownBy(() -> sut.createRecurring(transferCmd(3L, 100_000L, null, 200_000L, null)))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).getErrorCode())
                .isEqualTo(DeskErrorCode.ASSET_TRANSFER_INVALID_INTEREST);
        }

        @Test
        @DisplayName("대출 상환이면 이자가 허용된다")
        void allowsInterestOnLoan() {
            givenAssets(AssetType.BANK_ACCOUNT, AssetType.LOAN);

            var info = sut.createRecurring(transferCmd(3L, 100_000L, null, 20_000L, null));

            assertThat(info.interestAmount()).isEqualTo(20_000L);
        }

        @Test
        @DisplayName("이체에는 카테고리가 없다 — 실려 오면 거절한다")
        void rejectsCategoryOnTransfer() {
            User u = user(USER_ID);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
            given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(category(10L, u)));

            assertThatThrownBy(() -> sut.createRecurring(transferCmd(3L, 100_000L, null, null, 10L)))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).getErrorCode())
                .isEqualTo(DeskErrorCode.EXPENSE_TYPE_CATEGORY_MISMATCH);
        }

        @Test
        @DisplayName("지출인데 이체 칸이 채워져 있으면 거절한다 — 종류만 바꿨을 때 쓰레기 값이 살아난다")
        void rejectsTransferFieldsOnExpense() {
            User u = user(USER_ID);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
            given(assetRepository.findById(2L)).willReturn(Optional.of(asset(2L, AssetType.BANK_ACCOUNT, u)));
            given(assetRepository.findById(3L)).willReturn(Optional.of(asset(3L, AssetType.BANK_ACCOUNT, u)));

            var cmd = new RecurringTransactionServiceDto.CreateCommand(
                USER_ID, null, 2L, 3L, null, null, null,
                TxKind.EXPENSE, 10_000L, "점심", null, null,
                RecurringFrequency.MONTHLY, 1, null, 15, null,
                LocalDate.of(2026, 9, 15), null, null, null, null);

            assertThatThrownBy(() -> sut.createRecurring(cmd))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).getErrorCode())
                .isEqualTo(DeskErrorCode.INVALID_INPUT);
        }
    }

    @Nested
    @DisplayName("이체 반복 실행(자정 배치)")
    class TransferExecution {

        // 오늘 = 회차 날(9/15). 배치는 오늘까지 도래한 회차를 한 번에 따라잡는다(QA 30 4) —
        // 벽시계를 쓰면 10/15 가 지나는 날부터 두 회차가 돌아 "이체 한 번" 단언이 깨진다.
        @BeforeEach
        void pinToday() {
            lenient().doReturn(LocalDate.of(2026, 9, 15)).when(serviceClock).today();
        }

        private RecurringTransaction dueTransfer(Long fee, Long interest) {
            User u = user(USER_ID);
            Asset from = Asset.createAsset(u, "예금", AssetType.BANK_ACCOUNT, 0L, "KRW", null,
                null, null, null, null, YNType.Y, null, null, null, null);
            ReflectionTestUtils.setField(from, "rowId", 2L);
            Asset to = Asset.createAsset(u, "적금", AssetType.SAVINGS, 0L, "KRW", null,
                null, null, null, null, YNType.Y, null, null, null, null);
            ReflectionTestUtils.setField(to, "rowId", 3L);

            RecurringTransaction r = RecurringTransaction.createRecurring(
                u, null, from, to, fee, interest, null,
                TxKind.TRANSFER, 100_000L, "적금 이체", null, null,
                RecurringFrequency.MONTHLY, 1, null, 15, null,
                LocalDate.of(2026, 1, 15), null, null,
                LocalDate.of(2026, 9, 15), true, false);
            ReflectionTestUtils.setField(r, "rowId", 77L);
            return r;
        }

        @Test
        @DisplayName("이체를 만든다 — 지출로 기록하지 않는다")
        void createsTransferNotExpense() {
            RecurringTransaction r = dueTransfer(500L, null);
            given(recurringTransactionRepository.findDueTransactions(any())).willReturn(List.of(r));
            given(recurringTransactionRepository.findById(77L)).willReturn(Optional.of(r));
            given(assetService.createTransfer(any())).willReturn(transferInfo(900L));

            sut.executeDueTransactions();

            ArgumentCaptor<AssetServiceDto.CreateTransferCommand> captor =
                ArgumentCaptor.forClass(AssetServiceDto.CreateTransferCommand.class);
            then(assetService).should().createTransfer(captor.capture());
            var sent = captor.getValue();
            assertThat(sent.fromAssetRowId()).isEqualTo(2L);
            assertThat(sent.toAssetRowId()).isEqualTo(3L);
            assertThat(sent.amount()).isEqualTo(100_000L);
            assertThat(sent.fee()).isEqualTo(500L);

            then(expenseRepository).should(never()).save(any());
            then(balanceHistoryService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("autoSource 를 걸지 않는다 — 만들어진 이체는 반복 지출처럼 고칠 수 있어야 한다")
        void leavesGeneratedTransferEditable() {
            RecurringTransaction r = dueTransfer(null, null);
            given(recurringTransactionRepository.findDueTransactions(any())).willReturn(List.of(r));
            given(recurringTransactionRepository.findById(77L)).willReturn(Optional.of(r));
            given(assetService.createTransfer(any())).willReturn(transferInfo(900L));

            sut.executeDueTransactions();

            ArgumentCaptor<AssetServiceDto.CreateTransferCommand> captor =
                ArgumentCaptor.forClass(AssetServiceDto.CreateTransferCommand.class);
            then(assetService).should().createTransfer(captor.capture());
            assertThat(captor.getValue().autoSource()).isNull();
        }

        @Test
        @DisplayName("실행하면 다음 날짜로 넘어간다 — 지출 반복과 같은 흐름")
        void advancesToNextDate() {
            RecurringTransaction r = dueTransfer(null, null);
            given(recurringTransactionRepository.findDueTransactions(any())).willReturn(List.of(r));
            given(recurringTransactionRepository.findById(77L)).willReturn(Optional.of(r));
            given(assetService.createTransfer(any())).willReturn(transferInfo(900L));

            sut.executeDueTransactions();

            assertThat(r.getExecutedCount()).isEqualTo(1);
            assertThat(r.getNextExecutionDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        }

        @Test
        @DisplayName("한 건이 터져도 나머지는 남는다 — 건마다 트랜잭션을 따로 연다")
        void oneFailureDoesNotPoisonTheBatch() {
            RecurringTransaction bad = dueTransfer(null, null);
            RecurringTransaction good = dueTransfer(null, null);
            ReflectionTestUtils.setField(good, "rowId", 78L);
            given(recurringTransactionRepository.findDueTransactions(any()))
                .willReturn(List.of(bad, good));
            given(recurringTransactionRepository.findById(77L)).willReturn(Optional.of(bad));
            given(recurringTransactionRepository.findById(78L)).willReturn(Optional.of(good));
            // 받는 계좌를 지운 규칙 — createTransfer 가 터진다.
            given(assetService.createTransfer(any()))
                .willThrow(new EntityNotFoundException(DeskErrorCode.ASSET_NOT_FOUND))
                .willReturn(transferInfo(900L));

            sut.executeDueTransactions();

            // 실패한 건은 실행 표시가 안 되고, 뒤 건은 그대로 실행된다.
            assertThat(bad.getExecutedCount()).isZero();
            assertThat(good.getExecutedCount()).isEqualTo(1);
            // 배치 하나가 아니라 건마다 트랜잭션을 연다 — 실패가 앞뒤로 번지지 않는 근거.
            then(transactionManager).should(times(2)).getTransaction(any());
        }

        private AssetServiceDto.TransferInfo transferInfo(long rowId) {
            return new AssetServiceDto.TransferInfo(
                rowId, USER_ID, 2L, "예금", 3L, "적금",
                100_000L, null, null, 100_000L, "적금 이체", null,
                LocalDateTime.of(2026, 9, 15, 9, 0), LocalDateTime.of(2026, 9, 15, 9, 0), null, true);
        }
    }

    @Nested
    @DisplayName("지출·수입 반복 실행 — 고정 표식을 만들어진 거래가 물려받는다")
    class FixedExecution {

        @BeforeEach
        void pinToday() {
            lenient().doReturn(LocalDate.of(2026, 9, 15)).when(serviceClock).today();
        }

        private RecurringTransaction due(TxKind kind, boolean fixed) {
            User u = user(USER_ID);
            RecurringTransaction r = RecurringTransaction.createRecurring(
                u, category(10L, u), null, null, null, null, null,
                kind, 700_000L, "월세", null, null,
                RecurringFrequency.MONTHLY, 1, null, 15, null,
                LocalDate.of(2026, 1, 15), null, null,
                LocalDate.of(2026, 9, 15), true, false);
            r.markFixed(fixed);
            ReflectionTestUtils.setField(r, "rowId", 88L);
            return r;
        }

        private Expense runAndCapture(RecurringTransaction r) {
            given(recurringTransactionRepository.findDueTransactions(any())).willReturn(List.of(r));
            given(recurringTransactionRepository.findById(88L)).willReturn(Optional.of(r));
            sut.executeDueTransactions();
            ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
            then(expenseRepository).should().save(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("고정 지출 반복이 만든 거래는 고정이다")
        void fixedExpenseRecurringMakesFixedExpense() {
            Expense made = runAndCapture(due(TxKind.EXPENSE, true));

            assertThat(made.isFixedTx()).isTrue();
        }

        @Test
        @DisplayName("고정 수입 반복이 만든 거래는 고정이다")
        void fixedIncomeRecurringMakesFixedIncome() {
            Expense made = runAndCapture(due(TxKind.INCOME, true));

            assertThat(made.isFixedTx()).isTrue();
        }

        @Test
        @DisplayName("표식 없는 반복이 만든 거래는 변동이다 — 기존 반복의 동작이 안 바뀐다")
        void plainRecurringMakesVariable() {
            Expense made = runAndCapture(due(TxKind.EXPENSE, false));

            assertThat(made.isFixedTx()).isFalse();
        }

        @Test
        @DisplayName("이체로 바꾸면 켜 둔 표식이 꺼진다")
        void switchingToTransferTurnsItOff() {
            RecurringTransaction r = due(TxKind.EXPENSE, true);
            ReflectionTestUtils.setField(r, "expenseType", TxKind.TRANSFER);
            r.markFixed(true);

            assertThat(r.isFixedTx()).isFalse();
        }
    }
}
