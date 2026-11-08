package io.groo.damda.expense.service;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.asset.service.AssetBalanceHistoryService;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.card.service.CardCycleMath;
import io.groo.damda.card.service.CardPaymentService;
import io.groo.damda.card.service.PaymentSchedule;
import io.groo.damda.card.service.PaymentScheduleService;
import io.groo.damda.card.service.dto.CardPaymentServiceDto;
import io.groo.damda.asset.service.AssetService;
import io.groo.damda.calendar.domain.CalendarEvent;
import io.groo.damda.calendar.repository.CalendarEventRepository;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.domain.ExpenseAggregates;
import io.groo.damda.expense.domain.ExpenseBudget;
import io.groo.damda.expense.domain.ExpenseCategory;
import io.groo.damda.expense.domain.ExpenseSplit;
import io.groo.damda.expense.repository.ExpenseBudgetRepository;
import io.groo.damda.expense.repository.ExpenseCategoryRepository;
import io.groo.damda.expense.repository.ExpenseRepository;
import io.groo.damda.expense.repository.ExpenseSplitRepository;
import io.groo.damda.expense.service.dto.ExpenseServiceDto;
import io.groo.damda.expense.service.dto.ExpenseSplitServiceDto;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.notification.service.NotificationMessages;
import io.groo.damda.notification.service.NotificationService;
import io.groo.damda.notification.service.dto.NotificationServiceDto;
import io.groo.damda.notification.type.NotificationType;
import io.groo.damda.notification.type.ReferenceType;
import io.groo.damda.todo.domain.Todo;
import io.groo.damda.todo.repository.TodoRepository;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import io.groo.damda.user.service.UserService;
import io.groo.core.time.ServiceClock;
import io.groo.core.time.UserClock;
import io.groo.core.type.YNType;
import io.groo.damda.dutchpay.domain.DutchPay;
import io.groo.damda.expense.domain.RecurringTransaction;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ExpenseServiceImpl implements ExpenseService {
    private final ExpenseRepository expenseRepository;
    private final UserClock userClock;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final ExpenseBudgetRepository expenseBudgetRepository;
    private final ExpenseSplitRepository expenseSplitRepository;
    private final ExpenseSplitService expenseSplitService;
    private final NotificationService notificationService;
    private final NotificationMessages notificationMessages;
    private final UserService userService;
    private final AssetRepository assetRepository;
    private final AssetBalanceHistoryService balanceHistoryService;
    private final CalendarEventRepository calendarEventRepository;
    private final TodoRepository todoRepository;
    private final UserRepository userRepository;
    private final CardPaymentService cardPaymentService;
    private final AssetService assetService;
    /** 회차별 결제일(D5) — 카드 거래의 닫힌 회차 판정. */
    private final PaymentScheduleService paymentScheduleService;
    /** 닫힌 회차 판정의 "오늘" — 자정 배치와 같은 서울 시계(D11). */
    private final ServiceClock serviceClock;
    /** 고쳐 쓰기 — 더치페이·반복 규칙의 원거래 연결을 새 거래로 옮긴다. */
    private final EntityManager entityManager;

    @Override
    @Transactional
    public ExpenseServiceDto.ExpenseInfo createExpense(ExpenseServiceDto.CreateCommand command) {
        return createExpense(command, false);
    }

    /**
     * 대량 적재 — 여러 건을 <b>한 트랜잭션</b>에 넣는다.
     *
     * <p>건별 트랜잭션이면 행마다 커밋(디스크 동기화)이 일어나 1만 건에 커밋만 1만 번이다.
     * 묶어서 커밋하면 그 비용이 청크 수만큼으로 줄고, 같은 사용자·카테고리·자산 조회도
     * 영속성 컨텍스트에 캐시돼 청크당 한 번만 SQL 을 탄다.
     *
     * <p>한 건이라도 실패하면 트랜잭션이 통째로 롤백된다. 호출자는 그때 <b>건별로 재시도</b>해
     * 문제 행만 가려내야 한다(부분 성공 보장).
     */
    @Override
    @Transactional
    public void createExpensesChunk(List<ExpenseServiceDto.CreateCommand> commands) {
        for (ExpenseServiceDto.CreateCommand c : commands) {
            createExpense(c, true);
        }
    }

    @Override
    @Transactional
    public ExpenseServiceDto.ExpenseInfo createExpense(ExpenseServiceDto.CreateCommand command, boolean bulk) {
        log.debug("지출 등록 시작: userRowId={}, amount={}", command.userRowId(), command.amount());

        Created created = persistExpense(command, bulk);
        Expense expense = created.expense();

        // 예산 임계 도달 시 알림 (생성이므로 이전 기여분 없음). 생성 시점엔 분할이 아직 없어 거래 카테고리로 귀속.
        // 대량 적재에선 건너뛴다 — 행 수만큼 알림이 쏟아지고 매번 집계 쿼리가 돈다.
        if (!bulk) {
            notifyBudgetThresholdIfCrossed(expense, 0L, Map.of());
        }

        log.info("지출 등록 완료: expenseId={}, userRowId={}", expense.getRowId(), command.userRowId());

        ExpenseServiceDto.ExpenseInfo info = ExpenseServiceDto.ExpenseInfo.from(
            expense, List.of(), refundedAmountOf(created.settled()));
        // 대량 적재는 응답을 안 쓴다 — 행마다 결제일 이력을 읽지 않는다.
        return bulk ? info : info.withLock(expense, moneyLockJudge().test(expense));
    }

    /** 새로 적은 거래와 그 정산 결과(열린 회차 카드 수입이면 선결제 환급이 실릴 수 있다). */
    private record Created(Expense expense, CardPaymentServiceDto.SettlementResult settled) {}

    /**
     * 거래 한 건을 적고 카드 회차 규칙으로 정산한다 — 새 저장과 고쳐 쓰기(D13)가 같은 길을 탄다.
     *
     * <p>닫힌 회차에 떨어진 카드 몫은 기록용(표식 + 상계)이 된다(D1·D2). 예산 알림은 호출자가 띄운다
     * — 고쳐 쓰기는 옛 거래의 기여분을 기준으로 삼아야 같은 금액에 알림이 다시 뜨지 않는다.
     */
    private Created persistExpense(ExpenseServiceDto.CreateCommand command, boolean bulk) {
        validateAmount(command.amount());

        User user = userRepository.findById(command.userRowId())
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));

        ExpenseCategory category = expenseCategoryRepository.findById(command.categoryRowId())
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.EXPENSE_CATEGORY_NOT_FOUND));
        validateCategoryOwnership(category, command.userRowId());
        // 거래 유형 == 카테고리 유형 강제 (수입 거래는 수입 카테고리에만, 지출은 지출에만).
        // 프론트가 타입별로 카테고리를 거르지만 API 2차 가드 — 혼재 시 집계(breakdown) 오염 방지.
        if (category.getExpenseType() != command.expenseType()) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_TYPE_CATEGORY_MISMATCH);
        }

        // leaf 확인은 매번 쿼리라 영속성 컨텍스트 캐시가 듣지 않는다.
        // 대량 적재는 가져오기 리졸버가 leaf 만 반환하므로 행마다 다시 확인하지 않는다.
        if (!bulk && expenseCategoryRepository.hasChildren(category.getRowId())) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_NOT_LEAF);
        }

        Asset asset = null;
        if (command.assetRowId() != null) {
            asset = assetRepository.findById(command.assetRowId())
                .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.ASSET_NOT_FOUND));
            validateAssetOwnership(asset, command.userRowId());
        }

        Expense expense = Expense.createExpense(
            user, category, asset,
            command.expenseType(),
            command.amount(),
            command.description(),
            command.expenseDate(),
            command.merchant(),
            command.paymentMethod(),
            command.installmentMonths(),
            command.originalAmount(), command.originalCurrency(), command.exchangeRate()
        );
        expense.markFixed(command.isFixed());

        if (command.calendarEventRowId() != null) {
            CalendarEvent event = calendarEventRepository.findById(command.calendarEventRowId())
                .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.CALENDAR_EVENT_NOT_FOUND));
            expense.setCalendarEvent(event);
        }

        if (command.todoRowId() != null) {
            Todo todo = todoRepository.findById(command.todoRowId())
                .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.TODO_NOT_FOUND));
            expense.setTodo(todo);
        }

        expenseRepository.save(expense);

        // 자산 잔액 이력: 거래 flow 적재 — 잔액은 조회할 때 이력에서 집계한다
        // 금액은 원화 — 외화 계좌면 계좌 통화로 바꿔 적는다(같은 통화 원 통화 금액이 있으면 그 값).
        balanceHistoryService.recordExpenseKrw(asset, expense.getRowId(),
            command.expenseType(), command.amount(),
            expense.getOriginalAmount(), expense.getOriginalCurrency(), command.expenseDate());

        // 닫힌 회차에 떨어진 카드 몫은 기록용이다(D1·D2) — 결제일 당일도 닫혔다.
        CardPaymentServiceDto.SettlementResult settled = settleCard(expense, CardCycleMath.Side.none(),
            sideOf(expense), null, command.userRowId(), CardPaymentServiceDto.SettlementMode.CHANGE);
        return new Created(expense, settled);
    }

    @Override
    public List<ExpenseServiceDto.ExpenseInfo> getExpenses(Long userRowId, Long categoryRowId, Long assetRowId, ExpenseType expenseType, LocalDate startDate, LocalDate endDate) {
        log.debug("지출 목록 조회: userRowId={}, assetRowId={}, expenseType={}", userRowId, assetRowId, expenseType);
        validateDateRange(startDate, endDate);

        List<Expense> allExpenses = new java.util.ArrayList<>(
            expenseRepository.findByUser(userRowId, categoryRowId, expenseType, startDate, endDate));

        // Asset 필터 (서비스 층) — repo 쿼리 시그니처 확장 대신 여기서 후처리
        if (assetRowId != null) {
            allExpenses = allExpenses.stream()
                .filter(e -> e.getAsset() != null && assetRowId.equals(e.getAsset().getRowId()))
                .collect(java.util.stream.Collectors.toList());
        }

        allExpenses.sort(java.util.Comparator.comparing(Expense::getExpenseDate).reversed()
            .thenComparing(java.util.Comparator.comparing(Expense::getRowId).reversed()));

        // 분할 카테고리 id 를 bulk 로 적재(N+1 회피) — 목록 카테고리 필터를 split-aware 하게 하기 위해 노출.
        Map<Long, List<Long>> splitCatsByExpense = loadSplitCategoryIdsByExpense(allExpenses);
        Predicate<Expense> locked = moneyLockJudge();
        return allExpenses.stream()
            .map(e -> ExpenseServiceDto.ExpenseInfo.from(
                e,
                splitCatsByExpense.getOrDefault(e.getRowId(), List.of())).withLock(e, locked.test(e)))
            .toList();
    }

    /** 거래 목록의 활성 분할 카테고리 id 를 거래별로 묶어 반환(N+1 회피용 bulk 적재). */
    private Map<Long, List<Long>> loadSplitCategoryIdsByExpense(List<Expense> expenses) {
        List<Long> ids = expenses.stream().map(Expense::getRowId).toList();
        if (ids.isEmpty()) return Map.of();
        return expenseSplitRepository.findByExpenseIds(ids).stream()
            .collect(Collectors.groupingBy(
                s -> s.getExpense().getRowId(),
                Collectors.mapping(s -> s.getCategory().getRowId(), Collectors.toList())));
    }

    @Override
    @Transactional
    public ExpenseServiceDto.ExpenseInfo updateExpense(Long expenseId, Long userRowId, ExpenseServiceDto.UpdateCommand command) {
        log.debug("지출 수정 시작: expenseId={}", expenseId);

        // 금액은 실렸을 때만 검사한다 — 안 실린 금액은 지금 값이 그대로 남고, 그 값은 이미 이 검사를 통과했다.
        // (조회보다 앞에 둔다 — 없는 거래에 잘못된 금액을 보내면 400 이 먼저다.)
        if (command.amount().present()) {
            validateAmount(command.amount().value());
        }

        Expense expense = findExpenseOrThrow(expenseId);
        validateExpenseOwnership(expense, userRowId);
        // 환불된 거래는 손댈 수 없다 — 돈은 이미 자산으로 돌아가 있어서, 여기서 금액을
        // 고치면 되돌릴 기준이 사라진다. 먼저 환불을 취소하게 한다(설계 2절 잠금).
        if (expense.isRefunded()) {
            throw new InvalidValueException(DeskErrorCode.REFUNDED_READONLY);
        }
        // 카드 이월 거래("이전 미결제 사용액")는 카드 설정에서만 바꾼다 — 가계부에서 분류·메모를 바꿔도
        // 이월이라는 뜻이 흐려질 뿐 얻는 게 없다(23차 10).
        if (expense.isCardCarryover()) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CARRYOVER_READONLY);
        }

        // 시스템이 만든 거래는 금액·자산·유형·일자를 못 고친다 — 원 거래(매도·이체)가 정한다.
        // 카테고리·메모·거래처만 반영하고, 잔액 이력은 손대지 않는다(원래 없는 게 맞다).
        if (expense.isAutoGenerated()) {
            ExpenseCategory editedCategory = command.categoryRowId()
                .map(rowId -> {
                    ExpenseCategory found = expenseCategoryRepository.findById(rowId)
                        .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.EXPENSE_CATEGORY_NOT_FOUND));
                    validateCategoryOwnership(found, userRowId);
                    return found;
                })
                .orKeep(expense.getCategory());
            expense.updateEditableFields(editedCategory,
                command.description().orKeep(expense.getDescription()),
                command.merchant().orKeep(expense.getMerchant()));
            // 고정 표식은 집계의 분류일 뿐 돈이 아니라 자동 생성 거래도 고칠 수 있다.
            expense.markFixed(command.isFixed().orKeep(expense.isFixedTx()));
            log.info("자동 생성 거래 부분 수정: expenseId={} (카테고리·메모·거래처만)", expenseId);
            return ExpenseServiceDto.ExpenseInfo.from(expense);
        }

        // 결제가 끝난 카드 거래는 돈 칸을 못 고친다(D12) — 고치려면 고쳐 쓰기(지우고 새로 적기).
        // 옛 웹·앱은 카테고리만 고쳐도 돈 칸을 늘 실어 보내므로 "실렸으면 거절" 이 아니라
        // "달라졌으면 거절" 이다. 통과하면 돈 칸은 저장값 그대로 두고 나머지만 반영한다.
        if (moneyLockJudge().test(expense)) {
            if (moneyChanged(expense, command)) {
                log.warn("결제 끝난 거래의 돈 칸 수정 거부: expenseId={}", expenseId);
                throw new InvalidValueException(DeskErrorCode.EXPENSE_MONEY_LOCKED);
            }
            return updateLockedExpense(expense, userRowId, command);
        }

        // 결제 완료 회차의 카드 거래가 줄어드는지 보려면 **병합 전** 값이 필요하다
        // (설계 13-3). 자산이 카드에서 빠지거나 날짜가 회차 밖으로 나가면 전액이,
        // 금액만 줄면 그 차액이 상한이다.
        Asset cardBefore = creditCardOf(expense);
        Long amountBefore = expense.getAmount();
        // 잔액 기록을 다시 계산할지 가르는 돈 칸(계좌 · 유형 · 금액 · 원 통화) — 병합 전 값.
        Long assetIdBefore = expense.getAsset() != null ? expense.getAsset().getRowId() : null;
        ExpenseType typeBefore = expense.getExpenseType();
        java.math.BigDecimal originalBefore = expense.getOriginalAmount();
        String originalCurrencyBefore = expense.getOriginalCurrency();
        LocalDateTime dateBefore = expense.getExpenseDate();
        String refundMemoBase = cardRefundMemo(expense, false);
        CardCycleMath.Side sideBefore = sideOf(expense);

        // 수정 전 이 거래의 EXPENSE 기여분 (수정 후 임계 돌파 판정용 delta 기준) — 총액 + 카테고리별(split-aware).
        // 변경 전 값으로 캡처해야 하므로 expense.updateExpense(...) 전에 계산한다. 분할이 있으면 그 분할로 귀속.
        // 합계에 없던 거래(예정·환불·카드 이월)는 기여가 0 이다 — 월 합계와 같은 규칙으로 센다.
        boolean countedBefore = countsInLedgerNow(expense);
        long previousTotal = (countedBefore && expense.getExpenseType() == ExpenseType.EXPENSE
                && expense.getAmount() != null) ? expense.getAmount() : 0L;
        Map<Long, Long> previousByCat = countedBefore
                ? expenseSpendRollup(List.of(expense), loadSplitsByExpense(List.of(expense)))
                : Map.of();

        // 실린 칸만 바꾼다 — 안 온 칸은 지금 값이 그대로 남는다(QA #96).
        // 조회가 필요한 칸(카테고리·자산·일정·할 일)은 <b>실렸을 때만</b> 찾는다.
        ExpenseCategory category = command.categoryRowId()
            .map(rowId -> findOwnedCategory(rowId, userRowId))
            .orKeep(expense.getCategory());
        ExpenseType expenseType = command.expenseType().orKeep(expense.getExpenseType());
        Long amount = command.amount().orKeep(expense.getAmount());
        LocalDateTime expenseDate = command.expenseDate().orKeep(expense.getExpenseDate());

        // 거래 유형 == 카테고리 유형 강제 (create 와 대칭).
        if (category.getExpenseType() != expenseType) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_TYPE_CATEGORY_MISMATCH);
        }
        // 정책: 상위(자식 보유) 카테고리에는 거래를 둘 수 없음.
        if (expenseCategoryRepository.hasChildren(category.getRowId())) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_NOT_LEAF);
        }

        Asset asset = command.assetRowId()
            .map(rowId -> {
                Asset found = assetRepository.findById(rowId)
                    .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.ASSET_NOT_FOUND));
                validateAssetOwnership(found, userRowId); // create 와 대칭 — 남의 자산 할당 차단
                return found;
            })
            .orKeep(expense.getAsset());

        // 환불 상한 — 생성과 같은 검사를 수정에도 건다. orKeep 이라 연결이 그대로여도 금액만 오를 수
        // 있고, 다른 원거래로 옮기면 그쪽 합계 기준으로 다시 봐야 한다.
        expense.updateExpense(
            category, asset,
            expenseType,
            amount,
            command.description().orKeep(expense.getDescription()),
            expenseDate,
            command.merchant().orKeep(expense.getMerchant()),
            command.paymentMethod().orKeep(expense.getPaymentMethod()),
            command.installmentMonths().orKeep(expense.getInstallmentMonths()),
            command.originalAmount().orKeep(expense.getOriginalAmount()),
            command.originalCurrency().orKeep(expense.getOriginalCurrency()),
            command.exchangeRate().orKeep(expense.getExchangeRate())
        );
        expense.markFixed(command.isFixed().orKeep(expense.isFixedTx()));

        // 자산 잔액 이력: 기존 flow soft-delete 후 새 flow 적재(자산 변경 포함).
        // 병합한 값을 쓴다 — 안 보낸 칸을 그대로 실으면 이력이 null 금액·null 일자로 남는다.
        // 돈 칸이 그대로면(메모 · 카테고리 · 날짜만) 저장된 증감을 그대로 두고 시각만 옮긴다 — 외화 계좌에서
        // 그 사이 계좌 환율을 고쳤어도 지난 거래의 잔액 기록이 흔들리지 않는다.
        boolean moneySame = Objects.equals(assetIdBefore, asset != null ? asset.getRowId() : null)
            && typeBefore == expenseType
            && Objects.equals(amountBefore, amount)
            && sameDecimal(originalBefore, expense.getOriginalAmount())
            && Objects.equals(originalCurrencyBefore, expense.getOriginalCurrency());
        if (!(moneySame && balanceHistoryService.retimeExpense(asset, expense.getRowId(), expenseDate))) {
            balanceHistoryService.removeExpense(expense.getRowId());
            balanceHistoryService.recordExpenseKrw(asset, expense.getRowId(),
                expenseType, amount, expense.getOriginalAmount(), expense.getOriginalCurrency(), expenseDate);
        }

        // 일정·할 일 연결은 <b>실렸을 때만</b> 손댄다 — 안 보낸 요청이 붙여 둔 연결을 끊으면 안 된다.
        expense.setCalendarEvent(command.calendarEventRowId()
            .map(rowId -> calendarEventRepository.findById(rowId)
                .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.CALENDAR_EVENT_NOT_FOUND)))
            .orKeep(expense.getCalendarEvent()));

        expense.setTodo(command.todoRowId()
            .map(rowId -> todoRepository.findById(rowId)
                .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.TODO_NOT_FOUND)))
            .orKeep(expense.getTodo()));

        // 분할 합 일치화: 분할이 있는 거래는 거래 금액과 분할 합이 항상 같아야 한다.
        // - splits != null: 클라이언트가 맞춘 분할로 교체. 합 == abs(금액) 검증은 replaceSplits 가 수행
        //   (이 시점 expense 는 새 금액으로 갱신돼 있으므로 새 금액 기준으로 검증된다) → 원자적 동시 수정.
        // - splits == null 인데 기존 활성 분할이 새 금액과 어긋남: 거부하여 클라이언트가 분할을
        //   맞춰 다시 저장하도록 유도(거래 금액↔분할 합 불변식 보호).
        if (command.splits() != null) {
            expenseSplitService.replaceSplits(new ExpenseSplitServiceDto.ReplaceCommand(
                expenseId, userRowId, command.splits()));
            // replaceSplits 가 영속성 컨텍스트를 flush·clear 하므로 응답은 재조회로 구성한다.
            expense = findExpenseOrThrow(expenseId);
        } else if (command.amount().present()) {
            List<ExpenseSplit> existingSplits = expenseSplitRepository.findByExpense(expenseId);
            if (!existingSplits.isEmpty()) {
                long splitSum = existingSplits.stream().mapToLong(ExpenseSplit::getAmount).sum();
                if (splitSum != Math.abs(amount)) {
                    log.warn("분할 합 불일치로 지출 수정 거부 - expenseId={}, splitSum={}, amount={}",
                        expenseId, splitSum, amount);
                    throw new InvalidValueException(DeskErrorCode.EXPENSE_SPLIT_AMOUNT_MISMATCH);
                }
            }
        }

        // 카드 회차 규칙으로 다시 판정한다 — 열린 회차 청구가 줄면 선결제 환급(D3), 닫힌 회차로
        // 옮기면 기록용(저장 뒤에는 잠긴다, D12).
        boolean assetLeftCard = cardBefore != null
            && (asset == null || !cardBefore.getRowId().equals(asset.getRowId()));
        boolean dateMoved = dateBefore != null && !dateBefore.equals(expenseDate);
        boolean amountReduced = amountBefore != null && amount != null && amount < amountBefore;
        // 날짜를 옮기거나 자산을 바꾼 것은 "감액" 이 아니다 — 메모를 나눠 이체 목록에서
        // 무슨 일이 있었는지 읽을 수 있게 한다.
        String memo = (!assetLeftCard && !dateMoved && amountReduced)
            ? refundMemoBase + " · 감액분"
            : refundMemoBase;
        CardPaymentServiceDto.SettlementResult settled = settleCard(expense, sideBefore, sideOf(expense),
            memo, userRowId, CardPaymentServiceDto.SettlementMode.CHANGE);
        Long refundedAmount = refundedAmountOf(settled);

        // 예산 임계 도달 시 알림 — 분할 영속화 이후에 실행해 새 분할까지 반영된 카테고리 귀속으로 판정.
        notifyBudgetThresholdIfCrossed(expense, previousTotal, previousByCat);

        log.info("지출 수정 완료: expenseId={}, 환급={}", expenseId, refundedAmount);

        return ExpenseServiceDto.ExpenseInfo.from(expense, List.of(), refundedAmount)
            .withLock(expense, moneyLockJudge().test(expense));
    }

    /**
     * 잠긴 거래의 수정 — 카테고리·메모·가맹점과 일정·할 일 연결, 분할(카테고리 나누기)만 반영한다.
     *
     * <p>돈이 안 움직이므로 잔액 이력·카드 정산을 건드리지 않는다. 유형은 못 바꾸므로 카테고리는 지금
     * 유형의 것이어야 한다.
     */
    private ExpenseServiceDto.ExpenseInfo updateLockedExpense(Expense expense, Long userRowId,
                                                             ExpenseServiceDto.UpdateCommand command) {
        Long expenseId = expense.getRowId();
        boolean countedBefore = countsInLedgerNow(expense);
        long previousTotal = (countedBefore && expense.getExpenseType() == ExpenseType.EXPENSE
                && expense.getAmount() != null) ? expense.getAmount() : 0L;
        Map<Long, Long> previousByCat = countedBefore
                ? expenseSpendRollup(List.of(expense), loadSplitsByExpense(List.of(expense)))
                : Map.of();

        ExpenseCategory category = command.categoryRowId()
            .map(rowId -> findOwnedCategory(rowId, userRowId))
            .orKeep(expense.getCategory());
        if (category.getExpenseType() != expense.getExpenseType()) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_TYPE_CATEGORY_MISMATCH);
        }
        if (expenseCategoryRepository.hasChildren(category.getRowId())) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_NOT_LEAF);
        }
        expense.updateEditableFields(category,
            command.description().orKeep(expense.getDescription()),
            command.merchant().orKeep(expense.getMerchant()));
        expense.markFixed(command.isFixed().orKeep(expense.isFixedTx()));
        expense.setCalendarEvent(command.calendarEventRowId()
            .map(rowId -> calendarEventRepository.findById(rowId)
                .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.CALENDAR_EVENT_NOT_FOUND)))
            .orKeep(expense.getCalendarEvent()));
        expense.setTodo(command.todoRowId()
            .map(rowId -> todoRepository.findById(rowId)
                .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.TODO_NOT_FOUND)))
            .orKeep(expense.getTodo()));
        if (command.splits() != null) {
            expenseSplitService.replaceSplits(new ExpenseSplitServiceDto.ReplaceCommand(
                expenseId, userRowId, command.splits()));
            expense = findExpenseOrThrow(expenseId);
        }

        notifyBudgetThresholdIfCrossed(expense, previousTotal, previousByCat);
        log.info("결제 끝난 거래 부분 수정: expenseId={} (분류·메모·가맹점·연결·분할만)", expenseId);
        return ExpenseServiceDto.ExpenseInfo.from(expense).withLock(expense, true);
    }

    /**
     * 돈 칸이 달라졌는가(D12) — 금액·날짜·시간·자산·할부·유형·통화 3칸·결제수단.
     *
     * <p>안 실린 칸은 그대로다. 옛 클라이언트가 되돌려 보내는 값의 표기 차이는 같은 값으로 본다 —
     * 날짜는 분 단위(화면이 HH:mm 으로 보낸다), 날짜만 오면 날짜만, 할부 null ≡ 1, 통화는 저장할 때와
     * 같은 규칙(원화·빈 통화 = 없음)으로 맞춘 뒤 비교한다.
     */
    static boolean moneyChanged(Expense e, ExpenseServiceDto.UpdateCommand c) {
        if (c.amount().present() && !Objects.equals(c.amount().value(), e.getAmount())) {
            return true;
        }
        if (c.expenseType().present() && c.expenseType().value() != e.getExpenseType()) {
            return true;
        }
        Long assetNow = e.getAsset() != null ? e.getAsset().getRowId() : null;
        if (c.assetRowId().present() && !Objects.equals(c.assetRowId().value(), assetNow)) {
            return true;
        }
        if (c.installmentMonths().present()
                && installmentOrOne(c.installmentMonths().value()) != installmentOrOne(e.getInstallmentMonths())) {
            return true;
        }
        if (c.expenseDate().present() && dateChanged(e.getExpenseDate(), c.expenseDate().value(),
                c.expenseDateDateOnly())) {
            return true;
        }
        if (c.paymentMethod().present()
                && !Objects.equals(blankToNull(c.paymentMethod().value()), blankToNull(e.getPaymentMethod()))) {
            return true;
        }
        BigDecimal oa = c.originalAmount().orKeep(e.getOriginalAmount());
        String oc = c.originalCurrency().orKeep(e.getOriginalCurrency());
        BigDecimal er = c.exchangeRate().orKeep(e.getExchangeRate());
        boolean foreign = oc != null && !oc.isBlank() && !"KRW".equalsIgnoreCase(oc)
            && oa != null && oa.signum() > 0;
        return !sameDecimal(foreign ? oa : null, e.getOriginalAmount())
            || !Objects.equals(foreign ? oc.toUpperCase() : null, e.getOriginalCurrency())
            || !sameDecimal(foreign ? er : null, e.getExchangeRate());
    }

    private static int installmentOrOne(Integer months) {
        return months == null || months <= 1 ? 1 : months;
    }

    private static boolean dateChanged(LocalDateTime now, LocalDateTime sent, boolean dateOnly) {
        if (now == null || sent == null) {
            return !Objects.equals(now, sent);
        }
        if (dateOnly) {
            return !now.toLocalDate().equals(sent.toLocalDate());
        }
        return !now.truncatedTo(ChronoUnit.MINUTES).equals(sent.truncatedTo(ChronoUnit.MINUTES));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static boolean sameDecimal(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /**
     * 돈 칸 잠금 판정기(D12) — 신용카드 거래이고 결제일이 된 회차분이 하나라도 있거나 기록용 표식이
     * 있으면 잠긴다. 회차는 거래 달부터 순서대로 닫히므로 "첫 회차가 닫혔나" = "거래 날짜 ≤ 닫힌 회차의
     * 끝"이다. 카드마다 결제일 이력을 한 번만 읽는다.
     *
     * <p>시스템이 만든 거래는 따로 잠겨 있고(자동 생성 분기), 지운 카드의 거래는 회차 규칙 밖이다.
     */
    private Predicate<Expense> moneyLockJudge() {
        LocalDate today = serviceClock.today();
        Map<Long, Optional<LocalDate>> closedThrough = new HashMap<>();
        return e -> {
            Asset card = creditCardOf(e);
            if (card == null || card.getIsDeleted() == YNType.Y || e.isAutoGenerated()
                    || e.getExpenseDate() == null) {
                return false;
            }
            if (e.getCardSettledThrough() != null) {
                return true;
            }
            Optional<LocalDate> through = closedThrough.computeIfAbsent(card.getRowId(),
                id -> Optional.ofNullable(paymentScheduleService.scheduleOf(card))
                    .map(schedule -> schedule.closedThrough(today)));
            return through.isPresent() && !e.getExpenseDate().toLocalDate().isAfter(through.get());
        };
    }

    /**
     * 시스템이 만든 거래를 건드릴 때의 거절 사유 — 카드 이월 거래는 수정 거절과 같은 문구로 카드
     * 설정으로 안내한다(QA 26 2 — 환불·고쳐 쓰기·삭제가 "원래 거래(매도·이체)를 지우면 함께
     * 사라져요" 로 거절돼 이월 거래엔 맞지 않았다).
     */
    static DeskErrorCode autoGeneratedReadonly(Expense expense) {
        return expense.isCardCarryover()
            ? DeskErrorCode.EXPENSE_CARRYOVER_READONLY
            : DeskErrorCode.EXPENSE_AUTO_GENERATED_READONLY;
    }

    private ExpenseCategory findOwnedCategory(Long categoryRowId, Long userRowId) {
        ExpenseCategory found = expenseCategoryRepository.findById(categoryRowId)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.EXPENSE_CATEGORY_NOT_FOUND));
        validateCategoryOwnership(found, userRowId);
        return found;
    }

    /**
     * 고쳐 쓰기(D13) — 결제가 끝난 거래를 지우고 새로 적는다. 한 트랜잭션이다.
     *
     * <p>새 거래는 제 날짜의 회차 규칙을 그대로 따른다(D14) — 표식·상계는 옮기지 않고 생성이 다시
     * 정한다. 옛 거래가 붙들던 연결(분할·더치페이·반복 규칙의 원거래·일정·할 일)은 새 거래로 옮긴다.
     *
     * <p>순서가 뜻이 있다. 새 거래를 먼저 정산해야(닫힌 회차 몫은 기록용이 되어 청구에서 빠진다)
     * 옛 거래를 뺄 때 같은 회차의 정리된 청구를 옛 거래 몫으로 온전히 붙잡고, 열린 회차에 다시 들어간
     * 몫은 청구로 남아 선결제를 돌려줬다 다시 걷는 왕복이 생기지 않는다.
     */
    @Override
    @Transactional
    public ExpenseServiceDto.ExpenseInfo replaceExpense(Long expenseId, Long userRowId,
                                                        ExpenseServiceDto.CreateCommand command,
                                                        List<ExpenseSplitServiceDto.SplitCommand> splits) {
        log.debug("고쳐 쓰기 시작: expenseId={}", expenseId);
        validateAmount(command.amount());

        // 이미 지운 거래는 404 — 재시도가 새 거래를 한 번 더 만들지 않는다.
        Expense old = findExpenseOrThrow(expenseId);
        validateExpenseOwnership(old, userRowId);
        if (old.isRefunded()) {
            throw new InvalidValueException(DeskErrorCode.REFUNDED_READONLY);
        }
        if (old.isAutoGenerated()) {
            throw new InvalidValueException(autoGeneratedReadonly(old));
        }
        if (old.getInstallmentPayoffDate() != null) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_REPLACE_PAID_OFF);
        }
        if (!moneyLockJudge().test(old)) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_REPLACE_NOT_LOCKED);
        }

        // 예산 알림 기준은 옛 거래의 기여분 — 같은 금액으로 고쳐 써도 알림이 다시 뜨지 않게.
        boolean countedBefore = countsInLedgerNow(old);
        long previousTotal = (countedBefore && old.getExpenseType() == ExpenseType.EXPENSE
                && old.getAmount() != null) ? old.getAmount() : 0L;
        Map<Long, Long> previousByCat = countedBefore
                ? expenseSpendRollup(List.of(old), loadSplitsByExpense(List.of(old)))
                : Map.of();
        List<ExpenseSplit> oldSplits = expenseSplitRepository.findByExpense(expenseId);
        List<ExpenseSplitServiceDto.SplitCommand> newSplits = splits != null ? splits
            : oldSplits.stream()
                .map(sp -> new ExpenseSplitServiceDto.SplitCommand(null,
                    sp.getCategory() != null ? sp.getCategory().getRowId() : null,
                    sp.getAmount(), sp.getLabel(), sp.getSortOrder()))
                .toList();
        CardCycleMath.Side sideBefore = sideOf(old);
        String memo = cardRefundMemo(old, false);

        // ① 옛 거래를 지운다 — 정산은 새 거래를 적은 뒤에(위 순서 설명).
        old.deleteExpense();
        balanceHistoryService.removeExpense(expenseId);
        oldSplits.forEach(ExpenseSplit::deleteSplit);

        // ② 새 거래 — 일정·할 일을 안 보냈으면 옛 거래의 연결을 잇는다.
        ExpenseServiceDto.CreateCommand effective = new ExpenseServiceDto.CreateCommand(
            userRowId, command.categoryRowId(), command.assetRowId(), command.expenseType(),
            command.amount(), command.description(), command.expenseDate(), command.merchant(),
            command.paymentMethod(), command.installmentMonths(),
            command.originalAmount(), command.originalCurrency(), command.exchangeRate(),
            command.calendarEventRowId() != null ? command.calendarEventRowId()
                : (old.getCalendarEvent() != null ? old.getCalendarEvent().getRowId() : null),
            command.todoRowId() != null ? command.todoRowId()
                : (old.getTodo() != null ? old.getTodo().getRowId() : null),
            command.isFixed() != null ? command.isFixed() : old.isFixedTx());
        Created created = persistExpense(effective, false);
        Expense fresh = created.expense();

        // ③ 옛 거래를 뺀 정산 — 삭제와 같다(닫힌 회차 결제분은 붙잡고, 열린 회차 선결제는 돌려준다).
        CardPaymentServiceDto.SettlementResult removed = settleCard(old, sideBefore, CardCycleMath.Side.none(),
            memo, userRowId, CardPaymentServiceDto.SettlementMode.CHANGE);

        // ④ 연결을 새 거래로 — 더치페이·반복 규칙이 가리키던 원거래.
        relinkSources(old, fresh);

        // ⑤ 분할 — 본문에 있으면 그것, 없으면 옛 분할을 옮긴다(합이 새 금액과 다르면 400).
        Long freshId = fresh.getRowId();
        if (!newSplits.isEmpty()) {
            expenseSplitService.replaceSplits(new ExpenseSplitServiceDto.ReplaceCommand(
                freshId, userRowId, newSplits));
            // replaceSplits 가 영속성 컨텍스트를 비운다 — 응답은 재조회로.
            fresh = findExpenseOrThrow(freshId);
        }

        notifyBudgetThresholdIfCrossed(fresh, previousTotal, previousByCat);

        long refunded = (removed != null ? removed.refundedAmount() : 0L)
            + (created.settled() != null ? created.settled().refundedAmount() : 0L);
        log.info("고쳐 쓰기 완료: {} → {}, 환급={}", expenseId, freshId, refunded);
        return ExpenseServiceDto.ExpenseInfo.from(fresh, List.of(), refunded > 0L ? refunded : null)
            .withLock(fresh, moneyLockJudge().test(fresh));
    }

    /** 더치페이·반복 규칙이 가리키던 원거래를 새 거래로 옮긴다(고쳐 쓰기). */
    private void relinkSources(Expense from, Expense to) {
        entityManager.createQuery(
                "SELECT d FROM DutchPay d WHERE d.sourceExpense.rowId = :id", DutchPay.class)
            .setParameter("id", from.getRowId())
            .getResultList()
            .forEach(d -> d.relinkSourceExpense(to));
        entityManager.createQuery(
                "SELECT r FROM RecurringTransaction r WHERE r.sourceExpense.rowId = :id", RecurringTransaction.class)
            .setParameter("id", from.getRowId())
            .getResultList()
            .forEach(r -> r.relinkSourceExpense(to));
    }

    /**
     * 환불 마크 — <b>삭제 대신</b>이다(설계 결정 1, 2026-09-18).
     *
     * <p>돈과 집계는 삭제와 똑같이 다룬다: 원거래 잔액 흐름을 지워 금액이 그 자산으로
     * 돌아가고, {@link ExpenseAggregates#countable} 이 이 거래를 빼므로 모든 합계·청구·
     * 실적에서 사라진다. 삭제와 다른 점은 <b>내역에 남고 되돌릴 수 있다</b>는 것뿐이다.
     *
     * <p>종전 모델(수입 행 + 원거래 연결)에서는 환불이 카드에 {@code +금액} 흐름을 남기고
     * 동시에 <b>환불 날짜</b> 회차의 청구에서 또 빠져, 두 날짜가 다른 회차면 한 번 산 것을
     * 두 번 깎아 유령 빚이 남았다. 여기엔 "환불 날짜 회차" 라는 개념이 없다.
     */
    @Override
    @Transactional
    public ExpenseServiceDto.ExpenseInfo refund(Long expenseId, Long userRowId,
                                                LocalDateTime refundedAt) {
        log.debug("환불 마크 시작: expenseId={}", expenseId);

        Expense expense = findExpenseOrThrow(expenseId);
        validateExpenseOwnership(expense, userRowId);
        if (expense.getExpenseType() != ExpenseType.EXPENSE) {
            throw new InvalidValueException(DeskErrorCode.REFUND_NOT_EXPENSE);
        }
        if (expense.isAutoGenerated()) {
            throw new InvalidValueException(autoGeneratedReadonly(expense));
        }
        if (expense.isRefunded()) {
            throw new InvalidValueException(DeskErrorCode.ALREADY_REFUNDED);
        }

        LocalDateTime now = userClock.now(userRowId);
        LocalDateTime at = refundedAt != null ? refundedAt : now;
        // 환불일은 거래일부터 오늘까지(D16) — 거래 전 날짜나 미래 날짜의 환불은 없다.
        LocalDate refundDay = at.toLocalDate();
        if (refundDay.isBefore(expense.getExpenseDate().toLocalDate())
                || refundDay.isAfter(userClock.today(userRowId))) {
            throw new InvalidValueException(DeskErrorCode.REFUND_DATE_OUT_OF_RANGE);
        }
        CardCycleMath.Side sideBefore = sideOf(expense);

        balanceHistoryService.removeExpense(expenseId);
        expense.markRefunded(at);

        // 열린 회차면 청구에서 빠지고 선결제가 남으면 돌려준다(D3), 닫힌 회차면 통계에서만 빠진다(D1).
        CardPaymentServiceDto.SettlementResult settled = settleCard(expense, sideBefore,
            CardCycleMath.Side.none(), cardRefundMemo(expense, false), userRowId,
            CardPaymentServiceDto.SettlementMode.REFUND);
        if (settled != null && settled.refundTransferRowId() != null) {
            expense.linkRefundTransfer(settled.refundTransferRowId());
        }

        log.info("환불 마크 완료: expenseId={}, refundedAt={}, 환급이체={}",
            expenseId, expense.getRefundedAt(),
            settled != null ? settled.refundTransferRowId() : null);
        return ExpenseServiceDto.ExpenseInfo.from(expense, List.of(), refundedAmountOf(settled))
            .withLock(expense, moneyLockJudge().test(expense));
    }

    /** 환불 취소 — 표식을 지우고 흐름을 되살린다. 환급 이체가 있었으면 그것도 무른다. */
    @Override
    @Transactional
    public ExpenseServiceDto.ExpenseInfo cancelRefund(Long expenseId, Long userRowId) {
        log.debug("환불 취소 시작: expenseId={}", expenseId);

        Expense expense = findExpenseOrThrow(expenseId);
        validateExpenseOwnership(expense, userRowId);
        if (!expense.isRefunded()) {
            throw new InvalidValueException(DeskErrorCode.NOT_REFUNDED);
        }

        Long transferRowId = expense.getRefundTransferRowId();
        expense.clearRefund();
        // 환급 이체를 무른다 — 사용자 경로(deleteTransferByUser)는 CARD_REFUND 를 막으므로
        // 내부 경로를 쓴다. 되돌리기는 환불 취소로만 할 수 있어야 한다.
        if (transferRowId != null) {
            assetService.deleteTransfer(transferRowId, userRowId);
        }
        // 환불 때 붙잡은 몫도 무른다 — 표식·상계는 환불 전 그대로라 이것으로 환불 전 모습이 된다.
        balanceHistoryService.removeCardRefundHold(expenseId);
        // 원거래 흐름을 다시 적재한다 — 카드는 다시 빚이 되고 청구도 다시 늘어난다.
        // 돈 칸은 환불 동안 잠겨 있다 — 환불 때 걷은 증감을 그대로 되살린다(지금 환율로 다시 계산하지 않는다).
        balanceHistoryService.restoreExpense(expense.getAsset(), expense.getRowId(),
            expense.getExpenseType(), expense.getAmount(),
            expense.getOriginalAmount(), expense.getOriginalCurrency(), expense.getExpenseDate());
        // 환불 동안 결제일이 된 회차는 그 결제에 이 거래가 빠져 있었다 — 결제가 덮지 못한 만큼 기록용.
        settleCard(expense, CardCycleMath.Side.none(), sideOf(expense), null, userRowId,
            CardPaymentServiceDto.SettlementMode.CANCEL_REFUND);

        log.info("환불 취소 완료: expenseId={}, 되돌린 환급이체={}", expenseId, transferRowId);
        return ExpenseServiceDto.ExpenseInfo.from(expense, List.of())
            .withLock(expense, moneyLockJudge().test(expense));
    }

    /**
     * 지우거나 고치면 돈이 어떻게 움직이는지 미리 센다(설계 13-1, 닫힌 회차 R2·R3·R6).
     *
     * <p>실제 실행은 DB 가 바뀐 뒤에 세지만 확인창은 바뀌기 <b>전</b>에 물어야 한다. 그래서
     * 바뀐 뒤 모습을 저장하지 않은 채 만들어 실행과 같은 판정에 넘긴다 — 산식을 복사해 두면
     * 확인창 금액과 실제 이체액이 갈린다.
     */
    @Override
    @Transactional(readOnly = true)
    public ExpenseServiceDto.RefundPreviewInfo refundPreview(
            Long expenseId, Long userRowId, Long amountAfter, Long assetRowIdAfter,
            LocalDateTime dateAfter, Integer installmentMonthsAfter) {
        Expense expense = findExpenseOrThrow(expenseId);
        validateExpenseOwnership(expense, userRowId);

        if (expense.isRefunded()) {
            // 환급은 마크할 때 이미 끝났다 — 지워도 추가 이체가 없다(alreadyRefunded).
            return ExpenseServiceDto.RefundPreviewInfo.none(CardPaymentServiceDto.RefundPreview.ALREADY_REFUNDED);
        }
        boolean deletion = amountAfter == null && assetRowIdAfter == null && dateAfter == null
            && installmentMonthsAfter == null;
        CardCycleMath.Side before = sideOf(expense);
        CardCycleMath.Side after = deletion
            ? CardCycleMath.Side.none()
            : sideOf(draftOf(expense.getUser(), expense,
                assetRowIdAfter != null ? findOwnedAsset(assetRowIdAfter, userRowId) : expense.getAsset(),
                amountAfter != null ? amountAfter : expense.getAmount(),
                dateAfter != null ? dateAfter : expense.getExpenseDate(),
                installmentMonthsAfter != null ? installmentMonthsAfter : expense.getInstallmentMonths()));
        return previewCard(expense, before, after, userRowId);
    }

    /**
     * 새 카드 지출을 저장하면 어떻게 되는지 — 닫힌 회차면 기록만, 결제일 당일이면 추가 결제(R2·R3).
     *
     * <p>저장 확인창의 재료다. DB 를 바꾸지 않는다.
     */
    @Override
    @Transactional(readOnly = true)
    public ExpenseServiceDto.RefundPreviewInfo cardSavePreview(
            Long userRowId, Long assetRowId, Long amount, LocalDateTime expenseDate,
            Integer installmentMonths) {
        validateAmount(amount);
        User user = userRepository.findById(userRowId)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));
        Asset asset = findOwnedAsset(assetRowId, userRowId);
        CardCycleMath.Side after = sideOf(draftOf(user, null, asset, amount, expenseDate, installmentMonths));
        return previewCard(null, CardCycleMath.Side.none(), after, userRowId);
    }

    private ExpenseServiceDto.RefundPreviewInfo previewCard(Expense expense, CardCycleMath.Side before,
                                                            CardCycleMath.Side after, Long userRowId) {
        if (!before.isCard() && !after.isCard()) {
            return ExpenseServiceDto.RefundPreviewInfo.none(CardPaymentServiceDto.RefundPreview.NOT_CARD);
        }
        CardPaymentServiceDto.SettlementPreview p = cardPaymentService.previewExpenseChange(
            new CardPaymentServiceDto.SettlementCommand(expense, before, after, null, userRowId,
                CardPaymentServiceDto.SettlementMode.CHANGE));
        // 옛 앱만 부른다(D4) — 당일 추가 결제는 없어졌고(U7), 기한 문구(REFUND_WINDOW_CLOSED)는 쓰지 않는다.
        String reason;
        if (p.refundAmount() > 0L) {
            reason = CardPaymentServiceDto.RefundPreview.OK;
        } else if (p.noPaymentAsset()) {
            reason = CardPaymentServiceDto.RefundPreview.NO_PAYMENT_ASSET;
        } else {
            reason = CardPaymentServiceDto.RefundPreview.NOT_PAID_CYCLE;
        }
        return new ExpenseServiceDto.RefundPreviewInfo(p.refundAmount() > 0L, p.refundAmount(), reason,
            p.newRecordAmount(), 0L);
    }

    /**
     * 저장하지 않은 거래 — 미리보기가 "바뀐 뒤 모습" 의 회차분을 셀 때 쓴다.
     *
     * <p>회차 산식(할부 나머지·중도 상환)은 엔티티 한 곳에만 있다. 값을 따로 들고 다시 세면
     * 확인창과 실제 청구가 갈리므로, 같은 엔티티를 영속화하지 않고 만든다.
     */
    private static Expense draftOf(User user, Expense source, Asset asset, Long amount,
                                   LocalDateTime expenseDate, Integer installmentMonths) {
        Expense draft = Expense.createExpense(user, source != null ? source.getCategory() : null, asset,
            source != null ? source.getExpenseType() : ExpenseType.EXPENSE, amount, null, expenseDate,
            null, null, installmentMonths, null, null, null);
        if (source != null) {
            // 개월을 줄이거나 날짜를 옮겨 상환 회차가 할부 밖으로 나가면 상환이 없는 것과 같다 —
            // 엔티티가 예외를 던지므로(24차 10⑥, 미리보기 500) 여기서 거른다.
            LocalDate payoff = source.getInstallmentPayoffDate();
            if (payoff != null && draft.isInstallment() && expenseDate != null
                    && Expense.installmentSequenceAt(payoff, expenseDate.toLocalDate())
                        <= draft.getInstallmentMonths()) {
                draft.payoffInstallment(payoff);
            }
            draft.markCardSettledThrough(source.getCardSettledThrough());
        }
        return draft;
    }

    private Asset findOwnedAsset(Long assetRowId, Long userRowId) {
        Asset asset = assetRepository.findById(assetRowId)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.ASSET_NOT_FOUND));
        validateAssetOwnership(asset, userRowId);
        return asset;
    }

    /** 이 거래의 자산이 신용카드면 그 자산, 아니면 null. */
    private Asset creditCardOf(Expense expense) {
        Asset asset = expense.getAsset();
        return asset != null && asset.getAssetType() == AssetType.CREDIT_CARD ? asset : null;
    }

    /**
     * 이 거래가 카드 회차 규칙을 따르는 모습 — 신용카드에 사용자가 쓴 살아 있는 거래일 때만.
     *
     * <p>시스템이 만든 거래(카드 이월 등)는 원 거래가 정하므로 규칙 밖이다. 지운 카드도 밖이다 — 정리할
     * 카드가 없는데 정산하려다 404 로 거래 삭제·환불까지 막혔다(23차 11). 결제일 없는 옛 카드는 회차가
     * 닫히지 않는 카드로 들어온다(D8) — 늘 열린 회차라 선결제 환급은 그대로 된다.
     */
    private CardCycleMath.Side sideOf(Expense e) {
        Asset card = creditCardOf(e);
        if (card == null || card.getIsDeleted() == YNType.Y || e.isAutoGenerated() || !e.isCountable()) {
            return CardCycleMath.Side.none();
        }
        PaymentSchedule schedule = paymentScheduleService.scheduleOf(card);
        if (schedule == null) {
            return CardCycleMath.Side.none();
        }
        return new CardCycleMath.Side(card.getRowId(), schedule,
            CardCycleMath.duesByCycle(e), e.getCardSettledThrough());
    }

    /** 카드 회차 규칙으로 정산 — 앞뒤 모두 카드가 아니면 할 일이 없다. */
    private CardPaymentServiceDto.SettlementResult settleCard(
            Expense expense, CardCycleMath.Side before, CardCycleMath.Side after, String memo,
            Long userRowId, CardPaymentServiceDto.SettlementMode mode) {
        if (!before.isCard() && !after.isCard()) {
            return null;
        }
        return cardPaymentService.settleExpenseChange(new CardPaymentServiceDto.SettlementCommand(
            expense, before, after, memo, userRowId, mode));
    }

    /** 이번 저장이 만든 환급액 — 없으면 null(화면이 토스트를 안 띄운다). */
    private static Long refundedAmountOf(CardPaymentServiceDto.SettlementResult settled) {
        return settled != null && settled.refundedAmount() > 0L ? settled.refundedAmount() : null;
    }

    /**
     * 삭제된 거래는 가리킬 수 없으므로 이체 메모에 무엇의 환급인지 남긴다.
     *
     * <p>{@code reduced} 면 "· 감액분" 을 붙인다 — 이체 목록에서 "이 거래가 통째로
     * 빠졌나, 금액만 줄었나" 를 읽을 수 있는 유일한 자리다(설계 13-3).
     */
    private String cardRefundMemo(Expense expense, boolean reduced) {
        String what = expense.getMerchant() != null && !expense.getMerchant().isBlank()
            ? expense.getMerchant()
            : (expense.getDescription() != null ? expense.getDescription() : "카드 거래");
        return "카드사 환급 · " + what + " "
            + (expense.getExpenseDate() != null ? expense.getExpenseDate().toLocalDate() : "")
            + (reduced ? " · 감액분" : "");
    }

    @Override
    @Transactional
    public Long deleteExpense(Long expenseId, Long userRowId) {
        log.debug("지출 삭제 시작: expenseId={}", expenseId);

        Expense expense = findDeletableOrThrow(expenseId, userRowId);
        Long refundedAmount = removeExpense(expenseId, expense, userRowId);

        log.info("지출 삭제 완료: expenseId={}, 환급={}", expenseId, refundedAmount);
        return refundedAmount;
    }

    @Override
    @Transactional
    public ExpenseServiceDto.BulkDeleteResult bulkDeleteExpenses(ExpenseServiceDto.BulkDeleteCommand command) {
        List<Long> ids = command.expenseRowIds().stream().distinct().toList();
        log.debug("지출 일괄 삭제 시작: userRowId={}, count={}", command.userRowId(), ids.size());

        // 먼저 전부 읽고 검사한 뒤 지운다 — 중간에 남의 거래·시스템 거래를 만나 일부만 지워지는 상태를 막는다.
        List<Expense> targets = new java.util.ArrayList<>(ids.size());
        for (Long id : ids) {
            targets.add(findDeletableOrThrow(id, command.userRowId()));
        }
        // 건마다 try/catch 로 삼키지 않는다 — 삼키면 트랜잭션이 rollback-only 로 오염된 채 커밋을 시도한다.
        long refundedTotal = 0L;
        for (int i = 0; i < ids.size(); i++) {
            Long refunded = removeExpense(ids.get(i), targets.get(i), command.userRowId());
            if (refunded != null) {
                refundedTotal += refunded;
            }
        }
        Long refundedAmount = refundedTotal > 0L ? refundedTotal : null;

        log.info("지출 일괄 삭제 완료: userRowId={}, 지운 수={}, 환급 합={}", command.userRowId(), ids.size(), refundedAmount);
        return new ExpenseServiceDto.BulkDeleteResult(ids.size(), refundedAmount);
    }

    /** 지울 수 있는 내 거래를 읽는다 — 없음 404 · 남의 것 403 · 시스템이 만든 거래 400. */
    private Expense findDeletableOrThrow(Long expenseId, Long userRowId) {
        Expense expense = findExpenseOrThrow(expenseId);
        validateExpenseOwnership(expense, userRowId);
        // 시스템이 만든 거래는 원 거래를 지워야 사라진다 — 여기서 지우면 매도·이체는
        // 그대로인데 손익·이자 기록만 없어져 앞뒤가 안 맞는다.
        // 단, 매도 실현손익(TRADE_REALIZED)은 그 증권 자산이 이미 지워졌으면 지울 수 있다 — 자산 삭제는
        // 이 기록을 남겨 두므로(지난 달 합계 유지) 치울 길은 사용자에게 열어 둔다. 수정·환불은 그대로 잠겨 있다.
        if (!expense.isDeletableByUser()) {
            throw new InvalidValueException(autoGeneratedReadonly(expense));
        }
        return expense;
    }

    /** 거래 하나를 지우는 본문 — 단건·일괄이 함께 쓴다. 돌려준 환급액(없으면 null). */
    private Long removeExpense(Long expenseId, Expense expense, Long userRowId) {
        CardCycleMath.Side sideBefore = sideOf(expense);
        String memo = cardRefundMemo(expense, false);

        expense.deleteExpense();
        // 자산 잔액 이력: 해당 거래 flow soft-delete
        balanceHistoryService.removeExpense(expenseId);
        // 분할도 같이 지운다 — 남기면 거래 없는 분할이 고아로 남는다(인계 25 2-1 ⑥).
        expenseSplitRepository.findByExpense(expenseId).forEach(ExpenseSplit::deleteSplit);

        // 닫힌 회차 결제분은 결제가 덮은 만큼 상계로 붙잡아 카드·통장이 그대로고(D1), 열린 회차면
        // 선결제가 남을 때만 결제계좌로 돌려준다(D3).
        return refundedAmountOf(settleCard(expense, sideBefore, CardCycleMath.Side.none(),
            memo, userRowId, CardPaymentServiceDto.SettlementMode.CHANGE));
    }

    /**
     * 집계에 넣을 거래만 남긴다 — <b>아직 오지 않은 건 뺀다.</b>
     *
     * <p>반복거래는 미래분을 미리 만들어 둔다. 그걸 그대로 더하면 8월 수입에 아직 안 받은
     * 급여 400만원이 잡혀, 통장에는 없는 돈이 이번 달 수입으로 보인다. 잔액은 현재 시각
     * 기준이라 애초에 미래분이 안 들어가는데, 집계만 들어가서 둘이 어긋났다.
     *
     * <p>목록·캘린더에서는 여전히 보인다 — 거기서는 "예정" 으로 표시한다.
     */
    private List<Expense> aggregatable(List<Expense> all, Long userRowId) {
        return ExpenseAggregates.ledgerCountable(all, userClock.now(userRowId));
    }

    @Override
    @Transactional
    public int bulkMarkFixed(ExpenseServiceDto.BulkFixedCommand command) {
        List<Long> ids = command.expenseRowIds().stream().distinct().toList();
        log.debug("고정 표식 일괄 변경: userRowId={}, count={}, fixed={}", command.userRowId(), ids.size(), command.fixed());

        // 먼저 전부 읽고 검사한 뒤 바꾼다 — 중간에 남의 거래를 만나 일부만 바뀌는 상태를 막는다.
        List<Expense> targets = new java.util.ArrayList<>(ids.size());
        for (Long id : ids) {
            Expense expense = findExpenseOrThrow(id);
            validateExpenseOwnership(expense, command.userRowId());
            targets.add(expense);
        }
        int changed = 0;
        for (Expense expense : targets) {
            if (expense.isFixedTx() != command.fixed()) {
                expense.markFixed(command.fixed());
                changed++;
            }
        }
        return changed;
    }

    @Override
    public ExpenseServiceDto.DailySummary getDailySummary(Long userRowId, LocalDate date) {
        log.debug("지출 일별 요약 조회: userRowId={}, date={}", userRowId, date);

        List<Expense> expenses = aggregatable(expenseRepository.findDailySummary(userRowId, date), userRowId);

        // 환불(INCOME + 원거래 지정)은 수입이 아니라 지출 상계로 잡는다 — Expense 가 부호를 결정한다.
        Long totalIncome = expenses.stream().mapToLong(Expense::incomeContribution).sum();
        Long totalExpense = expenses.stream().mapToLong(Expense::expenseContribution).sum();

        return new ExpenseServiceDto.DailySummary(date, totalIncome, totalExpense);
    }

    @Override
    public ExpenseServiceDto.RangeSummary getRangeSummary(Long userRowId, LocalDate startDate, LocalDate endDate) {
        return getRangeSummary(userRowId, startDate, endDate, null);
    }

    @Override
    public ExpenseServiceDto.RangeSummary getRangeSummary(Long userRowId, LocalDate startDate, LocalDate endDate,
                                                          Long assetRowId) {
        log.debug("지출 기간 요약 조회: userRowId={}, startDate={}, endDate={}, assetRowId={}",
            userRowId, startDate, endDate, assetRowId);
        validateDateRange(startDate, endDate);

        List<Expense> expenses = aggregatable(
            expenseRepository.findByDateRange(userRowId, startDate, endDate, assetRowId), userRowId);

        // 환불(INCOME + 원거래 지정)은 수입이 아니라 지출 상계로 잡는다 — Expense 가 부호를 결정한다.
        Long totalIncome = expenses.stream().mapToLong(Expense::incomeContribution).sum();
        Long totalExpense = expenses.stream().mapToLong(Expense::expenseContribution).sum();
        Long fixedIncome = ExpenseAggregates.fixedIncomeSum(expenses);
        Long fixedExpense = ExpenseAggregates.fixedExpenseSum(expenses);

        // 분할(split)을 1회만 조회해 전체·월별 카테고리 집계에 공유(중복 쿼리 회피).
        Map<Long, List<ExpenseSplit>> splitsByExpense = loadSplits(expenses);
        List<ExpenseServiceDto.CategoryBreakdown> categoryBreakdown = buildCategoryBreakdown(expenses, splitsByExpense);

        // 추이 차트용 월별 버킷 — startDate~endDate 안의 모든 (year, month) 슬롯을 보장 (0 인 달도 포함)
        Map<String, List<Expense>> grouped = expenses.stream()
            .collect(Collectors.groupingBy(e -> e.getExpenseDate().getYear() + "-" + e.getExpenseDate().getMonthValue()));

        List<ExpenseServiceDto.RangeMonthlyBucket> monthlyBuckets = new java.util.ArrayList<>();
        LocalDate cursor = startDate.withDayOfMonth(1);
        LocalDate endMonth = endDate.withDayOfMonth(1);
        while (!cursor.isAfter(endMonth)) {
            int y = cursor.getYear();
            int m = cursor.getMonthValue();
            List<Expense> bucket = grouped.getOrDefault(y + "-" + m, List.of());
            long income = bucket.stream().mapToLong(Expense::incomeContribution).sum();
            long expense = bucket.stream().mapToLong(Expense::expenseContribution).sum();
            monthlyBuckets.add(new ExpenseServiceDto.RangeMonthlyBucket(
                y, m, income, expense,
                ExpenseAggregates.fixedIncomeSum(bucket), ExpenseAggregates.fixedExpenseSum(bucket),
                expenseCategoryAmounts(bucket, splitsByExpense)));
            cursor = cursor.plusMonths(1);
        }

        return new ExpenseServiceDto.RangeSummary(
            startDate, endDate, totalIncome, totalExpense, fixedIncome, fixedExpense,
            categoryBreakdown, monthlyBuckets);
    }

    /**
     * 거래 목록을 카테고리 단위 합계로 집계.
     * 분할(ExpenseSplit) 항목이 있는 거래는 부모 카테고리 대신 분할 카테고리별로 집계.
     * 분할 합계는 부모 amount 와 일치하므로 totalIncome/totalExpense 는 영향 없음.
     */
    private List<ExpenseServiceDto.CategoryBreakdown> buildCategoryBreakdown(
            List<Expense> expenses, Map<Long, List<ExpenseSplit>> splitsByExpense) {
        if (expenses.isEmpty()) return List.of();


        Map<Long, ExpenseServiceDto.CategoryBreakdown> agg = new HashMap<>();
        for (Expense e : expenses) {
            ExpenseType breakdownType = e.getExpenseType();
            List<ExpenseSplit> es = splitsByExpense.get(e.getRowId());
            if (es != null && !es.isEmpty()) {
                for (ExpenseSplit s : es) {
                    accumulateBreakdown(agg, s.getCategory(), breakdownType, s.getAmount(), e.isFixedTx());
                }
            } else {
                accumulateBreakdown(agg, e.getCategory(), breakdownType, e.getAmount(), e.isFixedTx());
            }
        }
        return List.copyOf(agg.values());
    }

    /**
     * 카테고리별 누적. 카테고리가 없는 거래(실현손익·대출이자처럼 자동 생성되는 것들)는
     * <b>미분류</b> 버킷으로 모은다 — 버리면 총액은 맞는데 카테고리를 다 더해도 총액에
     * 못 미쳐, 사용자가 사라진 돈을 찾게 된다.
     */
    private void accumulateBreakdown(Map<Long, ExpenseServiceDto.CategoryBreakdown> agg,
                                      ExpenseCategory category, ExpenseType type, Long amount, boolean fixed) {
        // 고정 표식은 거래에 있고 분할은 그 거래를 쪼갠 것이라 분할도 거래의 표식을 따른다.
        long fixedPart = fixed ? amount : 0L;
        if (category == null) {
            // 유형(수입/지출)까지 섞으면 부호가 뒤엉킨다 — 유형별로 따로 모은다.
            agg.merge(uncategorizedKey(type),
                new ExpenseServiceDto.CategoryBreakdown(null, null, null, null, type, amount, fixedPart),
                (a, b) -> new ExpenseServiceDto.CategoryBreakdown(
                    null, null, null, null, type, a.totalAmount() + b.totalAmount(),
                    a.fixedAmount() + b.fixedAmount()));
            return;
        }
        Long key = category.getRowId();
        ExpenseServiceDto.CategoryBreakdown existing = agg.get(key);
        if (existing == null) {
            ExpenseCategory parent = category.getParent();
            agg.put(key, new ExpenseServiceDto.CategoryBreakdown(
                category.getRowId(),
                category.getCategoryName(),
                parent != null ? parent.getRowId() : null,
                parent != null ? parent.getCategoryName() : null,
                type,
                amount,
                fixedPart
            ));
        } else {
            agg.put(key, new ExpenseServiceDto.CategoryBreakdown(
                existing.categoryRowId(),
                existing.categoryName(),
                existing.parentCategoryRowId(),
                existing.parentCategoryName(),
                existing.expenseType(),
                existing.totalAmount() + amount,
                existing.fixedAmount() + fixedPart
            ));
        }
    }

    /** 거래들의 활성 분할을 expenseId → splits 맵으로 1회 로딩(없으면 빈 맵). */
    private Map<Long, List<ExpenseSplit>> loadSplits(List<Expense> expenses) {
        if (expenses.isEmpty()) return Map.of();
        List<Long> expenseIds = expenses.stream().map(Expense::getRowId).toList();
        return expenseSplitRepository.findByExpenseIds(expenseIds).stream()
            .collect(Collectors.groupingBy(s -> s.getExpense().getRowId()));
    }

    /**
     * 한 달치 거래를 EXPENSE 만 카테고리(leaf/split)별로 합산.
     * split 거래는 분할 카테고리로 분해, 아니면 거래 카테고리. 수입·미분류는 제외.
     */
    private List<ExpenseServiceDto.CategoryAmount> expenseCategoryAmounts(
            List<Expense> monthExpenses, Map<Long, List<ExpenseSplit>> splitsByExpense) {
        // 값 = {전체, 그 중 고정}
        Map<Long, long[]> agg = new HashMap<>();
        for (Expense e : monthExpenses) {
            if (e.getExpenseType() != ExpenseType.EXPENSE) continue;
            List<ExpenseSplit> es = splitsByExpense.get(e.getRowId());
            if (es != null && !es.isEmpty()) {
                for (ExpenseSplit s : es) {
                    // 카테고리 없는 분할도 미분류(null 키)로 남긴다 — 합이 그 달 지출과 맞아야 한다.
                    addCategoryAmount(agg, categoryKey(s.getCategory()), s.getAmount(), e.isFixedTx());
                }
            } else {
                addCategoryAmount(agg, categoryKey(e.getCategory()), e.getAmount(), e.isFixedTx());
            }
        }
        return agg.entrySet().stream()
            // 미분류는 categoryRowId 가 null 로 나간다 — 클라이언트가 '미분류' 로 표시한다.
            .map(en -> new ExpenseServiceDto.CategoryAmount(
                en.getKey() == UNCATEGORIZED ? null : en.getKey(), en.getValue()[0], en.getValue()[1]))
            .toList();
    }

    private static void addCategoryAmount(Map<Long, long[]> agg, Long key, Long amount, boolean fixed) {
        long[] v = agg.computeIfAbsent(key, k -> new long[2]);
        v[0] += amount;
        if (fixed) v[1] += amount;
    }

    @Override
    public List<ExpenseServiceDto.MonthlyTrend> getMonthlyTrend(Long userRowId, Integer months) {
        if (months != null && months < 0) {
            throw new InvalidValueException(DeskErrorCode.INVALID_INPUT);
        }
        int n = (months == null || months < 1) ? 6 : Math.min(months, 24);
        log.debug("지출 월별 트렌드 조회: userRowId={}, months={}", userRowId, n);

        LocalDate now = userClock.today(userRowId);
        LocalDate from = now.minusMonths(n - 1L).withDayOfMonth(1);
        LocalDate to = now.withDayOfMonth(1).plusMonths(1).minusDays(1);

        // 달마다 쿼리를 날리면 24개월에 24번이다 — 한 번 받아 자바에서 월별로 접는다.
        Map<String, List<Expense>> byMonth = aggregatable(
                expenseRepository.findByDateRange(userRowId, from, to, null), userRowId).stream()
            .collect(Collectors.groupingBy(
                e -> e.getExpenseDate().getYear() + "-" + e.getExpenseDate().getMonthValue()));

        List<ExpenseServiceDto.MonthlyTrend> trends = new java.util.ArrayList<>(n);
        for (int i = n - 1; i >= 0; i--) {
            LocalDate m = now.minusMonths(i);
            int y = m.getYear();
            int mm = m.getMonthValue();
            List<Expense> bucket = byMonth.getOrDefault(y + "-" + mm, List.of());
            // 환불은 수입이 아니라 지출 상계다 — range 요약과 같은 규칙을 써야 두 화면이 맞는다.
            // 예전엔 getAmount() 를 그냥 더해 같은 달인데 3,000원씩 어긋났다.
            long income = bucket.stream().mapToLong(Expense::incomeContribution).sum();
            long expense = bucket.stream().mapToLong(Expense::expenseContribution).sum();
            trends.add(new ExpenseServiceDto.MonthlyTrend(y, mm, income, expense,
                ExpenseAggregates.fixedIncomeSum(bucket), ExpenseAggregates.fixedExpenseSum(bucket)));
        }
        return trends;
    }

    @Override
    public List<ExpenseServiceDto.MerchantSummary> getMerchantSummary(Long userRowId, LocalDate startDate, LocalDate endDate) {
        log.debug("거래처별 요약 조회: userRowId={}", userRowId);
        validateDateRange(startDate, endDate);

        List<Expense> expenses = aggregatable(
            expenseRepository.findByUser(userRowId, null, null, startDate, endDate), userRowId);

        // 환불된 거래는 `countable` 에서 이미 빠졌다 — 여기서 따로 셀 것이 없다.
        return expenses.stream()
            .filter(e -> e.getExpenseType() == ExpenseType.EXPENSE)
            .filter(e -> e.getMerchant() != null && !e.getMerchant().isBlank())
            .collect(Collectors.groupingBy(Expense::getMerchant))
            .entrySet().stream()
            .map(entry -> new ExpenseServiceDto.MerchantSummary(
                entry.getKey(),
                entry.getValue().stream().mapToLong(Expense::expenseContribution).sum(),
                entry.getValue().size()
            ))
            .filter(m -> m.totalAmount() != 0L)  // 전액 환불된 가맹점은 목록에서 뺀다
            .sorted((a, b) -> Long.compare(b.totalAmount(), a.totalAmount()))
            .toList();
    }

    @Override
    public List<ExpenseServiceDto.AssetSummary> getAssetSummary(Long userRowId, LocalDate startDate, LocalDate endDate) {
        log.debug("자산별 요약 조회: userRowId={}", userRowId);
        validateDateRange(startDate, endDate);

        List<Expense> expenses = aggregatable(
            expenseRepository.findByUser(userRowId, null, null, startDate, endDate), userRowId);

        // 거래처별 요약과 같은 규칙 — 환불된 거래는 이미 빠져 있다.
        return expenses.stream()
            .filter(e -> e.getExpenseType() == ExpenseType.EXPENSE)
            .filter(e -> e.getAsset() != null)
            .collect(Collectors.groupingBy(e -> e.getAsset().getRowId()))
            .entrySet().stream()
            .map(entry -> {
                List<Expense> assetExpenses = entry.getValue();
                Expense first = assetExpenses.get(0);
                return new ExpenseServiceDto.AssetSummary(
                    first.getAsset().getRowId(),
                    first.getAsset().getAssetName(),
                    assetExpenses.stream().mapToLong(Expense::expenseContribution).sum(),
                    assetExpenses.size()
                );
            })
            .sorted((a, b) -> Long.compare(b.totalAmount(), a.totalAmount()))
            .toList();
    }

    @Override
    public List<ExpenseServiceDto.ExpenseInfo> searchExpenses(ExpenseServiceDto.SearchCommand command) {
        log.debug("지출 검색: userRowId={}, keyword={}", command.userRowId(), command.keyword());

        List<Expense> expenses = expenseRepository.search(
            command.userRowId(), command.categoryRowId(), command.assetRowId(),
            command.expenseType(), command.keyword(), command.merchant(),
            command.minAmount(), command.maxAmount(), command.startDate(), command.endDate()
        );

        // 고정/변동 조건 — 표식은 한 칸이라 쿼리를 늘리지 않고 결과에서 거른다(검색 결과는 어차피 메모리에 있다).
        if (command.fixed() != null) {
            expenses = expenses.stream().filter(e -> e.isFixedTx() == command.fixed()).toList();
        }

        // 분할 카테고리 id 를 bulk 로 적재(N+1 회피) — 검색 결과의 카테고리 필터도 split-aware 하게 (getExpenses 와 대칭).
        Map<Long, List<Long>> splitCatsByExpense = loadSplitCategoryIdsByExpense(expenses);
        Predicate<Expense> locked = moneyLockJudge();
        return expenses.stream()
            .map(e -> ExpenseServiceDto.ExpenseInfo.from(
                e, splitCatsByExpense.getOrDefault(e.getRowId(), List.of())).withLock(e, locked.test(e)))
            .toList();
    }

    @Override
    public List<ExpenseServiceDto.ExpenseInfo> getExpensesByCalendarEvent(Long calendarEventRowId) {
        log.debug("일정 연결 지출 조회: calendarEventRowId={}", calendarEventRowId);

        Predicate<Expense> locked = moneyLockJudge();
        return expenseRepository.findByCalendarEvent(calendarEventRowId).stream()
            .map(e -> ExpenseServiceDto.ExpenseInfo.from(e).withLock(e, locked.test(e)))
            .toList();
    }

    @Override
    public List<ExpenseServiceDto.ExpenseInfo> getExpensesByTodo(Long todoRowId) {
        log.debug("할일 연결 지출 조회: todoRowId={}", todoRowId);

        Predicate<Expense> locked = moneyLockJudge();
        return expenseRepository.findByTodo(todoRowId).stream()
            .map(e -> ExpenseServiceDto.ExpenseInfo.from(e).withLock(e, locked.test(e)))
            .toList();
    }

    @Override
    public List<ExpenseServiceDto.HeatmapCell> getHeatmap(Long userRowId, LocalDate startDate, LocalDate endDate) {
        log.debug("지출 히트맵 조회: userRowId={}, startDate={}, endDate={}", userRowId, startDate, endDate);
        validateDateRange(startDate, endDate);

        // 지출(EXPENSE)만 히트맵 집계 대상. 합계 그대로 반환 — 평균 정규화는 클라이언트가 기간 길이로.
        List<Object[]> rows = expenseRepository.sumGroupedByDayOfWeekAndHour(
            userRowId, ExpenseType.EXPENSE, startDate, endDate
        );

        // MySQL/MariaDB DAYOFWEEK(1=일 ~ 7=토) → Java DayOfWeek(1=월 ~ 7=일) 변환
        //   sun(1) → 7, mon(2) → 1, tue(3) → 2, ..., sat(7) → 6
        //   공식: javaDow = ((mysqlDow + 5) % 7) + 1
        return rows.stream()
            .map(row -> {
                int mysqlDow = ((Number) row[0]).intValue();
                int hour = ((Number) row[1]).intValue();
                long amount = ((Number) row[2]).longValue();
                int javaDow = ((mysqlDow + 5) % 7) + 1;
                return new ExpenseServiceDto.HeatmapCell(javaDow, hour, amount);
            })
            .toList();
    }

    private void validateExpenseOwnership(Expense expense, Long userRowId) {
        if (!expense.getUser().getRowId().equals(userRowId)) {
            log.warn("지출 소유권 검증 실패 - expenseId={}, ownerRowId={}, requestUserRowId={}",
                expense.getRowId(), expense.getUser().getRowId(), userRowId);
            throw new ForbiddenException(DeskErrorCode.EXPENSE_ACCESS_DENIED);
        }
    }

    private void validateCategoryOwnership(ExpenseCategory category, Long userRowId) {
        if (!category.getUser().getRowId().equals(userRowId)) {
            log.warn("지출 카테고리 소유권 검증 실패 - categoryId={}, ownerRowId={}, requestUserRowId={}",
                category.getRowId(), category.getUser().getRowId(), userRowId);
            throw new ForbiddenException(DeskErrorCode.EXPENSE_ACCESS_DENIED);
        }
    }

    private void validateAssetOwnership(Asset asset, Long userRowId) {
        if (!asset.getUser().getRowId().equals(userRowId)) {
            log.warn("자산 소유권 검증 실패 - assetId={}, ownerRowId={}, requestUserRowId={}",
                asset.getRowId(), asset.getUser().getRowId(), userRowId);
            throw new ForbiddenException(DeskErrorCode.EXPENSE_ACCESS_DENIED);
        }
    }

    private Expense findExpenseOrThrow(Long expenseId) {
        return expenseRepository.findById(expenseId)
            .orElseThrow(() -> {
                log.warn("지출 조회 실패 - 존재하지 않는 지출: expenseId={}", expenseId);
                return new EntityNotFoundException(DeskErrorCode.EXPENSE_NOT_FOUND);
            });
    }

    /**
     * 해당 월의 EXPENSE 지출을 카테고리별로 집계해 반환(split-aware).
     * 분할이 있는 거래는 분할 항목 카테고리로, 없으면 거래 카테고리로 귀속하며,
     * 각 leaf 금액을 leaf 키와 부모 키 모두에 누적(롤업)한다.
     */
    @Override
    public Map<Long, Long> getMonthlyExpenseSpendByCategory(Long userRowId, int year, int month) {
        LocalDate ms = LocalDate.of(year, month, 1);
        LocalDate me = ms.plusMonths(1).minusDays(1);
        // 예산 알림도 가계부 숫자다 — 예정·환불·카드 이월을 뺀다. 종전엔 날것 합이라 환불한
        // 거래와 아직 안 온 반복거래까지 예산을 깎아, 화면 이행률과 알림 문구가 달랐다.
        List<Expense> monthly = aggregatable(
            expenseRepository.findByDateRange(userRowId, ms, me), userRowId);
        return expenseSpendRollup(monthly, loadSplitsByExpense(monthly));
    }

    @Override
    public long getMonthlyExpenseTotal(Long userRowId, int year, int month) {
        LocalDate ms = LocalDate.of(year, month, 1);
        LocalDate me = ms.plusMonths(1).minusDays(1);
        return aggregatable(expenseRepository.findByDateRange(userRowId, ms, me), userRowId).stream()
            .mapToLong(Expense::expenseContribution)
            .sum();
    }

    /**
     * 이 거래가 **지금** 가계부 합계에 들어가는가 — 예산 알림의 delta 를 세는 기준.
     *
     * <p>월 합계를 {@link #aggregatable} 로 세면서 이 거래의 기여분만 날것으로 세면, 합계에
     * 없는 금액을 빼게 되어 "돌파 전" 값이 틀린다(예: 미래 날짜로 적은 지출).
     */
    private boolean countsInLedgerNow(Expense expense) {
        return !ExpenseAggregates.ledgerCountable(
            List.of(expense), userClock.now(expense.getUser().getRowId())).isEmpty();
    }

    /** 거래 id 목록의 활성 분할을 거래별로 묶어 반환. */
    private Map<Long, List<ExpenseSplit>> loadSplitsByExpense(List<Expense> expenses) {
        List<Long> ids = expenses.stream().map(Expense::getRowId).toList();
        if (ids.isEmpty()) return Map.of();
        return expenseSplitRepository.findByExpenseIds(ids).stream()
            .collect(Collectors.groupingBy(s -> s.getExpense().getRowId()));
    }

    /**
     * EXPENSE 거래를 split-aware 하게 카테고리별 합계로 집계(leaf + 부모 롤업).
     * 분할이 있으면 분할 항목 카테고리로, 없으면 거래 카테고리로 귀속.
     */
    private Map<Long, Long> expenseSpendRollup(List<Expense> expenses, Map<Long, List<ExpenseSplit>> splitsByExpense) {
        Map<Long, Long> spent = new HashMap<>();
        for (Expense e : expenses) {
            if (e.getExpenseType() != ExpenseType.EXPENSE || e.getAmount() == null) continue;
            List<ExpenseSplit> es = splitsByExpense.get(e.getRowId());
            if (es != null && !es.isEmpty()) {
                for (ExpenseSplit s : es) addSpendRollup(spent, s.getCategory(), s.getAmount());
            } else {
                addSpendRollup(spent, e.getCategory(), e.getAmount());
            }
        }
        return spent;
    }

    /** 금액을 카테고리 leaf 키와 (있으면) 부모 키 양쪽에 누적. */
    /** 미분류 자리표시 키 — 실제 카테고리 rowId 와 겹치지 않게 음수를 쓴다. */
    private static final long UNCATEGORIZED = -1L;

    private static Long categoryKey(ExpenseCategory category) {
        return category != null ? category.getRowId() : UNCATEGORIZED;
    }

    /** 수입 미분류와 지출 미분류를 갈라 담는 키 — 한 버킷에 섞으면 부호가 뒤엉킨다. */
    private static Long uncategorizedKey(ExpenseType type) {
        return type == ExpenseType.INCOME ? UNCATEGORIZED - 1 : UNCATEGORIZED;
    }

    private void addSpendRollup(Map<Long, Long> spent, ExpenseCategory category, Long amount) {
        if (category == null || amount == null) return;
        spent.merge(category.getRowId(), amount, Long::sum);
        if (category.getParent() != null) {
            spent.merge(category.getParent().getRowId(), amount, Long::sum);
        }
    }

    /** 기간 조회 공통 검증 — 시작일이 종료일보다 늦으면 조용히 빈/부분 결과를 내지 않고 거부. */
    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_INVALID_DATE_RANGE);
        }
    }

    /**
     * 이번 지출 변경으로 월 예산이 warn/100% 임계를 "처음으로" 넘었을 때만 알림 생성 (split-aware).
     * 대상 예산: 전체(null) / 거래·분할이 귀속되는 카테고리(leaf) 및 그 부모.
     *
     * @param previousTotal 변경 전 이 거래가 EXPENSE 로 반영하던 총액(생성=0).
     * @param previousByCat 변경 전 이 거래의 카테고리별(leaf+부모 롤업) 기여분(생성=빈 맵).
     *        카테고리별 delta = 현재 기여분 − previousByCat 으로 임계 "돌파"를 판정(수정 시 전체가 새로
     *        더해진 것으로 오판 방지 + warn→over 에스컬레이션 유지). 실패는 무시(저장을 막지 않음).
     */
    private void notifyBudgetThresholdIfCrossed(Expense expense, long previousTotal, Map<Long, Long> previousByCat) {
        try {
            if (expense == null || expense.getExpenseType() != ExpenseType.EXPENSE) return;
            if (expense.getAmount() == null || expense.getAmount() <= 0) return;

            Long userRowId = expense.getUser().getRowId();

            // 사용자가 예산 알림을 껐으면 여기서 끝낸다 — 종전엔 설정을 아예 안 봐서 꺼도 계속 만들어졌다.
            // 이 메서드가 하는 일은 알림 생성뿐이라(거래 저장은 이미 끝났다) 돌아서면 아래 집계
            // (월 거래 조회 + 분할 롤업)까지 통째로 아낀다 — 다른 로직에는 영향이 없다.
            // 사용자 객체는 저장 경로가 이미 들고 있던 것이라 조회가 더 들지 않는다.
            if (!expense.getUser().allowsBudgetNotification()) return;

            int year = expense.getExpenseDate().getYear();
            int month = expense.getExpenseDate().getMonthValue();

            List<ExpenseBudget> budgets = expenseBudgetRepository.findByUser(userRowId, year, month);
            if (budgets.isEmpty()) return;

            // 사용자 설정 warn 임계(%). 비율(85/100.0)로 만들면 이진 오차 때문에 경계에서 알림이
            // 한 발 빨리·늦게 터지므로, 아래 판정은 양변에 100 을 곱한 정수 비교로 한다.
            int warnPercent = userService.getBudgetAlertThreshold(userRowId) != null
                ? userService.getBudgetAlertThreshold(userRowId) : 85;

            // 해당 월의 split-aware 카테고리 지출(leaf+부모 롤업) + 전체 합계 (방금 저장된 이 expense 포함)
            LocalDate ms = LocalDate.of(year, month, 1);
            LocalDate me = ms.plusMonths(1).minusDays(1);
            // 화면의 예산 이행률과 같은 규칙 — 예정·환불·카드 이월을 뺀다.
            List<Expense> monthly = aggregatable(
                expenseRepository.findByDateRange(userRowId, ms, me), userRowId);
            Map<Long, List<ExpenseSplit>> splitsByExpense = loadSplitsByExpense(monthly);
            Map<Long, Long> spentByCat = expenseSpendRollup(monthly, splitsByExpense);
            long totalSpent = monthly.stream()
                .filter(e -> e.getExpenseType() == ExpenseType.EXPENSE && e.getAmount() != null)
                .mapToLong(Expense::getAmount).sum();

            // 이번 거래의 현재 기여분(leaf+부모) — delta(=현재−이전) 산정용
            // 합계에 안 들어가는 거래(미래 날짜 등)는 기여도 0 — 안 그러면 합계에 없는 금액을
            // 빼서 "돌파 전" 값이 틀린다. 그 날이 오면 09:00 배치가 같은 규칙으로 잡는다.
            boolean countedNow = countsInLedgerNow(expense);
            Map<Long, Long> currentByCat = countedNow
                ? expenseSpendRollup(List.of(expense), splitsByExpense)
                : Map.of();
            long currentTotal = countedNow ? expense.getAmount() : 0L;

            for (ExpenseBudget budget : budgets) {
                if (budget.getBudgetAmount() == null || budget.getBudgetAmount() <= 0) continue;
                Long bCatId = budget.getCategory() != null ? budget.getCategory().getRowId() : null;

                // 이 예산이 이번 거래(현재 또는 이전 기여)와 관련 있는가?
                boolean matches = bCatId == null
                    || currentByCat.containsKey(bCatId)
                    || previousByCat.containsKey(bCatId);
                if (!matches) continue;

                long afterSpent = (bCatId == null) ? totalSpent : spentByCat.getOrDefault(bCatId, 0L);
                long delta = (bCatId == null)
                    ? (currentTotal - previousTotal)
                    : (currentByCat.getOrDefault(bCatId, 0L) - previousByCat.getOrDefault(bCatId, 0L));
                long beforeSpent = afterSpent - delta;
                long limit = budget.getBudgetAmount();
                // 임계 돌파 판정 — 전부 정수 교차곱. (지출/한도 >= pct/100) 을 (지출*100 >= 한도*pct) 로 본다.
                long beforeScaled = beforeSpent * 100L;
                long afterScaled = afterSpent * 100L;
                long overScaled = limit * 100L;          // 100% = 초과
                long warnScaled = limit * warnPercent;

                // 문구는 번들에서 온다 — 같은 알림을 매일 09:00 배치도 만들기 때문이다
                // (NotificationTriggerScheduler). 종전엔 두 곳이 각자 문장을 만들어 갈렸다(QA #76).
                String categoryName = bCatId == null
                    ? notificationMessages.budgetCategoryAll() : budget.getCategory().getCategoryName();

                if (beforeScaled < overScaled && afterScaled >= overScaled) {
                    notificationService.createNotification(new NotificationServiceDto.CreateCommand(
                        userRowId,
                        NotificationType.BUDGET_ALERT,
                        notificationMessages.budgetOverTitle(categoryName),
                        notificationMessages.budgetOverMessage(categoryName, limit, afterSpent),
                        ReferenceType.EXPENSE_BUDGET,
                        budget.getRowId()
                    ));
                } else if (beforeScaled < warnScaled && afterScaled >= warnScaled) {
                    // WARN 분기는 정의상 초과가 아니다 — 표시 % 의 반올림·99 cap 은 usagePercent 가 맡는다.
                    int pct = NotificationMessages.usagePercent(afterSpent, limit);
                    notificationService.createNotification(new NotificationServiceDto.CreateCommand(
                        userRowId,
                        NotificationType.BUDGET_ALERT,
                        notificationMessages.budgetWarnTitle(categoryName, pct),
                        notificationMessages.budgetWarnMessage(categoryName, pct, afterSpent, limit),
                        ReferenceType.EXPENSE_BUDGET,
                        budget.getRowId()
                    ));
                }
            }
        } catch (Exception ex) {
            log.warn("예산 임계 알림 처리 실패: {}", ex.getMessage());
        }
    }

    /**
     * 거래 금액은 0보다 커야 한다.
     *
     * <p>부호는 유형(EXPENSE/INCOME)이 정하고 금액은 크기만 담는다 — 잔액 flow 를
     * {@code signed = (INCOME) ? amount : -amount} 로 만들기 때문이다. 음수가 들어오면
     * 부호가 두 번 뒤집혀 <b>지출인데 잔액이 늘어난다</b>.
     *
     * <p>화면은 숫자만 입력받아 음수를 만들 수 없지만, 가져오기·반복거래·API 직접 호출은
     * 그 필터를 안 거친다. 이체·매매가 같은 이유로 이미 막고 있어 지출만 빠져 있었다.
     */
    private void validateAmount(Long amount) {
        if (amount == null || amount <= 0) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_INVALID_AMOUNT);
        }
    }

}
