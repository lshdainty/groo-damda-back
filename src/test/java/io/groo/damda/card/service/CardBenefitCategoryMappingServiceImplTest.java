package io.groo.damda.card.service;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.ForbiddenException;
import io.groo.core.type.YNType;
import io.groo.damda.card.domain.CardBenefitCategoryMapping;
import io.groo.damda.card.domain.CardCatalogBenefit;
import io.groo.damda.card.repository.CardBenefitCategoryMappingRepository;
import io.groo.damda.card.repository.CardCatalogBenefitRepository;
import io.groo.damda.card.service.dto.CardBenefitCategoryMappingServiceDto;
import io.groo.damda.card.service.dto.CardCatalogServiceDto;
import io.groo.damda.expense.domain.ExpenseCategory;
import io.groo.damda.expense.repository.ExpenseCategoryRepository;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 카드 혜택-카테고리 매핑 서비스 단위 테스트.
 *
 * <p>repository 는 모두 mock — {@link CardBenefitCategoryMappingServiceImpl} 의 effective 매핑 매핑,
 * upsert 분기(생성/수정), 소유권 가드(공용/타인 매핑 삭제 금지), 적용 가능 혜택 필터링만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class CardBenefitCategoryMappingServiceImplTest {

    @Mock private CardBenefitCategoryMappingRepository mappingRepository;
    @Mock private CardCatalogBenefitRepository cardCatalogBenefitRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExpenseCategoryRepository expenseCategoryRepository;

    @InjectMocks private CardBenefitCategoryMappingServiceImpl sut;

    private static final long USER_ID = 1L;

    private User user(long rowId) {
        User u = User.createUser(null, "tester", "테스터", "tester@porest.com");
        ReflectionTestUtils.setField(u, "rowId", rowId);
        return u;
    }

    private ExpenseCategory expenseCategory(long rowId, String name) {
        ExpenseCategory c = ExpenseCategory.createCategory(user(USER_ID), name, "utensils", "#fff", ExpenseType.EXPENSE, null);
        ReflectionTestUtils.setField(c, "rowId", rowId);
        return c;
    }

    @Nested
    @DisplayName("getEffectiveMappings")
    class GetEffectiveMappings {

        @Test
        @DisplayName("공용/커스텀 매핑을 MappingInfo 로 변환하고 isCustom 을 구분한다")
        void mapsAndFlagsCustom() {
            CardBenefitCategoryMapping defaultMapping = mock(CardBenefitCategoryMapping.class);
            given(defaultMapping.getUser()).willReturn(null);
            given(defaultMapping.getRowId()).willReturn(100L);
            given(defaultMapping.getBenefitCategory()).willReturn("DINING");
            given(defaultMapping.getExpenseCategory()).willReturn(null);

            ExpenseCategory cat = expenseCategory(5L, "식비");
            CardBenefitCategoryMapping customMapping = mock(CardBenefitCategoryMapping.class);
            given(customMapping.getUser()).willReturn(user(USER_ID));
            given(customMapping.getRowId()).willReturn(101L);
            given(customMapping.getBenefitCategory()).willReturn("DINING");
            given(customMapping.getExpenseCategory()).willReturn(cat);

            given(mappingRepository.findEffectiveMappings(USER_ID))
                    .willReturn(List.of(defaultMapping, customMapping));

            var result = sut.getEffectiveMappings(USER_ID);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).isCustom()).isFalse();
            assertThat(result.get(0).expenseCategoryRowId()).isNull();
            assertThat(result.get(0).expenseCategoryName()).isNull();
            assertThat(result.get(1).isCustom()).isTrue();
            assertThat(result.get(1).expenseCategoryRowId()).isEqualTo(5L);
            assertThat(result.get(1).expenseCategoryName()).isEqualTo("식비");
        }

        @Test
        @DisplayName("매핑이 없으면 빈 목록을 반환한다")
        void returnsEmpty() {
            given(mappingRepository.findEffectiveMappings(USER_ID)).willReturn(List.of());

            assertThat(sut.getEffectiveMappings(USER_ID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("upsertMapping")
    class UpsertMapping {

        @Test
        @DisplayName("남의 카테고리 번호로는 매핑할 수 없다 — 응답으로 남의 카테고리 이름이 새던 자리(보안 점검 DAM-7)")
        void rejectsOthersCategory() {
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));
            ExpenseCategory others = ExpenseCategory.createCategory(user(999L), "남의 비상금", "x", "#000", ExpenseType.EXPENSE, null);
            ReflectionTestUtils.setField(others, "rowId", 77L);
            given(expenseCategoryRepository.findById(77L)).willReturn(Optional.of(others));

            assertThatThrownBy(() -> sut.upsertMapping(
                    new CardBenefitCategoryMappingServiceDto.CreateCommand(USER_ID, "DINING", 77L)))
                    .isInstanceOf(ForbiddenException.class);
            verify(mappingRepository, never()).save(any());
        }

        @Test
        @DisplayName("기존 커스텀 매핑이 없으면 새로 생성하고 저장한다")
        void createsWhenNoExisting() {
            User u = user(USER_ID);
            ExpenseCategory cat = expenseCategory(5L, "식비");
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
            given(expenseCategoryRepository.findById(5L)).willReturn(Optional.of(cat));
            given(mappingRepository.findUserMapping(USER_ID, "DINING")).willReturn(Optional.empty());

            var command = new CardBenefitCategoryMappingServiceDto.CreateCommand(USER_ID, "DINING", 5L);
            var info = sut.upsertMapping(command);

            assertThat(info.benefitCategory()).isEqualTo("DINING");
            assertThat(info.expenseCategoryRowId()).isEqualTo(5L);
            assertThat(info.expenseCategoryName()).isEqualTo("식비");
            assertThat(info.isCustom()).isTrue();
            verify(mappingRepository).save(any(CardBenefitCategoryMapping.class));
        }

        @Test
        @DisplayName("기존 커스텀 매핑이 있으면 카테고리만 갱신하고 저장하지 않는다")
        void updatesWhenExisting() {
            User u = user(USER_ID);
            ExpenseCategory oldCat = expenseCategory(5L, "식비");
            ExpenseCategory newCat = expenseCategory(6L, "카페");
            CardBenefitCategoryMapping existing = CardBenefitCategoryMapping.createUserMapping(u, "DINING", oldCat);
            ReflectionTestUtils.setField(existing, "rowId", 200L);

            given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
            given(expenseCategoryRepository.findById(6L)).willReturn(Optional.of(newCat));
            given(mappingRepository.findUserMapping(USER_ID, "DINING")).willReturn(Optional.of(existing));

            var command = new CardBenefitCategoryMappingServiceDto.CreateCommand(USER_ID, "DINING", 6L);
            var info = sut.upsertMapping(command);

            assertThat(info.rowId()).isEqualTo(200L);
            assertThat(info.expenseCategoryRowId()).isEqualTo(6L);
            assertThat(info.expenseCategoryName()).isEqualTo("카페");
            assertThat(existing.getExpenseCategory()).isEqualTo(newCat);
            verify(mappingRepository, never()).save(any());
        }

        @Test
        @DisplayName("존재하지 않는 사용자 — EntityNotFoundException, 저장 안 함")
        void throwsWhenUserNotFound() {
            given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

            var command = new CardBenefitCategoryMappingServiceDto.CreateCommand(USER_ID, "DINING", 5L);
            assertThatThrownBy(() -> sut.upsertMapping(command))
                    .isInstanceOf(EntityNotFoundException.class);
            verify(mappingRepository, never()).save(any());
        }

        @Test
        @DisplayName("존재하지 않는 지출 카테고리 — EntityNotFoundException, 저장 안 함")
        void throwsWhenCategoryNotFound() {
            User u = user(USER_ID);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
            given(expenseCategoryRepository.findById(5L)).willReturn(Optional.empty());

            var command = new CardBenefitCategoryMappingServiceDto.CreateCommand(USER_ID, "DINING", 5L);
            assertThatThrownBy(() -> sut.upsertMapping(command))
                    .isInstanceOf(EntityNotFoundException.class);
            verify(mappingRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("deleteMapping")
    class DeleteMapping {

        @Test
        @DisplayName("소유자는 자신의 매핑을 소프트 삭제한다")
        void ownerSoftDeletes() {
            CardBenefitCategoryMapping mapping =
                    CardBenefitCategoryMapping.createUserMapping(user(USER_ID), "DINING", expenseCategory(5L, "식비"));
            ReflectionTestUtils.setField(mapping, "rowId", 300L);
            given(mappingRepository.findById(300L)).willReturn(Optional.of(mapping));

            sut.deleteMapping(300L, USER_ID);

            assertThat(mapping.getIsDeleted()).isEqualTo(YNType.Y);
        }

        @Test
        @DisplayName("존재하지 않는 매핑 — EntityNotFoundException")
        void throwsWhenNotFound() {
            given(mappingRepository.findById(300L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> sut.deleteMapping(300L, USER_ID))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("공용 기본 매핑(user=null)은 삭제 불가 — ForbiddenException")
        void rejectsDefaultMapping() {
            CardBenefitCategoryMapping defaultMapping =
                    CardBenefitCategoryMapping.createUserMapping(null, "DINING", expenseCategory(5L, "식비"));
            ReflectionTestUtils.setField(defaultMapping, "rowId", 300L);
            given(mappingRepository.findById(300L)).willReturn(Optional.of(defaultMapping));

            assertThatThrownBy(() -> sut.deleteMapping(300L, USER_ID))
                    .isInstanceOf(ForbiddenException.class);
            assertThat(defaultMapping.getIsDeleted()).isEqualTo(YNType.N);
        }

        @Test
        @DisplayName("타 사용자의 매핑은 삭제 불가 — ForbiddenException")
        void rejectsOthersMapping() {
            CardBenefitCategoryMapping mapping =
                    CardBenefitCategoryMapping.createUserMapping(user(999L), "DINING", expenseCategory(5L, "식비"));
            ReflectionTestUtils.setField(mapping, "rowId", 300L);
            given(mappingRepository.findById(300L)).willReturn(Optional.of(mapping));

            assertThatThrownBy(() -> sut.deleteMapping(300L, USER_ID))
                    .isInstanceOf(ForbiddenException.class);
            assertThat(mapping.getIsDeleted()).isEqualTo(YNType.N);
        }
    }

    @Nested
    @DisplayName("getAvailableBenefits")
    class GetAvailableBenefits {

        @Test
        @DisplayName("지정 지출 카테고리에 매핑된 benefit_category 로만 혜택을 조회한다")
        void filtersByExpenseCategory() {
            long cardId = 1L;
            long expCatId = 5L;

            CardBenefitCategoryMapping matching = mock(CardBenefitCategoryMapping.class);
            given(matching.getExpenseCategory()).willReturn(expenseCategory(5L, "식비"));
            given(matching.getBenefitCategory()).willReturn("DINING");
            CardBenefitCategoryMapping nonMatching = mock(CardBenefitCategoryMapping.class);
            given(nonMatching.getExpenseCategory()).willReturn(expenseCategory(99L, "교통"));
            CardBenefitCategoryMapping nullCategory = mock(CardBenefitCategoryMapping.class);
            given(nullCategory.getExpenseCategory()).willReturn(null);

            given(mappingRepository.findEffectiveMappings(USER_ID))
                    .willReturn(List.of(matching, nonMatching, nullCategory));

            CardCatalogBenefit foundBenefit = mock(CardCatalogBenefit.class);
            given(foundBenefit.getRowId()).willReturn(10L);
            given(cardCatalogBenefitRepository.findBenefitsByCardAndCategories(cardId, List.of("DINING")))
                    .willReturn(List.of(foundBenefit));

            List<CardCatalogServiceDto.BenefitInfo> result = sut.getAvailableBenefits(USER_ID, cardId, expCatId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).rowId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("매칭되는 benefit_category 가 없으면 조회 없이 빈 목록을 반환한다")
        void returnsEmptyWithoutQueryingWhenNoMatch() {
            long cardId = 1L;
            long expCatId = 5L;

            CardBenefitCategoryMapping nonMatching = mock(CardBenefitCategoryMapping.class);
            given(nonMatching.getExpenseCategory()).willReturn(expenseCategory(99L, "교통"));
            given(mappingRepository.findEffectiveMappings(USER_ID)).willReturn(List.of(nonMatching));

            List<CardCatalogServiceDto.BenefitInfo> result = sut.getAvailableBenefits(USER_ID, cardId, expCatId);

            assertThat(result).isEmpty();
            verify(cardCatalogBenefitRepository, never()).findBenefitsByCardAndCategories(any(), any());
        }
    }
}
