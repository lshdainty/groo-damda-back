package io.groo.damda.expense.service;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.core.exception.IntegrityViolations;
import io.groo.damda.common.util.NameNormalizer;
import io.groo.damda.common.validation.FieldLimits;
import io.groo.damda.expense.domain.ExpenseBudget;
import io.groo.damda.expense.domain.ExpenseCategory;
import io.groo.damda.expense.repository.ExpenseBudgetRepository;
import io.groo.damda.expense.repository.ExpenseCategoryRepository;
import io.groo.damda.expense.repository.ExpenseRepository;
import io.groo.damda.expense.repository.ExpenseSplitRepository;
import io.groo.damda.expense.repository.RecurringTransactionRepository;
import io.groo.damda.expense.service.dto.ExpenseCategoryServiceDto;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.domain.ExpenseSplit;
import io.groo.damda.expense.domain.RecurringTransaction;
import java.util.Objects;

@Service
@Slf4j
@Transactional(readOnly = true)
public class ExpenseCategoryServiceImpl implements ExpenseCategoryService {
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final ExpenseBudgetRepository expenseBudgetRepository;
    private final ExpenseRepository expenseRepository;
    private final ExpenseSplitRepository expenseSplitRepository;
    private final RecurringTransactionRepository recurringTransactionRepository;
    private final UserRepository userRepository;

    /**
     * 확보 시도 하나마다 <b>새 트랜잭션</b>을 여는 템플릿 — {@link #findOrCreateCategory} 전용.
     *
     * <p>{@code @RequiredArgsConstructor} 를 버리고 생성자를 손으로 쓴 이유가 이것 하나다.
     * 예산({@code ExpenseBudgetServiceImpl})이 같은 자리에서 같은 이유로 먼저 세워 둔 모양이다.
     */
    private final TransactionTemplate newTransaction;

    public ExpenseCategoryServiceImpl(ExpenseCategoryRepository expenseCategoryRepository,
                                      ExpenseBudgetRepository expenseBudgetRepository,
                                      ExpenseRepository expenseRepository,
                                      ExpenseSplitRepository expenseSplitRepository,
                                      RecurringTransactionRepository recurringTransactionRepository,
                                      UserRepository userRepository,
                                      PlatformTransactionManager transactionManager) {
        this.expenseCategoryRepository = expenseCategoryRepository;
        this.expenseBudgetRepository = expenseBudgetRepository;
        this.expenseRepository = expenseRepository;
        this.expenseSplitRepository = expenseSplitRepository;
        this.recurringTransactionRepository = recurringTransactionRepository;
        this.userRepository = userRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional
    public ExpenseCategoryServiceDto.CategoryInfo createCategory(ExpenseCategoryServiceDto.CreateCommand command) {
        String categoryName = NameNormalizer.require(command.categoryName(), FieldLimits.NAME_MAX);
        try {
            return createCategoryInternal(command, categoryName);
        } catch (DataIntegrityViolationException e) {
            // 조회 검사와 저장 사이는 원자적이지 않다 — 같은 이름의 두 요청이 동시에 들어오면
            // 둘 다 "없다" 를 보고 둘 다 INSERT 한다. 활성 이름 UNIQUE 가 진 쪽을 여기서 잡는다.
            // 답은 재조회가 아니라 409 다: 사용자가 원한 건 "이 이름의 새 카테고리" 였고 그 이름은 이미 있다.
            // UNIQUE 가 아닌 위반(NOT NULL·FK)은 이름 중복이 아니므로 그대로 올린다(QA #81).
            if (!IntegrityViolations.isUnique(e)) throw e;
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_DUPLICATE_NAME, e);
        }
    }

