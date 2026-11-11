package io.groo.damda.expense.service;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.core.exception.IntegrityViolations;
import io.groo.damda.common.validation.AmountLimits;
import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.domain.ExpenseBudget;
import io.groo.damda.expense.domain.ExpenseCategory;
import io.groo.damda.expense.repository.ExpenseBudgetRepository;
import io.groo.damda.expense.repository.ExpenseCategoryRepository;
import io.groo.damda.expense.repository.ExpenseRepository;
import io.groo.damda.expense.service.dto.ExpenseBudgetServiceDto;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import io.groo.core.time.UserClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import io.groo.damda.expense.domain.ExpenseAggregates;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@Transactional(readOnly = true)
public class ExpenseBudgetServiceImpl implements ExpenseBudgetService {
    private final ExpenseBudgetRepository expenseBudgetRepository;
    private final UserClock userClock;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final ExpenseRepository expenseRepository;
    private final UserRepository userRepository;

    /**
     * 등록 시도 하나마다 <b>새 트랜잭션</b>을 여는 템플릿.
     *
     * <p>{@code @RequiredArgsConstructor} 를 버리고 생성자를 손으로 쓴 이유가 이것 하나다 —
     * 여기서 {@code REQUIRES_NEW} 를 못 박아야 {@link #createBudget} 의 재시도가 성립한다.
     * 자세한 이유는 {@code createBudget} 주석에 있다.
     */
    private final TransactionTemplate newTransaction;

