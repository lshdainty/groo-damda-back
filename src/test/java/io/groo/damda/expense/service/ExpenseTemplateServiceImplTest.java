package io.groo.damda.expense.service;

import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.expense.domain.ExpenseCategory;
import io.groo.core.type.YNType;
import io.groo.damda.common.patch.Patch;
import io.groo.damda.expense.domain.ExpenseTemplate;
import io.groo.damda.expense.repository.ExpenseCategoryRepository;
import io.groo.damda.expense.repository.ExpenseRepository;
import io.groo.damda.expense.repository.ExpenseTemplateRepository;
import io.groo.damda.expense.service.dto.ExpenseServiceDto;
import io.groo.damda.expense.service.dto.ExpenseTemplateServiceDto;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.expense.type.TxKind;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import io.groo.damda.support.exception.ConstraintViolations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.common.exception.DeskErrorCode;
import org.junit.jupiter.api.Nested;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 경비 템플릿 정책 회귀 방지 단위 테스트 — 거래와 동일하게 leaf 카테고리만, 소유권 검증.
 * useTemplate 는 생성 이후 카테고리가 부모가 된 경우도 거래 생성을 막는다.
 */
@ExtendWith(MockitoExtension.class)
class ExpenseTemplateServiceImplTest {

    @Mock private ExpenseTemplateRepository expenseTemplateRepository;
    @Mock private ExpenseCategoryRepository expenseCategoryRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private ExpenseRepository expenseRepository;
    @Mock private UserRepository userRepository;

    /**
     * 참조 해석(카테고리·자산)은 <b>진짜</b>를 준다.
     *
     * <p>목으로 바꾸면 "남의 카테고리 거절"·"상위 카테고리 거절" 같은 단언이 스텁을 확인하는
     * 셈이 되어 아무것도 안 지킨다. 진짜에 리포지토리 목을 물리면 기존 스텁과 단언이 그대로
     * 뜻을 갖는다 — 규칙이 다른 클래스로 옮겨졌을 뿐이다.
     */
    private ExpenseTemplateServiceImpl sut;

