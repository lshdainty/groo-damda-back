package io.groo.damda.expense.service;

import io.groo.core.type.YNType;
import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.core.exception.IntegrityViolations;
import io.groo.damda.common.util.NameNormalizer;
import io.groo.damda.common.validation.FieldLimits;
import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.domain.ExpenseCategory;
import io.groo.damda.expense.domain.ExpenseTemplate;
import io.groo.damda.expense.repository.ExpenseCategoryRepository;
import io.groo.damda.expense.repository.ExpenseRepository;
import io.groo.damda.expense.repository.ExpenseTemplateRepository;
import io.groo.damda.expense.service.dto.ExpenseServiceDto;
import io.groo.damda.expense.service.dto.ExpenseTemplateServiceDto;
import io.groo.damda.expense.type.TxKind;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ExpenseTemplateServiceImpl implements ExpenseTemplateService {
    private final ExpenseTemplateRepository expenseTemplateRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final AssetRepository assetRepository;
    private final ExpenseRepository expenseRepository;
    private final UserRepository userRepository;
    private final ReservationRefs reservationRefs;

    @Override
    @Transactional
    public ExpenseTemplateServiceDto.TemplateInfo createTemplate(ExpenseTemplateServiceDto.CreateCommand command) {
        // 저장소를 건드리기 전에 금액부터 가린다 — 잘못된 금액이면 조회 없이 바로 거절한다.
        Long amount = resolveAmount(command.amount(), command.lockAmount());
        String templateName = NameNormalizer.require(command.templateName(), FieldLimits.WIDE_NAME_MAX);
        log.debug("경비 템플릿 생성 시작: userRowId={}, templateName={}", command.userRowId(), templateName);

        // 활성(미삭제) 프리셋 중 같은 이름 금지 (지운 이름은 다시 쓸 수 있다).
        // 종전엔 서버에 검사가 아예 없어 화면 목록 캐시가 비면 그대로 통과했다 —
        // 앱·웹 주석이 "최종 게이트는 서버다" 라고 적어 두고도 그 게이트가 없던 자리다.
        if (expenseTemplateRepository.existsActiveByUserAndName(command.userRowId(), templateName, null)) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_TEMPLATE_DUPLICATE_NAME);
        }

        User user = userRepository.findById(command.userRowId())
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));

        ExpenseCategory category = reservationRefs.resolveOwnedCategory(
            command.expenseType(), command.categoryRowId(), command.userRowId());

        Asset asset = reservationRefs.findOwnedAsset(command.assetRowId(), command.userRowId());
        Asset toAsset = reservationRefs.findOwnedAsset(command.toAssetRowId(), command.userRowId());
        RecurringTransferValidator.validate(command.expenseType(), category, asset, toAsset,
            amount, command.fee(), command.interestAmount());

        ExpenseTemplate template = ExpenseTemplate.createTemplate(
            user, templateName, category, asset,
            toAsset, command.fee(), command.interestAmount(),
            command.expenseType(), amount, command.description(),
            command.merchant(), command.paymentMethod(), command.sortOrder(),
            command.lockAmount()
        );
        template.markFixed(command.isFixed());

        expenseTemplateRepository.save(template);
        flushOrRejectDuplicate();
        log.info("경비 템플릿 생성 완료: templateId={}", template.getRowId());

        return ExpenseTemplateServiceDto.TemplateInfo.from(template);
    }

    @Override
    public List<ExpenseTemplateServiceDto.TemplateInfo> getTemplates(Long userRowId) {
        log.debug("경비 템플릿 목록 조회: userRowId={}", userRowId);

        return expenseTemplateRepository.findByUser(userRowId).stream()
            .map(ExpenseTemplateServiceDto.TemplateInfo::from)
            .toList();
    }

    @Override
    @Transactional
    public ExpenseTemplateServiceDto.TemplateInfo updateTemplate(Long templateId, Long userRowId, ExpenseTemplateServiceDto.UpdateCommand command) {
        log.debug("경비 템플릿 수정 시작: templateId={}", templateId);

        // 등록과 달리 <b>행을 먼저 읽는다</b> — 안 보낸 칸을 지금 값으로 채워야 금액·이름을
        // 검사할 수 있다(QA #96). 그래서 없는 프리셋에 잘못된 값을 보내면 400 이 아니라 404 다.
        ExpenseTemplate template = findTemplateOrThrow(templateId);
        validateTemplateOwnership(template, userRowId);

        // 실린 칸만 바꾼다 — 안 온 칸은 지금 값이 그대로 남는다.
        String templateName = NameNormalizer.require(
            command.templateName().orKeep(template.getTemplateName()), FieldLimits.WIDE_NAME_MAX);
        TxKind expenseType = command.expenseType().orKeep(template.getExpenseType());
        // 금액과 고정 여부는 한 쌍이다 — 둘 다 병합한 뒤에 함께 판정한다. 한쪽만 실린 요청이
        // 나머지 한쪽을 지금 값으로 못 읽으면, 고정을 켜 둔 프리셋의 금액이 조용히 사라진다.
        YNType lockAmount = command.lockAmount().orKeep(template.getLockAmount());
        Long amount = resolveAmount(command.amount().orKeep(template.getAmount()), lockAmount);

        // 자기 자신은 뺀다 — 안 빼면 이름을 그대로 두고 금액만 고치는 저장이 영영 막힌다.
        if (expenseTemplateRepository.existsActiveByUserAndName(userRowId, templateName, templateId)) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_TEMPLATE_DUPLICATE_NAME);
        }

        // 조회가 필요한 칸(카테고리·자산)은 <b>실렸을 때만</b> 찾는다 — 안 보낸 요청이
        // 붙여 둔 카테고리·자산을 떼면 안 되고, 없는 값을 조회하면 404 가 난다.
        ExpenseCategory category = command.categoryRowId()
            .map(rowId -> expenseCategoryRepository.findById(rowId)
                .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.EXPENSE_CATEGORY_NOT_FOUND)))
            .orKeep(template.getCategory());
        // 판정은 <b>병합된 짝</b>으로 한다 — 종전엔 categoryRowId 를 안 보내면 카테고리가
        // 통째로 null 이 되어 검사할 짝이 없었다. 그래서 여기만 "이미 정해진 것" 을 넘긴다.
        reservationRefs.validateCategory(expenseType, category);

        Asset asset = command.assetRowId()
            .map(rowId -> reservationRefs.findOwnedAsset(rowId, userRowId))
            .orKeep(template.getAsset());
        Asset toAsset = command.toAssetRowId()
            .map(rowId -> reservationRefs.findOwnedAsset(rowId, userRowId))
            .orKeep(template.getToAsset());
        Long fee = command.fee().orKeep(template.getFee());
        Long interestAmount = command.interestAmount().orKeep(template.getInterestAmount());
        // 판정은 <b>병합된 값</b>으로 한다 — 종류만 바꾼 요청도 지금 붙어 있는 계좌로 검사된다.
        RecurringTransferValidator.validate(expenseType, category, asset, toAsset,
            amount, fee, interestAmount);

        template.updateTemplate(
            templateName, category, asset,
            toAsset, fee, interestAmount,
            expenseType, amount,
            command.description().orKeep(template.getDescription()),
            command.merchant().orKeep(template.getMerchant()),
            command.paymentMethod().orKeep(template.getPaymentMethod()),
            lockAmount
        );
        // 종류를 정한 뒤에 부른다 — 이체로 바뀌면 꺼진다. 안 보냈으면 유지.
        template.markFixed(command.isFixed().orKeep(template.isFixedTx()));
        flushOrRejectDuplicate();

        log.info("경비 템플릿 수정 완료: templateId={}", templateId);

        return ExpenseTemplateServiceDto.TemplateInfo.from(template);
    }

    @Override
    @Transactional
    public void deleteTemplate(Long templateId, Long userRowId) {
        log.debug("경비 템플릿 삭제 시작: templateId={}", templateId);

        ExpenseTemplate template = findTemplateOrThrow(templateId);
        validateTemplateOwnership(template, userRowId);
        template.deleteTemplate();

        log.info("경비 템플릿 삭제 완료: templateId={}", templateId);
    }

    @Override
    @Transactional
    public ExpenseTemplateServiceDto.TemplateInfo markTemplateUsed(Long templateId, Long userRowId) {
        ExpenseTemplate template = findTemplateOrThrow(templateId);
        validateTemplateOwnership(template, userRowId);
        template.incrementUseCount();
        log.debug("프리셋 사용 마킹: templateId={}, useCount={}", templateId, template.getUseCount());
        return ExpenseTemplateServiceDto.TemplateInfo.from(template);
    }

    @Override
    @Transactional
    public ExpenseServiceDto.ExpenseInfo useTemplate(Long templateId, Long userRowId, LocalDate expenseDate) {
        log.debug("경비 템플릿 사용: templateId={}, expenseDate={}", templateId, expenseDate);

        ExpenseTemplate template = findTemplateOrThrow(templateId);
        validateTemplateOwnership(template, userRowId);

        // 이체 프리셋은 여기로 오지 않는다 — 이 엔드포인트는 지출 1건을 만드는 자리다.
        // 웹·앱 모두 프리셋을 <b>폼에 채워</b> 각자의 저장(이체면 /asset-transfer)으로 보내고
        // 사용 기록만 /touch 로 올린다. 여기서 이체를 흉내 내면 같은 일을 하는 길이 둘이 된다.
        if (template.getExpenseType().isTransfer()) {
            throw new InvalidValueException(DeskErrorCode.INVALID_INPUT);
        }

        // 정책: 템플릿 생성 이후 카테고리가 상위(부모)가 됐다면 거래 생성 불가.
        if (template.getCategory() != null
            && expenseCategoryRepository.hasChildren(template.getCategory().getRowId())) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_CATEGORY_NOT_LEAF);
        }

        Expense expense = Expense.createExpense(
            template.getUser(),
            template.getCategory(),
            template.getAsset(),
            template.getExpenseType().toExpenseType(),
            template.getAmount(),
            template.getDescription(),
            // 템플릿은 LocalDate 만 받으므로 00:00 으로 보정하여 엔티티(LocalDateTime) 에 전달
            expenseDate.atStartOfDay(),
            template.getMerchant(),
            template.getPaymentMethod(),
            null, // 프리셋은 할부 개념이 없다
            null, null, null // 원화 결제
        );
        expense.markFixed(template.isFixedTx());

        expenseRepository.save(expense);
        template.incrementUseCount();

        log.info("경비 템플릿 사용 완료: templateId={}, expenseId={}", templateId, expense.getRowId());

        return ExpenseServiceDto.ExpenseInfo.from(expense);
    }

    private void validateTemplateOwnership(ExpenseTemplate template, Long userRowId) {
        if (!template.getUser().getRowId().equals(userRowId)) {
            log.warn("경비 템플릿 소유권 검증 실패 - templateId={}, ownerRowId={}, requestUserRowId={}",
                template.getRowId(), template.getUser().getRowId(), userRowId);
            throw new ForbiddenException(DeskErrorCode.EXPENSE_ACCESS_DENIED);
        }
    }

    /**
     * 조회 검사를 빠져나간 동시 저장 경쟁을 409 로 받는다.
     *
     * <p>flush 를 명시하지 않으면 위반이 커밋 시점에 터져 try/catch 가 닿지 않는다 —
     * 수정은 더티 체킹이라 UPDATE 자체가 커밋 때 나가므로 특히 그렇다.
     */
    private void flushOrRejectDuplicate() {
        try {
            expenseTemplateRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 위반만 이 도메인의 답으로 번역한다. NOT NULL·FK 를 여기서 "이름 중복" 이라고
            // 답하면 값을 빼먹은 요청이 엉뚱한 이유를 듣는다(QA #81) — 그런 위반은 그대로 올려
            // core GlobalExceptionHandler 가 종류대로 답하게 둔다.
            if (!IntegrityViolations.isUnique(e)) throw e;
            throw new InvalidValueException(DeskErrorCode.EXPENSE_TEMPLATE_DUPLICATE_NAME, e);
        }
    }

    private ExpenseTemplate findTemplateOrThrow(Long templateId) {
        return expenseTemplateRepository.findById(templateId)
            .orElseThrow(() -> {
                log.warn("경비 템플릿 조회 실패 - 존재하지 않는 템플릿: templateId={}", templateId);
                return new EntityNotFoundException(DeskErrorCode.EXPENSE_TEMPLATE_NOT_FOUND);
            });
    }

    /**
     * 프리셋 금액 — 고정 금액을 켰을 때만 존재한다.
     *
     * <p>프리셋은 <b>금액을 모르는 채로 양식만</b> 저장하려고 만든 것이다. 매번 금액이 다른
     * 항목(변동 구독료·병원비)은 불러올 때 금액칸이 비어 있어야 편하다.
     *
     * <p>둘 중 하나다 — 고정이면 금액이 있고, 아니면 금액이 없다. 중간 상태를 두지 않는다.
     * 고정을 끈 채로 들어온 금액은 <b>버린다</b>. 남겨 두면 화면엔 안 보이는 값이 붙어 다니다
     * 나중에 고정을 켜는 순간 엉뚱한 금액이 살아난다.
     *
     * @return 저장할 금액 — 고정이 아니면 항상 null
     */
    private Long resolveAmount(Long amount, YNType lockAmount) {
        if (lockAmount != YNType.Y) {
            return null;
        }
        if (amount == null || amount <= 0) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_INVALID_AMOUNT);
        }
        return amount;
    }
}