    public ExpenseBudgetServiceImpl(ExpenseBudgetRepository expenseBudgetRepository,
                                    UserClock userClock,
                                    ExpenseCategoryRepository expenseCategoryRepository,
                                    ExpenseRepository expenseRepository,
                                    UserRepository userRepository,
                                    PlatformTransactionManager transactionManager) {
        this.expenseBudgetRepository = expenseBudgetRepository;
        this.userClock = userClock;
        this.expenseCategoryRepository = expenseCategoryRepository;
        this.expenseRepository = expenseRepository;
        this.userRepository = userRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 예산 등록 — 같은 <b>(사용자 · 카테고리 · 연월)</b> 에는 행이 하나만 있는다.
     *
     * <p>없으면 만들고, 있으면 그 행의 금액을 고친다(QA 2026-09-03 #77 ①). 종전엔 무조건
     * {@code save(new)} 라 같은 달 같은 카테고리로 두 번 POST 하면 둘 다 저장됐다. 예산 알림
     * 스케줄러는 <b>예산 행마다</b> 돌고 중복 방지도 {@code referenceRowId = 예산 행 아이디} 로
     * 걸려 있어, 행이 둘이면 같은 상황을 두 번 알렸다. 행을 하나로 접으면 알림도 하나가 된다.
     *
     * <p>수정은 {@code merge} 가 아니라 <b>더티 체킹</b>이다(QA #78 ④) — 조회한 엔티티의 필드를
     * 바꾸면 트랜잭션이 끝날 때 UPDATE 가 나간다. 생성이든 수정이든 응답은 같다(200 + 행).
     *
     * <h4>동시 저장 경쟁</h4>
     * 조회와 저장 사이는 원자적이지 않다. 두 요청이 같이 들어오면 둘 다 "없다" 를 보고 둘 다
     * INSERT 하는데, DB UK 가 걸리면 진 쪽이 {@link DataIntegrityViolationException} 으로 터진다.
     * 그때는 상대가 넣은 행을 다시 찾아 수정하는 것이 맞는 답이다 — 사용자가 원한 것은
     * "이 달 예산을 이 금액으로" 였지 "새 행" 이 아니기 때문이다.
     *
     * <p><b>그 복구를 같은 트랜잭션에서 하면 안 된다.</b> 제약 위반이 난 하이버네이트 세션은
     * 더 못 쓴다 — 이어서 조회·flush 하면
     * {@code AssertionFailure: ... has a null identifier (this can happen if the session is
     * flushed after an exception occurs)} 로 죽는다(H2 로 직접 확인). 게다가 MariaDB 기본
     * 격리수준(REPEATABLE READ)에서는 같은 트랜잭션의 재조회가 처음 뜬 스냅샷을 그대로 보므로
     * 상대가 <b>그 뒤에</b> 커밋한 행은 애초에 보이지도 않는다.
     *
     * <p>그래서 시도 하나를 트랜잭션 하나로 감싸고({@link #newTransaction}), 위반이 나면
     * <b>새 트랜잭션으로 한 번만</b> 다시 돈다. 새 트랜잭션은 세션도 스냅샷도 새것이라
     * 이번 조회는 상대 행을 보고 수정 경로로 수렴한다. 재시도는 1회다 — 두 번째도 위반이면
     * 우리가 모르는 상황이므로 그대로 올린다(무한 루프 금지).
     */
    @Override
    // 재시도가 성립하려면 이 메서드가 트랜잭션을 들고 있으면 안 된다. 클래스 기본값
    // (readOnly = true) 이 걸리는 것도 막는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ExpenseBudgetServiceDto.BudgetInfo createBudget(ExpenseBudgetServiceDto.CreateCommand command) {
        log.debug("예산 등록 시작: userRowId={}, year={}, month={}", command.userRowId(), command.budgetYear(), command.budgetMonth());
        try {
            return newTransaction.execute(status -> upsertBudget(command));
        } catch (DataIntegrityViolationException e) {
            // 재시도가 뜻을 갖는 건 UNIQUE 위반뿐이다 — 상대가 넣은 행을 다시 찾아 수정하면 되기 때문이다.
            // NOT NULL·FK 는 다시 돌려도 같은 자리에서 같게 터지므로 그대로 올린다(QA #81).
            if (!IntegrityViolations.isUnique(e)) throw e;
            log.info("예산 등록 경쟁 감지 — 새 트랜잭션으로 재조회 후 수정: userRowId={}, categoryRowId={}, {}-{}",
                command.userRowId(), command.categoryRowId(), command.budgetYear(), command.budgetMonth());
            return newTransaction.execute(status -> upsertBudget(command));
        }
    }

    /**
     * 등록 시도 한 번 — <b>{@link #newTransaction} 안에서만</b> 부른다.
     *
     * <p>검증부터 저장까지 전부 여기에 둔다. 재시도가 이 메서드를 통째로 다시 도는 방식이라,
     * 조회를 밖에 두면 두 번째 시도가 첫 번째의 스냅샷을 물려받아 아무것도 못 고친다.
     */
    private ExpenseBudgetServiceDto.BudgetInfo upsertBudget(ExpenseBudgetServiceDto.CreateCommand command) {
        // 연·월이 없으면 조회로 가기 전에 끊는다. 아래 findByUserAndCategory 가 두 값을 그대로
        // QueryDSL 에 넘기는데 eq(null) 은 IllegalArgumentException 이고, @Repository 프록시가
        // 그걸 InvalidDataAccessApiUsageException 으로 번역해 매핑이 없는 채로 500 이 됐다
        // (QA 2026-09-07 #85).
        if (command.budgetYear() == null || command.budgetMonth() == null) {
            throw new InvalidValueException(DeskErrorCode.REQUIRED_VALUE_MISSING);
        }

        User user = userRepository.findById(command.userRowId())
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));

        validateBudgetAmount(command.budgetAmount());

        ExpenseCategory category = null;
        if (command.categoryRowId() != null) {
            category = expenseCategoryRepository.findById(command.categoryRowId())
                .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.EXPENSE_CATEGORY_NOT_FOUND));
            validateCategoryOwnership(category, command.userRowId());
            // 정책: 예산은 최상위(부모) 카테고리에만. 자식(하위) 카테고리는 예산 불가.
            if (category.getParent() != null) {
                throw new InvalidValueException(DeskErrorCode.EXPENSE_BUDGET_CATEGORY_NOT_ROOT);
            }
        }

        // categoryRowId 가 null 인 "전체 예산" 이 있다. 리포지토리 두 구현 모두 null 을
        // `category IS NULL` 로 갈라 놓았다 — `= null` 로 비교하면 아무것도 안 잡혀
        // 전체 예산만 계속 중복된다.
        ExpenseBudget existing = expenseBudgetRepository.findByUserAndCategory(
            command.userRowId(), command.categoryRowId(),
            command.budgetYear(), command.budgetMonth()).orElse(null);

        if (existing != null) {
            existing.updateBudget(command.budgetAmount());
            log.info("예산 등록 — 기존 행 수정: budgetId={}, userRowId={}, amount={}",
                existing.getRowId(), command.userRowId(), command.budgetAmount());
            return ExpenseBudgetServiceDto.BudgetInfo.from(existing);
        }

        ExpenseBudget budget = ExpenseBudget.createBudget(
            user,
            category,
            command.budgetAmount(),
            command.budgetYear(),
            command.budgetMonth()
        );

        expenseBudgetRepository.save(budget);
        log.info("예산 등록 완료: budgetId={}, userRowId={}", budget.getRowId(), command.userRowId());

        return ExpenseBudgetServiceDto.BudgetInfo.from(budget);
    }

