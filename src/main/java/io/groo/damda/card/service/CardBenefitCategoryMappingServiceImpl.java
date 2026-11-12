package io.groo.damda.card.service;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.ForbiddenException;
import io.groo.damda.card.domain.CardBenefitCategoryMapping;
import io.groo.damda.card.domain.CardCatalogBenefit;
import io.groo.damda.card.repository.CardBenefitCategoryMappingRepository;
import io.groo.damda.card.repository.CardCatalogBenefitRepository;
import io.groo.damda.card.service.dto.CardBenefitCategoryMappingServiceDto;
import io.groo.damda.card.service.dto.CardCatalogServiceDto;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.expense.domain.ExpenseCategory;
import io.groo.damda.expense.repository.ExpenseCategoryRepository;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class CardBenefitCategoryMappingServiceImpl implements CardBenefitCategoryMappingService {
    private final CardBenefitCategoryMappingRepository mappingRepository;
    private final CardCatalogBenefitRepository cardCatalogBenefitRepository;
    private final UserRepository userRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;

    @Override
    public List<CardBenefitCategoryMappingServiceDto.MappingInfo> getEffectiveMappings(Long userRowId) {
        log.debug("카드 혜택 카테고리 매핑 조회 (effective): userRowId={}", userRowId);
        return mappingRepository.findEffectiveMappings(userRowId).stream()
            .map(CardBenefitCategoryMappingServiceDto.MappingInfo::from)
            .toList();
    }

    @Override
    @Transactional
    public CardBenefitCategoryMappingServiceDto.MappingInfo upsertMapping(CardBenefitCategoryMappingServiceDto.CreateCommand command) {
        log.debug("카드 혜택 매핑 upsert: userRowId={}, benefitCategory={}", command.userRowId(), command.benefitCategory());

        User user = userRepository.findById(command.userRowId())
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));
        ExpenseCategory expenseCategory = expenseCategoryRepository.findById(command.expenseCategoryRowId())
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.EXPENSE_CATEGORY_NOT_FOUND));
        // 남의 카테고리 번호로 매핑하면 응답(expenseCategoryName)으로 그 이름이 읽힌다(보안 점검 DAM-7).
        if (!expenseCategory.getUser().getRowId().equals(command.userRowId())) {
            log.warn("카드 혜택 매핑 카테고리 소유권 검증 실패: categoryId={}, ownerRowId={}, requestUserRowId={}",
                expenseCategory.getRowId(), expenseCategory.getUser().getRowId(), command.userRowId());
            throw new ForbiddenException(DeskErrorCode.CARD_BENEFIT_MAPPING_ACCESS_DENIED);
        }

        // 기존 사용자 커스텀 있으면 update, 없으면 create
        CardBenefitCategoryMapping existing = mappingRepository
            .findUserMapping(command.userRowId(), command.benefitCategory())
            .orElse(null);

        if (existing != null) {
            existing.updateExpenseCategory(expenseCategory);
            return CardBenefitCategoryMappingServiceDto.MappingInfo.from(existing);
        }

        CardBenefitCategoryMapping mapping = CardBenefitCategoryMapping.createUserMapping(
            user, command.benefitCategory(), expenseCategory
        );
        mappingRepository.save(mapping);
        log.info("카드 혜택 매핑 생성 완료: rowId={}", mapping.getRowId());
        return CardBenefitCategoryMappingServiceDto.MappingInfo.from(mapping);
    }

    @Override
    @Transactional
    public void deleteMapping(Long mappingRowId, Long userRowId) {
        log.debug("카드 혜택 매핑 삭제 시작: rowId={}", mappingRowId);

        CardBenefitCategoryMapping mapping = mappingRepository.findById(mappingRowId)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.CARD_BENEFIT_MAPPING_NOT_FOUND));

        if (mapping.getUser() == null) {
            log.warn("공용 기본 매핑은 삭제 불가: rowId={}", mappingRowId);
            throw new ForbiddenException(DeskErrorCode.CARD_BENEFIT_MAPPING_ACCESS_DENIED);
        }
        if (!mapping.getUser().getRowId().equals(userRowId)) {
            log.warn("매핑 소유권 검증 실패: rowId={}, ownerRowId={}, requestUserRowId={}",
                mappingRowId, mapping.getUser().getRowId(), userRowId);
            throw new ForbiddenException(DeskErrorCode.CARD_BENEFIT_MAPPING_ACCESS_DENIED);
        }

        mapping.deleteMapping();
        log.info("카드 혜택 매핑 삭제 완료: rowId={}", mappingRowId);
    }

    @Override
    public List<CardCatalogServiceDto.BenefitInfo> getAvailableBenefits(Long userRowId, Long cardCatalogRowId, Long expenseCategoryRowId) {
        log.debug("적용 가능 혜택 조회: userRowId={}, card={}, expenseCategory={}", userRowId, cardCatalogRowId, expenseCategoryRowId);

        // 사용자 effective 매핑 중 지정 expense_category 에 매핑된 benefit_category 목록
        List<String> matchingBenefitCategories = mappingRepository.findEffectiveMappings(userRowId).stream()
            .filter(m -> m.getExpenseCategory() != null
                && m.getExpenseCategory().getRowId().equals(expenseCategoryRowId))
            .map(CardBenefitCategoryMapping::getBenefitCategory)
            .toList();

        if (matchingBenefitCategories.isEmpty()) {
            return List.of();
        }

        List<CardCatalogBenefit> benefits = cardCatalogBenefitRepository
            .findBenefitsByCardAndCategories(cardCatalogRowId, matchingBenefitCategories);

        return benefits.stream()
            .map(CardCatalogServiceDto.BenefitInfo::from)
            .toList();
    }
}