    @BeforeEach
    void buildSut() {
        sut = new ExpenseTemplateServiceImpl(
            expenseTemplateRepository, expenseCategoryRepository, assetRepository,
            expenseRepository, userRepository,
            new ReservationRefs(expenseCategoryRepository, assetRepository));
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

    private ExpenseTemplateServiceDto.CreateCommand createCmd(long categoryRowId) {
        return new ExpenseTemplateServiceDto.CreateCommand(
                USER_ID, "점심 템플릿", categoryRowId, null, null, null, null, TxKind.EXPENSE, 10_000L,
                null, null, null, null, null);
    }

    private ExpenseTemplateServiceDto.UpdateCommand updateCmd(long categoryRowId) {
        return new ExpenseTemplateServiceDto.UpdateCommand(
                Patch.set("점심 템플릿"), Patch.set(categoryRowId), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.set(TxKind.EXPENSE), Patch.set(10_000L),
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent());
    }

    @Test
    @DisplayName("createTemplate — 고정 금액을 안 쓰면 금액 없이도 저장된다")
    void createAllowsBlankAmountWhenNotLocked() {
        // 프리셋은 금액을 모르는 채로 양식만 저장하려고 만든 것이다.
        // 매번 금액이 다른 항목(구독료 변동·병원비 등)은 불러올 때 비어 있어야 편하다.
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        given(expenseTemplateRepository.save(any(ExpenseTemplate.class)))
            .willAnswer(inv -> inv.getArgument(0));

        var cmd = new ExpenseTemplateServiceDto.CreateCommand(
                USER_ID, "구독", 10L, null, null, null, null, TxKind.EXPENSE, null,
                null, null, null, null, YNType.N);

        assertThatCode(() -> sut.createTemplate(cmd)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("createTemplate — 고정 금액을 쓰면 금액이 있어야 한다")
    void createRequiresAmountWhenLocked() {
        // 고정 금액은 불러오는 거래가 그 값을 그대로 받는다 — 비어 있으면 의미가 없다.
        // 금액 검증이 사용자 조회보다 먼저라 저장소를 건드리지 않는다.
        var cmd = new ExpenseTemplateServiceDto.CreateCommand(
                USER_ID, "점심", 10L, null, null, null, null, TxKind.EXPENSE, null,
                null, null, null, null, YNType.Y);

        assertThatThrownBy(() -> sut.createTemplate(cmd))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("createTemplate — 고정 금액이 꺼져 있으면 적어 넣은 금액은 버린다")
    void createDropsAmountWhenNotLocked() {
        // 둘 중 하나다 — 고정이면 금액이 있고, 아니면 없다. 중간 상태를 남기면
        // 화면엔 안 보이는 값이 붙어 다니다 고정을 켜는 순간 엉뚱한 금액이 살아난다.
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        given(expenseTemplateRepository.save(any(ExpenseTemplate.class)))
            .willAnswer(inv -> inv.getArgument(0));

        var cmd = new ExpenseTemplateServiceDto.CreateCommand(
                USER_ID, "구독", 10L, null, null, null, null, TxKind.EXPENSE, 10_000L,
                null, null, null, null, YNType.N);

        var info = sut.createTemplate(cmd);

        assertThat(info.amount()).isNull();
    }

    @Test
    @DisplayName("createTemplate — 고정 금액을 켰는데 0 이면 거부한다")
    void createRejectsZeroWhenLocked() {
        var cmd = new ExpenseTemplateServiceDto.CreateCommand(
                USER_ID, "점심", 10L, null, null, null, null, TxKind.EXPENSE, 0L,
                null, null, null, null, YNType.Y);

        assertThatThrownBy(() -> sut.createTemplate(cmd))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("createTemplate — 자식 보유(상위) 카테고리에는 템플릿 불가")
    void createRejectsNonLeafCategory() {
        User u = user(USER_ID);
        ExpenseCategory parent = category(10L, u);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(parent));
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(true);

        assertThatThrownBy(() -> sut.createTemplate(createCmd(10L)))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("createTemplate — 남의 카테고리에는 템플릿 불가")
    void createRejectsOthersCategory() {
        User u = user(USER_ID);
        ExpenseCategory othersCategory = category(20L, user(999L));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
        given(expenseCategoryRepository.findById(20L)).willReturn(Optional.of(othersCategory));

        assertThatThrownBy(() -> sut.createTemplate(createCmd(20L)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("updateTemplate — 자식 보유(상위) 카테고리로 변경 불가")
    void updateRejectsNonLeafCategory() {
        User u = user(USER_ID);
        ExpenseTemplate template = mock(ExpenseTemplate.class);
        given(template.getUser()).willReturn(u);
        ExpenseCategory parent = category(30L, u);
        given(expenseTemplateRepository.findById(5L)).willReturn(Optional.of(template));
        given(expenseCategoryRepository.findById(30L)).willReturn(Optional.of(parent));
        given(expenseCategoryRepository.hasChildren(30L)).willReturn(true);

        assertThatThrownBy(() -> sut.updateTemplate(5L, USER_ID, updateCmd(30L)))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("updateTemplate — 남의 카테고리로 바꿀 수 없다(응답으로 남의 카테고리 이름이 새던 자리, 보안 점검 DAM-7)")
    void updateRejectsOthersCategory() {
        ExpenseTemplate template = mock(ExpenseTemplate.class);
        given(template.getUser()).willReturn(user(USER_ID));
        given(expenseTemplateRepository.findById(5L)).willReturn(Optional.of(template));
        given(expenseCategoryRepository.findById(40L)).willReturn(Optional.of(category(40L, user(999L))));

        assertThatThrownBy(() -> sut.updateTemplate(5L, USER_ID, updateCmd(40L)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("updateTemplate — 남의 템플릿은 수정 불가")
    void updateRejectsOthersTemplate() {
        ExpenseTemplate template = mock(ExpenseTemplate.class);
        given(template.getUser()).willReturn(user(999L));
        given(expenseTemplateRepository.findById(5L)).willReturn(Optional.of(template));

        assertThatThrownBy(() -> sut.updateTemplate(5L, USER_ID, updateCmd(30L)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("updateTemplate — 남의 자산으로 변경 불가(소유권 검증 누락 보강)")
    void updateRejectsOthersAsset() {
        User u = user(USER_ID);
        ExpenseTemplate template = mock(ExpenseTemplate.class);
        given(template.getUser()).willReturn(u);
        given(expenseTemplateRepository.findById(5L)).willReturn(Optional.of(template));
        Asset othersAsset = mock(Asset.class);
        given(othersAsset.getUser()).willReturn(user(999L));
        given(assetRepository.findById(20L)).willReturn(Optional.of(othersAsset));

        var cmd = new ExpenseTemplateServiceDto.UpdateCommand(
                Patch.set("점심 템플릿"), Patch.absent(), Patch.set(20L),
                Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.set(TxKind.EXPENSE), Patch.set(10_000L),
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent());

        assertThatThrownBy(() -> sut.updateTemplate(5L, USER_ID, cmd))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("useTemplate — 카테고리가 사후에 부모가 됐으면 거래 생성 불가")
    void useTemplateRejectsNonLeafCategory() {
        User u = user(USER_ID);
        ExpenseCategory nowParent = category(40L, u);
        ExpenseTemplate template = mock(ExpenseTemplate.class);
        given(template.getUser()).willReturn(u);
        given(template.getExpenseType()).willReturn(TxKind.EXPENSE);
        given(template.getCategory()).willReturn(nowParent);
        given(expenseTemplateRepository.findById(5L)).willReturn(Optional.of(template));
        given(expenseCategoryRepository.hasChildren(40L)).willReturn(true);

        assertThatThrownBy(() -> sut.useTemplate(5L, USER_ID, LocalDate.of(2026, 6, 1)))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    @DisplayName("useTemplate — 성공 시 템플릿 값으로 거래 생성 + 사용횟수 증가")
    void useTemplateCreatesExpenseAndIncrementsUseCount() {
        User u = user(USER_ID);
        ExpenseCategory leaf = category(10L, u);
        ExpenseTemplate template = mock(ExpenseTemplate.class);
        given(template.getUser()).willReturn(u);
        given(template.getCategory()).willReturn(leaf);
        given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
        given(template.getAsset()).willReturn(null);
        given(template.getExpenseType()).willReturn(TxKind.EXPENSE);
        given(template.getAmount()).willReturn(15_000L);
        given(template.getDescription()).willReturn("점심");
        given(template.getMerchant()).willReturn("식당");
        given(template.getPaymentMethod()).willReturn("CARD");
        given(expenseTemplateRepository.findById(5L)).willReturn(Optional.of(template));

        ExpenseServiceDto.ExpenseInfo info = sut.useTemplate(5L, USER_ID, LocalDate.of(2026, 7, 1));

        assertThat(info.amount()).isEqualTo(15_000L);
        assertThat(info.description()).isEqualTo("점심");
        verify(expenseRepository).save(org.mockito.ArgumentMatchers.any());
        verify(template).incrementUseCount();
    }

    @Test
    @DisplayName("useTemplate — 고정 프리셋으로 만든 거래는 고정이고, 아니면 변동이다")
    void useTemplateInheritsFixed() {
        for (boolean fixed : new boolean[]{true, false}) {
            org.mockito.Mockito.clearInvocations(expenseRepository);
            User u = user(USER_ID);
            ExpenseCategory leaf = category(10L, u);
            ExpenseTemplate template = mock(ExpenseTemplate.class);
            given(template.getUser()).willReturn(u);
            given(template.getCategory()).willReturn(leaf);
            given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
            given(template.getAsset()).willReturn(null);
            given(template.getExpenseType()).willReturn(TxKind.EXPENSE);
            given(template.getAmount()).willReturn(700_000L);
            given(template.isFixedTx()).willReturn(fixed);
            given(expenseTemplateRepository.findById(5L)).willReturn(Optional.of(template));

            sut.useTemplate(5L, USER_ID, LocalDate.of(2026, 7, 1));

            org.mockito.ArgumentCaptor<io.groo.damda.expense.domain.Expense> captor =
                org.mockito.ArgumentCaptor.forClass(io.groo.damda.expense.domain.Expense.class);
            verify(expenseRepository).save(captor.capture());
            assertThat(captor.getValue().isFixedTx()).isEqualTo(fixed);
        }
    }

    @Nested
    @DisplayName("금액 부호 — 음수 프리셋이 저장되면 그걸 불러 쓰는 거래도 오염된다")
    class AmountSign {

        @Test
        @DisplayName("음수 금액 프리셋을 만들 수 없다")
        void rejectsNegative() {
            var cmd = new ExpenseTemplateServiceDto.CreateCommand(
                USER_ID, "잘못된 프리셋", 1L, null, null, null, null, TxKind.EXPENSE, -10_000L,
                null, null, null, null, YNType.Y);
            assertThatThrownBy(() -> sut.createTemplate(cmd))
                .isInstanceOf(InvalidValueException.class);
        }

        @Test
        @DisplayName("수정에서도 음수를 막는다")
        void rejectsNegativeOnUpdate() {
            // 금액 검사가 조회보다 뒤로 갔다 — 안 보낸 칸을 지금 값으로 채워야 "고정 금액인가" 를
            // 판정할 수 있어서다(QA #96). 그래서 이 테스트는 프리셋이 실재해야 한다.
            User u = user(USER_ID);
            ExpenseTemplate t = ExpenseTemplate.createTemplate(
                u, "프리셋", null, null, null, null, null, TxKind.EXPENSE, 10_000L,
                null, null, null, null, YNType.Y);
            given(expenseTemplateRepository.findById(1L)).willReturn(Optional.of(t));

            var cmd = new ExpenseTemplateServiceDto.UpdateCommand(
                Patch.set("프리셋"), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.set(TxKind.EXPENSE), Patch.set(-5_000L),
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.set(YNType.Y));
            assertThatThrownBy(() -> sut.updateTemplate(1L, USER_ID, cmd))
                .isInstanceOf(InvalidValueException.class);
        }
    }

    @Nested
    @DisplayName("이름 중복 — 서버가 마지막 게이트다")
    class DuplicateName {

        @Test
        @DisplayName("createTemplate — 활성 프리셋 중 같은 이름이 있으면 409(중복 이름)")
        void createRejectsDuplicateActiveName() {
            given(expenseTemplateRepository.existsActiveByUserAndName(USER_ID, "점심 템플릿", null))
                    .willReturn(true);

            assertThatThrownBy(() -> sut.createTemplate(createCmd(10L)))
                    .isInstanceOf(InvalidValueException.class)
                    .extracting(e -> ((InvalidValueException) e).getErrorCode())
                    .isEqualTo(DeskErrorCode.EXPENSE_TEMPLATE_DUPLICATE_NAME);
            verify(expenseTemplateRepository, never()).save(any());
        }

        @Test
        @DisplayName("createTemplate — 이름 앞뒤 공백은 저장 전에 잘린다")
        void createTrimsName() {
            User u = user(USER_ID);
            ExpenseCategory leaf = category(10L, u);
            given(expenseTemplateRepository.existsActiveByUserAndName(USER_ID, "점심 템플릿", null))
                    .willReturn(false);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
            given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
            given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
            given(expenseTemplateRepository.save(any(ExpenseTemplate.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            var info = sut.createTemplate(new ExpenseTemplateServiceDto.CreateCommand(
                    USER_ID, "  점심 템플릿 ", 10L, null, null, null, null, TxKind.EXPENSE, 10_000L,
                    null, null, null, null, null));

            assertThat(info.templateName()).isEqualTo("점심 템플릿");
        }

        @Test
        @DisplayName("updateTemplate — 자기 자신은 중복 검사에서 뺀다(이름을 그대로 두는 저장이 막히면 안 된다)")
        void updateExcludesSelf() {
            User u = user(USER_ID);
            ExpenseTemplate t = ExpenseTemplate.createTemplate(
                    u, "점심 템플릿", null, null, null, null, null, TxKind.EXPENSE, 10_000L,
                    null, null, null, null, YNType.N);
            given(expenseTemplateRepository.findById(5L)).willReturn(Optional.of(t));
            given(expenseTemplateRepository.existsActiveByUserAndName(USER_ID, "점심 템플릿", 5L))
                    .willReturn(false);

            assertThatCode(() -> sut.updateTemplate(5L, USER_ID,
                    new ExpenseTemplateServiceDto.UpdateCommand(
                            Patch.set("점심 템플릿"), Patch.absent(), Patch.absent(),
                            Patch.absent(), Patch.absent(), Patch.absent(),
                            Patch.set(TxKind.EXPENSE), Patch.set(10_000L),
                            Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent())))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("updateTemplate — 다른 프리셋과 이름이 겹치면 409")
        void updateRejectsDuplicateActiveName() {
            User u = user(USER_ID);
            ExpenseTemplate t = ExpenseTemplate.createTemplate(
                    u, "저녁 템플릿", null, null, null, null, null, TxKind.EXPENSE, 10_000L,
                    null, null, null, null, YNType.N);
            given(expenseTemplateRepository.findById(5L)).willReturn(Optional.of(t));
            given(expenseTemplateRepository.existsActiveByUserAndName(USER_ID, "점심 템플릿", 5L))
                    .willReturn(true);

            assertThatThrownBy(() -> sut.updateTemplate(5L, USER_ID,
                    new ExpenseTemplateServiceDto.UpdateCommand(
                            Patch.set("점심 템플릿"), Patch.absent(), Patch.absent(),
                            Patch.absent(), Patch.absent(), Patch.absent(),
                            Patch.set(TxKind.EXPENSE), Patch.set(10_000L),
                            Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent())))
                    .isInstanceOf(InvalidValueException.class);
        }

        @Test
        @DisplayName("유니크 위반(동시 저장 경쟁)은 500 이 아니라 409 로 나간다")
        void translatesConstraintViolation() {
            User u = user(USER_ID);
            ExpenseCategory leaf = category(10L, u);
            given(expenseTemplateRepository.existsActiveByUserAndName(USER_ID, "점심 템플릿", null))
                    .willReturn(false);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
            given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
            given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
            given(expenseTemplateRepository.save(any(ExpenseTemplate.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            willThrow(ConstraintViolations.unique("UK_expense_template_active_name"))
                    .given(expenseTemplateRepository).flush();

            assertThatThrownBy(() -> sut.createTemplate(createCmd(10L)))
                    .isInstanceOf(InvalidValueException.class)
                    .extracting(e -> ((InvalidValueException) e).getErrorCode())
                    .isEqualTo(DeskErrorCode.EXPENSE_TEMPLATE_DUPLICATE_NAME);
        }
        /**
         * QA #81 — <b>UNIQUE 가 아닌 위반은 이 도메인이 손대지 않는다.</b>
         *
         * <p>종전엔 여기서 {@code DataIntegrityViolationException} 을 종류와 무관하게 전부
         * "이름이 중복돼요" 로 번역했다. 그러면 값을 하나 빼먹고 보낸 요청이 <b>있지도 않은
         * 중복</b>을 이유로 거절당한다. 그런 위반은 그대로 올려 공통 핸들러가 400 으로 답하게 둔다.
         *
         * <p>되돌려 보는 법(네거티브 컨트롤): 서비스의
         * {@code if (!IntegrityViolations.isUnique(e)) throw e;} 한 줄을 지우면 곧바로 깨진다.
         */
        @Test
        @DisplayName("NOT NULL 위반은 이름 중복으로 번역하지 않고 그대로 올린다")
        void doesNotTranslateNonUniqueViolation() {
            User u = user(USER_ID);
            ExpenseCategory leaf = category(10L, u);
            given(expenseTemplateRepository.existsActiveByUserAndName(USER_ID, "점심 템플릿", null))
                    .willReturn(false);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
            given(expenseCategoryRepository.findById(10L)).willReturn(Optional.of(leaf));
            given(expenseCategoryRepository.hasChildren(10L)).willReturn(false);
            given(expenseTemplateRepository.save(any(ExpenseTemplate.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            willThrow(ConstraintViolations.notNull("AMOUNT")).given(expenseTemplateRepository).flush();

            assertThatThrownBy(() -> sut.createTemplate(createCmd(10L)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("이체 프리셋")
    class Transfer {

        private Asset asset(long rowId, AssetType type, User owner) {
            Asset a = Asset.createAsset(owner, "자산" + rowId, type, 0L, "KRW", null,
                null, null, null, null, YNType.Y, null, null, null, null);
            ReflectionTestUtils.setField(a, "rowId", rowId);
            return a;
        }

        private ExpenseTemplateServiceDto.CreateCommand transferCmd(
                Long toAssetRowId, Long amount, Long fee, Long interest) {
            return new ExpenseTemplateServiceDto.CreateCommand(
                USER_ID, "적금 이체", null, 2L, toAssetRowId, fee, interest,
                TxKind.TRANSFER, amount, "매달 적금", null, null, 0, YNType.Y);
        }

        private void givenAssets(AssetType from, AssetType to) {
            User u = user(USER_ID);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
            given(assetRepository.findById(2L)).willReturn(Optional.of(asset(2L, from, u)));
            given(assetRepository.findById(3L)).willReturn(Optional.of(asset(3L, to, u)));
        }

        @Test
        @DisplayName("정상 — 보내는·받는 계좌와 수수료가 프리셋에 남는다")
        void savesTransferPreset() {
            givenAssets(AssetType.BANK_ACCOUNT, AssetType.SAVINGS);

            var info = sut.createTemplate(transferCmd(3L, 300_000L, 500L, null));

            assertThat(info.expenseType()).isEqualTo(TxKind.TRANSFER);
            assertThat(info.toAssetRowId()).isEqualTo(3L);
            assertThat(info.fee()).isEqualTo(500L);
        }

        @Test
        @DisplayName("금액을 안 고정해도 저장된다 — 이체 프리셋은 금액 없이 두는 게 정상 용도")
        void savesTransferPresetWithoutAmount() {
            givenAssets(AssetType.BANK_ACCOUNT, AssetType.SAVINGS);

            // 고정 금액을 끄면 resolveAmount 가 금액을 버린다(null). 대출 이자처럼 매달
            // 금액만 다른 이체는 나머지(계좌·수수료)만 프리셋으로 두고 그때 금액을 적는다.
            var cmd = new ExpenseTemplateServiceDto.CreateCommand(
                USER_ID, "대출 상환", null, 2L, 3L, 500L, null,
                TxKind.TRANSFER, null, "매달 이자만 다름", null, null, 0, YNType.N);

            var info = sut.createTemplate(cmd);

            assertThat(info.amount()).isNull();
            assertThat(info.toAssetRowId()).isEqualTo(3L);
            assertThat(info.fee()).isEqualTo(500L);
        }

        @Test
        @DisplayName("금액이 없어도 수수료 부호는 본다 — 음수 수수료는 없던 돈을 만든다")
        void rejectsNegativeFeeWithoutAmount() {
            givenAssets(AssetType.BANK_ACCOUNT, AssetType.SAVINGS);

            var cmd = new ExpenseTemplateServiceDto.CreateCommand(
                USER_ID, "적금", null, 2L, 3L, -500L, null,
                TxKind.TRANSFER, null, null, null, null, 0, YNType.N);

            assertThatThrownBy(() -> sut.createTemplate(cmd))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).getErrorCode())
                .isEqualTo(DeskErrorCode.ASSET_TRANSFER_INVALID_FEE);
        }

        @Test
        @DisplayName("금액이 없어도 '이자는 대출만' 은 그대로 본다 — 계좌 종류는 금액과 무관하다")
        void stillRejectsInterestOnNonLoanWithoutAmount() {
            givenAssets(AssetType.BANK_ACCOUNT, AssetType.SAVINGS);

            var cmd = new ExpenseTemplateServiceDto.CreateCommand(
                USER_ID, "적금", null, 2L, 3L, null, 10_000L,
                TxKind.TRANSFER, null, null, null, null, 0, YNType.N);

            assertThatThrownBy(() -> sut.createTemplate(cmd))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).getErrorCode())
                .isEqualTo(DeskErrorCode.ASSET_TRANSFER_INVALID_INTEREST);
        }

        @Test
        @DisplayName("고정 금액을 켰는데 0 이면 거절한다 — 금액을 적겠다고 해 놓고 안 적은 것")
        void rejectsZeroWhenLocked() {
            var cmd = new ExpenseTemplateServiceDto.CreateCommand(
                USER_ID, "적금", null, 2L, 3L, null, null,
                TxKind.TRANSFER, 0L, null, null, null, 0, YNType.Y);

            // resolveAmount 가 저장소를 건드리기 전에 끊는다.
            assertThatThrownBy(() -> sut.createTemplate(cmd))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).getErrorCode())
                .isEqualTo(DeskErrorCode.EXPENSE_INVALID_AMOUNT);
        }

        @Test
        @DisplayName("고정을 끄는 수정이면 금액이 지워진다 — 저장된 금액이 유령으로 남지 않는다")
        void unlockingClearsAmountOnUpdate() {
            User u = user(USER_ID);
            ExpenseTemplate t = ExpenseTemplate.createTemplate(
                u, "적금 이체", null, asset(2L, AssetType.BANK_ACCOUNT, u),
                asset(3L, AssetType.SAVINGS, u), 500L, null,
                TxKind.TRANSFER, 300_000L, null, null, null, 0, YNType.Y);
            given(expenseTemplateRepository.findById(5L)).willReturn(Optional.of(t));

            var cmd = new ExpenseTemplateServiceDto.UpdateCommand(
                Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.set(YNType.N));

            var info = sut.updateTemplate(5L, USER_ID, cmd);

            assertThat(info.amount()).isNull();
            // 나머지는 그대로 — 금액만 비우는 것이 사용자의 뜻이다.
            assertThat(info.toAssetRowId()).isEqualTo(3L);
            assertThat(info.fee()).isEqualTo(500L);
        }

        @Test
        @DisplayName("카드는 이체 상대가 될 수 없다 — 반복 이체와 같은 규칙")
        void rejectsCard() {
            givenAssets(AssetType.BANK_ACCOUNT, AssetType.CREDIT_CARD);

            assertThatThrownBy(() -> sut.createTemplate(transferCmd(3L, 300_000L, null, null)))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).getErrorCode())
                .isEqualTo(DeskErrorCode.RECURRING_TRANSFER_CARD_NOT_ALLOWED);
        }

        @Test
        @DisplayName("받는 계좌가 없으면 저장되지 않는다")
        void rejectsMissingToAsset() {
            User u = user(USER_ID);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(u));
            given(assetRepository.findById(2L)).willReturn(Optional.of(asset(2L, AssetType.BANK_ACCOUNT, u)));

            assertThatThrownBy(() -> sut.createTemplate(transferCmd(null, 300_000L, null, null)))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).getErrorCode())
                .isEqualTo(DeskErrorCode.REQUIRED_VALUE_MISSING);
        }

        @Test
        @DisplayName("이자는 받는 계좌가 대출일 때만")
        void rejectsInterestOnNonLoan() {
            givenAssets(AssetType.BANK_ACCOUNT, AssetType.SAVINGS);

            assertThatThrownBy(() -> sut.createTemplate(transferCmd(3L, 300_000L, null, 10_000L)))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).getErrorCode())
                .isEqualTo(DeskErrorCode.ASSET_TRANSFER_INVALID_INTEREST);
        }

        @Test
        @DisplayName("종류만 지출로 바꾸면 거절한다 — 남아 있던 받는 계좌가 함께 와서 짝이 안 맞는다")
        void rejectsKindSwitchWithStaleTransferFields() {
            User u = user(USER_ID);
            ExpenseTemplate t = ExpenseTemplate.createTemplate(
                u, "적금 이체", null, asset(2L, AssetType.BANK_ACCOUNT, u),
                asset(3L, AssetType.SAVINGS, u), 500L, null,
                TxKind.TRANSFER, 300_000L, "매달 적금", null, null, 0, YNType.Y);
            given(expenseTemplateRepository.findById(5L)).willReturn(Optional.of(t));

            // 종류만 EXPENSE 로 바꾼다 — 받는 계좌·수수료는 안 보냈으므로 지금 값이 그대로 남는다.
            var cmd = new ExpenseTemplateServiceDto.UpdateCommand(
                Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.set(TxKind.EXPENSE), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent());

            assertThatThrownBy(() -> sut.updateTemplate(5L, USER_ID, cmd))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).getErrorCode())
                .isEqualTo(DeskErrorCode.INVALID_INPUT);
        }

        @Test
        @DisplayName("useTemplate 로는 이체를 만들지 않는다 — 이 엔드포인트는 지출 1건을 만드는 자리다")
        void useTemplateRejectsTransfer() {
            User u = user(USER_ID);
            ExpenseTemplate t = ExpenseTemplate.createTemplate(
                u, "적금 이체", null, asset(2L, AssetType.BANK_ACCOUNT, u),
                asset(3L, AssetType.SAVINGS, u), null, null,
                TxKind.TRANSFER, 300_000L, "매달 적금", null, null, 0, YNType.Y);
            given(expenseTemplateRepository.findById(5L)).willReturn(Optional.of(t));

            assertThatThrownBy(() -> sut.useTemplate(5L, USER_ID, LocalDate.of(2026, 9, 15)))
                .isInstanceOf(InvalidValueException.class);
            verify(expenseRepository, never()).save(any());
        }
    }
}