    @Override
    public List<ExpenseBudgetServiceDto.BudgetInfo> getBudgets(Long userRowId, Integer year, Integer month) {
        log.debug("예산 목록 조회: userRowId={}, year={}, month={}", userRowId, year, month);

        List<ExpenseBudget> budgets = expenseBudgetRepository.findByUser(userRowId, year, month);

        return budgets.stream()
            .map(ExpenseBudgetServiceDto.BudgetInfo::from)
            .toList();
    }

    @Override
    public List<ExpenseBudgetServiceDto.ComplianceMonth> getCompliance(Long userRowId, Integer months) {
        int n = (months == null || months < 1) ? 6 : Math.min(months, 24);
        log.debug("예산 이행률 조회: userRowId={}, months={}", userRowId, n);

        LocalDate now = userClock.today(userRowId);

        // 달마다 쿼리를 날리면 24개월에 24번이다 — 한 번 받아 월별로 접는다.
        LocalDate from = now.minusMonths(n - 1L).withDayOfMonth(1);
        LocalDate to = now.withDayOfMonth(1).plusMonths(1).minusDays(1);
        // 예산은 **가계부** 숫자다 — 카드 이월(등록 전에 이미 쓴 돈)은 뺀다. countable 을 쓰면
        // 카드를 등록한 달만 이행률이 솟는다(D4 후속 결정, 2026-09-18).
        Map<String, List<Expense>> byMonth = ExpenseAggregates
            .ledgerCountable(expenseRepository.findByDateRange(userRowId, from, to, null),
                userClock.now(userRowId))
            .stream()
            .collect(Collectors.groupingBy(
                e -> e.getExpenseDate().getYear() + "-" + e.getExpenseDate().getMonthValue()));

        List<ExpenseBudgetServiceDto.ComplianceMonth> result = new ArrayList<>(n);

        for (int i = n - 1; i >= 0; i--) {
            LocalDate m = now.minusMonths(i);
            int y = m.getYear();
            int mm = m.getMonthValue();

            List<ExpenseBudget> budgets = expenseBudgetRepository.findByUser(userRowId, y, mm);
            // 전체 상한(category == null) 이 있으면 그것만 한도로 사용.
            // 없을 때만 카테고리별 한도의 합으로 대체 — 중복 집계 방지.
            long overallLimit = budgets.stream()
                .filter(b -> b.getCategory() == null)
                .mapToLong(ExpenseBudget::getBudgetAmount)
                .sum();
            long totalLimit = overallLimit > 0
                ? overallLimit
                : budgets.stream()
                    .filter(b -> b.getCategory() != null)
                    .mapToLong(ExpenseBudget::getBudgetAmount)
                    .sum();

            // 요약·추이와 같은 규칙으로 센다 — 여기만 raw 합이라 같은 화면에서 상단 카드와
            // 이행률 차트가 달랐다(20361% vs 20460%). 규칙은 ExpenseAggregates 에 하나뿐이다.
            long totalSpent = ExpenseAggregates.expenseSum(
                byMonth.getOrDefault(y + "-" + mm, List.of()));

            double compliancePercent = totalLimit > 0
                ? Math.round(((double) totalSpent / totalLimit) * 1000.0) / 10.0
                : 0.0;

            result.add(new ExpenseBudgetServiceDto.ComplianceMonth(y, mm, totalLimit, totalSpent, compliancePercent));
        }

        return result;
    }