    /**
     * 같은 자리에 그 이름의 활성 카테고리를 확보한다 — 없으면 만들고 있으면 그 행을 돌려준다.
     *
     * <p>가져오기 전용 진입점이다. 왜 {@link #createCategory} 로는 안 되는지는 인터페이스 주석에 있다.
     *
     * <h4>왜 새 트랜잭션인가</h4>
     * 제약 위반이 난 하이버네이트 세션은 더 못 쓴다 — 이어서 조회·flush 하면
     * {@code AssertionFailure ... null identifier} 로 죽는다. 게다가 MariaDB 기본 격리수준
     * (REPEATABLE READ)에서는 같은 트랜잭션의 재조회가 처음 뜬 스냅샷을 그대로 보므로
     * 상대가 <b>그 뒤에</b> 커밋한 행은 애초에 보이지도 않는다. 그래서 시도 하나를 트랜잭션
     * 하나로 감싸고, 위반이 나면 새 트랜잭션으로 <b>한 번만</b> 다시 돈다(무한 루프 금지).
     */
    @Override
    // 재시도가 성립하려면 이 메서드가 트랜잭션을 들고 있으면 안 된다. 클래스 기본값
    // (readOnly = true) 이 걸리는 것도 막는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ExpenseCategoryServiceDto.CategoryInfo findOrCreateCategory(ExpenseCategoryServiceDto.CreateCommand command) {
        String categoryName = NameNormalizer.require(command.categoryName(), FieldLimits.NAME_MAX);
        try {
            return newTransaction.execute(status -> resolveOrCreateCategory(command, categoryName));
        } catch (DataIntegrityViolationException e) {
            // 재시도가 뜻을 갖는 건 UNIQUE 위반뿐이다 — 상대가 넣은 행을 다시 찾아 쓰면 되기 때문이다.
            // NOT NULL·FK 는 몇 번을 다시 돌려도 같은 자리에서 같게 터진다. 두 번째 위반을 그대로
            // 올리는 것과 결과는 같지만, 쓸모없는 트랜잭션을 한 번 더 열지 않고 원인도 안 갈린다(QA #81).
            if (!IntegrityViolations.isUnique(e)) throw e;
            log.info("카테고리 확보 경쟁 감지 — 새 트랜잭션으로 재조회 후 재사용: userRowId={}, parentRowId={}, name={}",
                command.userRowId(), command.parentRowId(), categoryName);
            return newTransaction.execute(status -> resolveOrCreateCategory(command, categoryName));
        }
    }

    /** 확보 시도 한 번 — {@link #newTransaction} 안에서만 부른다(조회를 밖에 두면 재시도가 옛 스냅샷을 물려받는다). */
    private ExpenseCategoryServiceDto.CategoryInfo resolveOrCreateCategory(
            ExpenseCategoryServiceDto.CreateCommand command, String categoryName) {
        return expenseCategoryRepository.findActiveByUserAndParentAndTypeAndName(
                command.userRowId(), command.parentRowId(), command.expenseType(), categoryName)
            .map(ExpenseCategoryServiceDto.CategoryInfo::from)
            .orElseGet(() -> createCategoryInternal(command, categoryName));
    }

    /**
     * 등록 본체 — 이름은 이미 정규화된 값을 받는다.
     *
     * <p>제약 위반을 <b>번역하지 않고</b> 그대로 올린다. 부르는 쪽이 둘인데 답이 다르기 때문이다:
     * {@link #createCategory} 는 409 로 바꾸고, {@link #findOrCreateCategory} 는 재조회·재사용으로 간다.
     */
    private ExpenseCategoryServiceDto.CategoryInfo createCategoryInternal(
            ExpenseCategoryServiceDto.CreateCommand command, String categoryName) {
        log.debug("지출 카테고리 등록 시작: userRowId={}, categoryName={}", command.userRowId(), categoryName);

        User user = userRepository.findById(command.userRowId())
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));

        ExpenseCategory parent = null;
        if (command.parentRowId() != null) {
            parent = findCategoryOrThrow(command.parentRowId());
            validateCategoryOwnership(parent, command.userRowId());

            if (parent.getParent() != null) {
                throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_MAX_DEPTH);
            }

            if (parent.getExpenseType() != command.expenseType()) {
                throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_TYPE_MISMATCH);
            }
            // 정책: 부모는 직접 거래를 가질 수 없음 — 거래/반복 거래가 있는 카테고리는
            // 자식을 가질(부모가 될) 수 없다. 먼저 내역을 다른 카테고리로 옮겨야 함.
            validateCanBecomeParent(parent.getRowId());
        }

        // 같은 위치(부모)·같은 타입 내 활성 카테고리명 중복 금지 (삭제된 같은 이름은 재사용 허용).
        // parentRowId 가 null(최상위)인 경우를 리포지토리 두 구현 모두 `parent IS NULL` 로 갈라 놨다 —
        // `= null` 로 비교하면 아무것도 안 잡혀 최상위만 중복이 무제한 허용된다.
        if (expenseCategoryRepository.existsActiveByUserAndParentAndTypeAndName(
                command.userRowId(), command.parentRowId(), command.expenseType(), categoryName, null)) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_DUPLICATE_NAME);
        }

        ExpenseCategory category = ExpenseCategory.createCategory(
            user,
            categoryName,
            command.icon(),
            command.color(),
            command.expenseType(),
            parent,
            // 클라이언트가 실어 보낸 자리를 그대로 쓴다. 안 보내면 팩토리가 0(맨 앞)으로 둔다 —
            // 종전엔 이 값이 컨트롤러에서 서비스로 오지도 못했다(QA 2026-09-07 #91).
            command.sortOrder()
        );

        expenseCategoryRepository.save(category);
        // INSERT 를 지금 내보낸다 — 안 그러면 UNIQUE 위반이 커밋 시점(이 메서드가 반환한 뒤)에
        // 터져 부르는 쪽의 try/catch 가 닿지 않는다.
        expenseCategoryRepository.flush();
        log.info("지출 카테고리 등록 완료: categoryId={}, userRowId={}", category.getRowId(), command.userRowId());

        return ExpenseCategoryServiceDto.CategoryInfo.from(category);
    }

    /**
     * 기본 카테고리 세트 — 이름·아이콘(lucide)·색(카테고리 팔레트).
     *
     * <p>이름은 한국어 고정이다. 카테고리명은 사용자 데이터라 이후 언어를 바꿔도
     * 번역되지 않는 값이고, 시딩 시점엔 사용자의 표시 언어를 알 수 없다.
     */
    private static final List<String[]> DEFAULT_EXPENSE_CATEGORIES = List.of(
        new String[]{"식비", "utensils", "#c73838"},
        new String[]{"카페·간식", "coffee", "#b36418"},
        new String[]{"교통", "bus", "#8c7400"},
        new String[]{"주거·통신", "home", "#2d8060"},
        new String[]{"생활", "shopping-cart", "#2c70bf"},
        new String[]{"쇼핑", "shirt", "#5e60c8"},
        new String[]{"건강", "heart", "#b83b7a"},
        new String[]{"문화·여가", "film", "#8b4dba"}
    );
    private static final List<String[]> DEFAULT_INCOME_CATEGORIES = List.of(
        new String[]{"급여", "wallet", "#2c70bf"},
        new String[]{"용돈", "gift", "#2d8060"},
        new String[]{"부수입", "trending-up", "#9a6536"}
    );

    /**
     * 여기는 활성 이름 UNIQUE 를 위반할 수 없다 — 그래서 방어를 두지 않는다(확인한 근거를 남긴다).
     *
     * <p>① 심는 이름이 서로 겹치지 않는다: 지출 8개·수입 3개가 각 타입 안에서 전부 다른 이름이고
     * 부모는 전부 null 이다. ② 같은 사용자에게 두 번 심을 수 없다: 호출처가 <b>신규 {@code users}
     * INSERT 직후 같은 트랜잭션</b> 한 곳뿐이고({@code TokenExchangeService}), 동시 최초 로그인은
     * {@code desk.users} 의 {@code UNIQUE KEY UK_users_user_id} 에서 먼저 갈린다 — 설령 그 UK 가
     * 없더라도 진 쪽은 <b>다른 user_row_id</b> 로 심으므로 카테고리 키가 겹치지 않는다.
     *
     * <p><b>이 자리에 409 를 던지면 로그인이 죽는다.</b> 같은 트랜잭션이라 시딩이 터지면 사용자
     * 생성까지 롤백된다. 그래서 여기에는 아무 변환도 두지 않고, 위반이 불가능하다는 것을 근거로 남긴다.
     */
    @Override
    @Transactional
    public void seedDefaults(Long userRowId) {
        if (!expenseCategoryRepository.findAllByUser(userRowId).isEmpty()) {
            return;
        }

        User user = userRepository.findById(userRowId)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));

        int order = 0;
        for (String[] c : DEFAULT_EXPENSE_CATEGORIES) {
            saveDefault(user, c, ExpenseType.EXPENSE, order++);
        }
        order = 0;
        for (String[] c : DEFAULT_INCOME_CATEGORIES) {
            saveDefault(user, c, ExpenseType.INCOME, order++);
        }

        log.info("기본 카테고리 시딩 완료: userRowId={}, expense={}, income={}",
            userRowId, DEFAULT_EXPENSE_CATEGORIES.size(), DEFAULT_INCOME_CATEGORIES.size());
    }

    private void saveDefault(User user, String[] def, ExpenseType type, int sortOrder) {
        ExpenseCategory category = ExpenseCategory.createCategory(user, def[0], def[1], def[2], type, null);
        category.updateSortOrder(sortOrder);
        expenseCategoryRepository.save(category);
    }

    @Override
    public List<ExpenseCategoryServiceDto.CategoryInfo> getCategories(Long userRowId) {
        log.debug("지출 카테고리 목록 조회: userRowId={}", userRowId);

        List<ExpenseCategory> categories = expenseCategoryRepository.findAllByUser(userRowId);

        Set<Long> parentIds = categories.stream()
            .filter(c -> c.getParent() != null)
            .map(c -> c.getParent().getRowId())
            .collect(Collectors.toSet());

        return categories.stream()
            .map(c -> ExpenseCategoryServiceDto.CategoryInfo.fromWithHasChildren(c, parentIds.contains(c.getRowId())))
            .toList();
    }

    @Override
    @Transactional
    public ExpenseCategoryServiceDto.CategoryInfo updateCategory(Long categoryId, Long userRowId, ExpenseCategoryServiceDto.UpdateCommand command) {
        log.debug("지출 카테고리 수정 시작: categoryId={}", categoryId);

        String categoryName = NameNormalizer.require(command.categoryName(), FieldLimits.NAME_MAX);

        ExpenseCategory category = findCategoryOrThrow(categoryId);
        validateCategoryOwnership(category, userRowId);

        ExpenseType targetType = command.expenseType() != null
            ? command.expenseType()
            : category.getExpenseType();

        ExpenseCategory targetParent = resolveParent(category, command.parentRowId(), targetType, userRowId);
        if (targetParent == null) {
            // 최상위 유지 — 자식이 있으면 타입을 바꿔도 자식과 불일치하면 안 됨.
            if (expenseCategoryRepository.hasChildren(categoryId)
                && targetType != category.getExpenseType()) {
                throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_TYPE_MISMATCH);
            }
        }

        // 변경 후 위치(부모)·타입 기준 활성 카테고리명 중복 금지 (자기 자신 제외)
        if (expenseCategoryRepository.existsActiveByUserAndParentAndTypeAndName(
                userRowId, command.parentRowId(), targetType, categoryName, categoryId)) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_DUPLICATE_NAME);
        }

        category.updateCategory(
            categoryName,
            command.icon(),
            command.color(),
            command.sortOrder()
        );
        category.changeExpenseType(targetType);
        category.moveParent(targetParent);
        flushOrRejectDuplicate();

        log.info("지출 카테고리 수정 완료: categoryId={}", categoryId);

        return ExpenseCategoryServiceDto.CategoryInfo.from(category);
    }

    /**
     * 계층(부모) 변경 규칙 — 수정(PUT)과 정렬(PATCH reorder)이 **같은 규칙**을 쓴다.
     *
     * <ul>
     *   <li>최상위(부모) → 하위(강등): 금지. 삭제 후 재생성으로만.</li>
     *   <li>하위(자식) → 최상위(승격): 금지(현재). 연결 내역 이관 후 별도 기능에서.</li>
     *   <li>하위 → 다른 하위(부모 변경/이동): 허용. 단 새 부모는 최상위·같은 구분이고, 아직 자식이
     *       없던 leaf 라면 거래·반복·분할이 없어야 한다(부모는 직접 거래 불가).</li>
     * </ul>
     *
     * <p>정렬에는 이 검사가 따로 적혀 있었고 승격·강등·거래 달린 새 부모를 막지 않았다 — 수정은
     * 막는데 정렬로는 하위를 최상위로 올릴 수 있었다(QA 28 3). 웹·앱 정렬 화면은 같은 부모 안에서
     * 순서만 바꾸고 부모를 그대로 싣는다(`CategoryManager.handleDragEnd` · `category_screen`
     * `onReorderSiblings`) — 그 경로는 부모가 안 바뀌어 이 검사를 타지 않는다.
     *
     * @return 새 부모. 최상위면 null
     */
    private ExpenseCategory resolveParent(ExpenseCategory category, Long newParentRowId,
                                          ExpenseType targetType, Long userRowId) {
        boolean wasChild = category.getParent() != null;
        boolean willHaveParent = newParentRowId != null;

        if (!wasChild && willHaveParent) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_CANNOT_DEMOTE);
        }
        if (wasChild && !willHaveParent) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_CANNOT_PROMOTE);
        }
        if (!willHaveParent) {
            return null;
        }
        // 이 시점은 하위 → 하위(같은 부모 포함)만 도달한다 (위에서 강등/승격 차단됨).
        if (newParentRowId.equals(category.getRowId())) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_MAX_DEPTH);
        }
        ExpenseCategory targetParent = findCategoryOrThrow(newParentRowId);
        validateCategoryOwnership(targetParent, userRowId);
        if (targetParent.getParent() != null) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_MAX_DEPTH);
        }
        if (targetParent.getExpenseType() != targetType) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_TYPE_MISMATCH);
        }
        // 새 부모가 아직 자식이 없던 leaf 라면, 그 자체가 거래/반복을 보유한 경우
        // 부모가 될 수 없다(부모는 직접 거래 불가).
        if (!expenseCategoryRepository.hasChildren(targetParent.getRowId())) {
            validateCanBecomeParent(targetParent.getRowId());
        }
        return targetParent;
    }

    /** 거래·반복 거래·분할(split) 항목이 있는 카테고리는 부모(상위)가 될 수 없다(부모는 직접 거래 불가). */
    @Override
    @Transactional
    public ExpenseCategoryServiceDto.MoveResult moveTransactionsToNewChild(
            Long categoryId, String childName, String icon, String color, Long userRowId) {
        String name = NameNormalizer.require(childName, FieldLimits.NAME_MAX);

        ExpenseCategory source = findCategoryOrThrow(categoryId);
        validateCategoryOwnership(source, userRowId);

        // 카테고리는 2단계까지 — 하위에는 또 하위를 만들 수 없다.
        if (source.getParent() != null) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_MAX_DEPTH);
        }
        // 이미 자식이 있으면 직접 거래를 가질 수 없는 상태다 — 일반 이동(moveTransactions)을 쓰면 된다.
        if (expenseCategoryRepository.hasChildren(categoryId)) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_NOT_LEAF);
        }
        if (expenseCategoryRepository.existsActiveByUserAndParentAndTypeAndName(
                userRowId, categoryId, source.getExpenseType(), name, null)) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_DUPLICATE_NAME);
        }

        // validateCanBecomeParent 를 거치지 않는다 — 지금은 위반이지만 바로 아래에서 거래를
        // 전부 옮기므로 커밋 시점엔 부모에 직접 거래가 남지 않는다. createCategory 를 쓰면
        // 그 검증에 걸려 이 교착을 영영 풀 수 없다.
        ExpenseCategory child = ExpenseCategory.createCategory(
            source.getUser(), name, icon, color, source.getExpenseType(), source);
        expenseCategoryRepository.save(child);
        flushOrRejectDuplicate();

        ExpenseCategoryServiceDto.MoveResult moved = moveAllReferences(categoryId, child);
        log.info("카테고리 하위 생성 + 거래 이동: {} → 신규 자식 '{}', 거래 {}건 / 반복 {}건 / 분할 {}건",
            categoryId, name, moved.expenses(), moved.recurring(), moved.splits());
        return moved;
    }

    /** 카테고리를 가리키는 세 가지(거래·반복거래·분할)를 모두 대상으로 옮긴다. */
    private ExpenseCategoryServiceDto.MoveResult moveAllReferences(Long sourceCategoryId, ExpenseCategory target) {
        List<Expense> expenses = expenseRepository.findActiveByCategory(sourceCategoryId);
        expenses.forEach(e -> e.changeCategory(target));
        List<RecurringTransaction> recurrings = recurringTransactionRepository.findActiveByCategory(sourceCategoryId);
        recurrings.forEach(r -> r.changeCategory(target));
        List<ExpenseSplit> splits = expenseSplitRepository.findActiveByCategory(sourceCategoryId);
        splits.forEach(sp -> sp.changeCategory(target));
        return new ExpenseCategoryServiceDto.MoveResult(expenses.size(), recurrings.size(), splits.size());
    }

    @Override
    @Transactional
    public ExpenseCategoryServiceDto.MoveResult moveTransactions(Long sourceCategoryId, Long targetCategoryId,
                                                                 Long userRowId) {
        if (Objects.equals(sourceCategoryId, targetCategoryId)) {
            throw new InvalidValueException(DeskErrorCode.INVALID_INPUT);
        }
        ExpenseCategory source = findCategoryOrThrow(sourceCategoryId);
        validateCategoryOwnership(source, userRowId);
        ExpenseCategory target = findCategoryOrThrow(targetCategoryId);
        validateCategoryOwnership(target, userRowId);

        // 거래 유형 == 카테고리 유형 강제 — 지출 거래를 수입 카테고리로 옮기면 집계가 오염된다.
        if (source.getExpenseType() != target.getExpenseType()) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_TYPE_CATEGORY_MISMATCH);
        }
        // 거래는 말단에만 달 수 있다 — 자식을 가진 부모로는 옮길 수 없다.
        if (expenseCategoryRepository.hasChildren(targetCategoryId)) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_NOT_LEAF);
        }

        // 부모 자격을 막는 세 가지를 모두 옮긴다 — 하나라도 남으면 여전히 하위를 만들 수 없다.
        ExpenseCategoryServiceDto.MoveResult moved = moveAllReferences(sourceCategoryId, target);
        log.info("카테고리 일괄 이동: {} → {}, 거래 {}건 / 반복 {}건 / 분할 {}건",
            sourceCategoryId, targetCategoryId, moved.expenses(), moved.recurring(), moved.splits());
        return moved;
    }

    private void validateCanBecomeParent(Long categoryId) {
        if (expenseRepository.existsByCategory(categoryId)
            || recurringTransactionRepository.existsByCategory(categoryId)
            || expenseSplitRepository.existsActiveByCategory(categoryId)) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_PARENT_HAS_TX);
        }
    }

    @Override
    @Transactional
    public void deleteCategory(Long categoryId, Long userRowId) {
        log.debug("지출 카테고리 삭제 시작: categoryId={}", categoryId);

        ExpenseCategory category = findCategoryOrThrow(categoryId);
        validateCategoryOwnership(category, userRowId);

        if (expenseCategoryRepository.hasChildren(categoryId)) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_HAS_CHILDREN);
        }

        // 정책: 예산이 걸린 카테고리를 삭제하면 예산도 함께 삭제(cascade).
        // (클라이언트가 사전 확인 다이얼로그로 사용자 동의를 받는다.)
        List<ExpenseBudget> budgets = expenseBudgetRepository.findAllByCategory(categoryId);
        for (ExpenseBudget budget : budgets) {
            expenseBudgetRepository.delete(budget);
        }

        category.deleteCategory();

        log.info("지출 카테고리 삭제 완료: categoryId={}, 예산 {}건 정리", categoryId, budgets.size());
    }

    @Override
    @Transactional
    public void reorderCategories(Long userRowId, List<ExpenseCategoryServiceDto.ReorderItem> items) {
        log.debug("지출 카테고리 정렬 변경: userRowId={}, count={}", userRowId, items.size());

        for (ExpenseCategoryServiceDto.ReorderItem item : items) {
            ExpenseCategory category = findCategoryOrThrow(item.categoryRowId());
            validateCategoryOwnership(category, userRowId);

            // 부모가 바뀌는 항목만 계층 규칙을 탄다 — 수정과 같은 규칙(resolveParent).
            // 화면의 정렬은 같은 부모 안에서 순서만 바꾸므로 여기 오지 않는다.
            Long newParentRowId = item.parentRowId();
            Long currentParentRowId = category.getParent() != null ? category.getParent().getRowId() : null;
            boolean parentChanged = !java.util.Objects.equals(newParentRowId, currentParentRowId);
            if (parentChanged) {
                ExpenseCategory newParent = resolveParent(
                    category, newParentRowId, category.getExpenseType(), userRowId);
                // 옮겨 갈 자리의 형제 중 같은 이름이 있으면 막는다.
                // 종전엔 이 자리에 검사가 아예 없어 정렬 API 로 중복을 만들 수 있었다 — 등록·수정은
                // 막는데 이동만 뚫려 있던 구멍이다.
                if (expenseCategoryRepository.existsActiveByUserAndParentAndTypeAndName(
                        userRowId, newParentRowId, category.getExpenseType(),
                        category.getCategoryName(), category.getRowId())) {
                    throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_DUPLICATE_NAME);
                }
                category.moveParent(newParent);
            }
            category.updateSortOrder(item.sortOrder());
        }
        // 한 요청 안에서 여러 건이 같은 부모로 몰릴 수 있다 — 위 검사는 앞 항목의 이동까지
        // 보지만(쿼리 직전 자동 flush), 마지막 그물로 UNIQUE 위반도 409 로 받는다.
        flushOrRejectDuplicate();

        log.info("지출 카테고리 정렬 변경 완료: userRowId={}", userRowId);
    }

    /**
     * 더티 체킹으로 나가는 UPDATE 를 지금 내보내고, 활성 이름 UNIQUE 위반을 409 로 바꾼다.
     *
     * <p>수정·이동 경로는 특히 이 flush 가 없으면 못 잡는다 — 위반이 커밋 시점(서비스 메서드가
     * 반환한 뒤, 트랜잭션 인터셉터 안)에 터져 try/catch 가 닿지 않기 때문이다.
     *
     * <p>여기서 재조회하지 않는 이유는 답이 409 라서다(제약 위반 뒤의 세션은 더 못 쓴다).
     * 재조회·재사용으로 수렴해야 하는 자리는 {@link #findOrCreateCategory} 하나이고, 그건
     * 새 트랜잭션으로 돈다.
     */
    private void flushOrRejectDuplicate() {
        try {
            expenseCategoryRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 위반만 이 도메인의 답으로 번역한다. NOT NULL·FK 를 여기서 "이름 중복" 이라고
            // 답하면 값을 빼먹은 요청이 엉뚱한 이유를 듣는다(QA #81) — 그런 위반은 그대로 올려
            // core GlobalExceptionHandler 가 종류대로 답하게 둔다.
            if (!IntegrityViolations.isUnique(e)) throw e;
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_DUPLICATE_NAME, e);
        }
    }

    private void validateCategoryOwnership(ExpenseCategory category, Long userRowId) {
        if (!category.getUser().getRowId().equals(userRowId)) {
            log.warn("지출 카테고리 소유권 검증 실패 - categoryId={}, ownerRowId={}, requestUserRowId={}",
                category.getRowId(), category.getUser().getRowId(), userRowId);
            throw new ForbiddenException(DeskErrorCode.EXPENSE_ACCESS_DENIED);
        }
    }

    private ExpenseCategory findCategoryOrThrow(Long categoryId) {
        return expenseCategoryRepository.findById(categoryId)
            .orElseThrow(() -> {
                log.warn("지출 카테고리 조회 실패 - 존재하지 않는 카테고리: categoryId={}", categoryId);
                return new EntityNotFoundException(DeskErrorCode.EXPENSE_CATEGORY_NOT_FOUND);
            });
    }
}
