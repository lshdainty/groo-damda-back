package io.groo.damda.card.service;

import io.groo.core.exception.InvalidValueException;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** 보안 점검 DAM-9 — 할부 개월 수 상한. */
class InstallmentMonthsLimitTest {

    private static final LocalDateTime DATE = LocalDateTime.of(2026, 11, 3, 12, 0);

    private static Expense expense(Integer months) {
        User u = User.createUser(null, "tester", "테스터", "tester@porest.com");
        return Expense.createExpense(u, null, null, ExpenseType.EXPENSE, 1_200_000L, null, DATE,
            null, null, months, null, null, null);
    }

    @Test
    @DisplayName("Integer.MAX_VALUE 개월은 만들 때 거절한다 — 종전엔 회차 반복이 넘쳐 끝나지 않았다")
    void rejectsHugeMonthsAtCreate() {
        assertThatThrownBy(() -> expense(Integer.MAX_VALUE))
            .isInstanceOf(InvalidValueException.class)
            .extracting("errorCode").isEqualTo(DeskErrorCode.EXPENSE_INSTALLMENT_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("상한(60개월)까지는 받고, 61개월은 거절한다")
    void boundary() {
        assertThat(expense(60).getInstallmentMonths()).isEqualTo(60);
        assertThatThrownBy(() -> expense(61)).isInstanceOf(InvalidValueException.class);
        // 1 이하 · null 은 종전대로 일시불
        assertThat(expense(1).getInstallmentMonths()).isNull();
        assertThat(expense(null).getInstallmentMonths()).isNull();
    }

    @Test
    @DisplayName("수정도 같은 상한을 탄다")
    void rejectsHugeMonthsAtUpdate() {
        Expense e = expense(12);
        assertThatThrownBy(() -> e.updateExpense(null, null, ExpenseType.EXPENSE, 1_200_000L, null, DATE,
                null, null, Integer.MAX_VALUE, null, null, null))
            .isInstanceOf(InvalidValueException.class);
        assertThat(e.getInstallmentMonths()).isEqualTo(12);
    }

    @Test
    @DisplayName("이미 저장된 상한 밖 행(옛 데이터)도 회차 계산이 금방 끝난다 — 반복이 넘치지 않는다")
    void legacyRowDoesNotLoopForever() {
        Expense e = expense(12);
        ReflectionTestUtils.setField(e, "installmentMonths", Integer.MAX_VALUE);

        var dues = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> CardCycleMath.duesByCycle(e));

        assertThat(dues.size()).isLessThanOrEqualTo(60);
    }
}