    @Override
    @Transactional
    public ExpenseBudgetServiceDto.BudgetInfo updateBudget(Long budgetId, Long userRowId, ExpenseBudgetServiceDto.UpdateCommand command) {
        log.debug("예산 수정 시작: budgetId={}, userRowId={}", budgetId, userRowId);
        ExpenseBudget budget = findBudgetOrThrow(budgetId);
        validateBudgetOwnership(budget, userRowId);
        validateBudgetAmount(command.budgetAmount());
        budget.updateBudget(command.budgetAmount());
        log.info("예산 수정 완료: budgetId={}, amount={}", budget.getRowId(), command.budgetAmount());
        return ExpenseBudgetServiceDto.BudgetInfo.from(budget);
    }

    @Override
    @Transactional
    public void deleteBudget(Long budgetId, Long userRowId) {
        log.debug("예산 삭제 시작: budgetId={}", budgetId);

        ExpenseBudget budget = findBudgetOrThrow(budgetId);
        validateBudgetOwnership(budget, userRowId);
        expenseBudgetRepository.delete(budget);

        log.info("예산 삭제 완료: budgetId={}", budgetId);
    }

    private void validateBudgetOwnership(ExpenseBudget budget, Long userRowId) {
        if (!budget.getUser().getRowId().equals(userRowId)) {
            log.warn("예산 소유권 검증 실패 - budgetId={}, ownerRowId={}, requestUserRowId={}",
                budget.getRowId(), budget.getUser().getRowId(), userRowId);
            throw new ForbiddenException(DeskErrorCode.EXPENSE_ACCESS_DENIED);
        }
    }

    /**
     * 예산 금액은 1원 이상 100억원 이하여야 한다.
     *
     * <p>0/음수는 알림 스케줄러의 0 나눗셈·잘못된 사용률을 유발한다. 상한은 거래와 같은 층인데
     * 종전엔 없어서 999억 예산이 저장됐다(QA 2026-09-03 #48).
     *
     * <p>DTO 에 같은 범위를 걸어 컨트롤러에서 먼저 400 이 나가지만 이 검증은 <b>지우지 않는다</b> —
     * 스케줄러·가져오기처럼 컨트롤러를 안 거치고 서비스를 직접 부르는 경로가 있다.
     */
    private void validateBudgetAmount(Long amount) {
        if (amount == null || amount <= 0 || amount > AmountLimits.MAX_TX_AMOUNT) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_BUDGET_INVALID_AMOUNT);
        }
    }

    private void validateCategoryOwnership(ExpenseCategory category, Long userRowId) {
        if (!category.getUser().getRowId().equals(userRowId)) {
            log.warn("지출 카테고리 소유권 검증 실패 - categoryId={}, ownerRowId={}, requestUserRowId={}",
                category.getRowId(), category.getUser().getRowId(), userRowId);
            throw new ForbiddenException(DeskErrorCode.EXPENSE_ACCESS_DENIED);
        }
    }

    private ExpenseBudget findBudgetOrThrow(Long budgetId) {
        return expenseBudgetRepository.findById(budgetId)
            .orElseThrow(() -> {
                log.warn("예산 조회 실패 - 존재하지 않는 예산: budgetId={}", budgetId);
                return new EntityNotFoundException(DeskErrorCode.EXPENSE_BUDGET_NOT_FOUND);
            });
    }
}
