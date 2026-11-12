package io.groo.damda.expense.service;

import io.groo.damda.expense.service.dto.ExpenseServiceDto;
import io.groo.damda.expense.type.ExpenseType;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface ExpenseService {
    ExpenseServiceDto.ExpenseInfo createExpense(ExpenseServiceDto.CreateCommand command);

    /**
     * 대량 적재용 — 잔액 재산정과 예산 알림을 건너뛴다.
     * 재산정은 자산 전체 이력을 다시 읽으므로 행마다 하면 O(N²) 이고,
     * 예산 알림도 행 수만큼 발생한다. 호출자가 끝나고 자산별로 한 번만 재산정한다.
     */
    ExpenseServiceDto.ExpenseInfo createExpense(ExpenseServiceDto.CreateCommand command, boolean bulk);

    /**
     * 대량 적재 — 여러 건을 한 트랜잭션에 넣는다. 한 건이라도 실패하면 전체 롤백되므로,
     * 호출자는 실패 시 건별 재시도로 문제 행을 가려내야 한다.
     */
    void createExpensesChunk(List<ExpenseServiceDto.CreateCommand> commands);
    List<ExpenseServiceDto.ExpenseInfo> getExpenses(Long userRowId, Long categoryRowId, Long assetRowId, ExpenseType expenseType, LocalDate startDate, LocalDate endDate);
    ExpenseServiceDto.ExpenseInfo updateExpense(Long expenseId, Long userRowId, ExpenseServiceDto.UpdateCommand command);

    /** 그 달의 지출 합계 — 예산 이행률과 같은 규칙(예정·환불·카드 이월 제외). 전체 예산 알림용. */
    long getMonthlyExpenseTotal(Long userRowId, int year, int month);

    /**
     * 고쳐 쓰기(D13) — 결제가 끝난 거래를 지우고 새로 적는다. 한 트랜잭션.
     *
     * @param command 새 거래(생성 본문과 같다). 일정·할 일이 비면 옛 거래의 연결을 잇는다
     * @param splits  새 분할. null 이면 옛 분할을 옮긴다(합이 새 금액과 다르면 400)
     * @return 새 거래
     */
    ExpenseServiceDto.ExpenseInfo replaceExpense(Long expenseId, Long userRowId,
                                                 ExpenseServiceDto.CreateCommand command,
                                                 List<io.groo.damda.expense.service.dto.ExpenseSplitServiceDto.SplitCommand> splits);
    /**
     * 지운다. 결제 완료 회차의 카드 거래였다면 결제계좌로 돌려준 금액을 돌려준다(없으면 null).
     */
    Long deleteExpense(Long expenseId, Long userRowId);
    /**
     * 여러 거래를 한 트랜잭션으로 지운다 — 건마다 단건 삭제와 같은 규칙. 하나라도 없음(404) · 남의 것(403) ·
     * 시스템이 만든 거래(400)면 아무것도 지우지 않는다. 환급액은 건마다 난 값의 합(0 이면 null).
     */
    ExpenseServiceDto.BulkDeleteResult bulkDeleteExpenses(ExpenseServiceDto.BulkDeleteCommand command);
    /**
     * 고정 표식 일괄 변경 — 바뀐 건수를 돌려준다. 돈·잔액은 건드리지 않는다.
     * 하나라도 내 거래가 아니면 전체를 거절한다(일부만 바뀌는 상태를 만들지 않는다).
     */
    int bulkMarkFixed(ExpenseServiceDto.BulkFixedCommand command);
    ExpenseServiceDto.DailySummary getDailySummary(Long userRowId, LocalDate date);
    ExpenseServiceDto.RangeSummary getRangeSummary(Long userRowId, LocalDate startDate, LocalDate endDate);

    /** 자산으로 좁힌 기간 요약 — assetRowId 가 null 이면 전체. */
    ExpenseServiceDto.RangeSummary getRangeSummary(Long userRowId, LocalDate startDate, LocalDate endDate,
                                                  Long assetRowId);
    List<ExpenseServiceDto.MonthlyTrend> getMonthlyTrend(Long userRowId, Integer months);
    List<ExpenseServiceDto.MerchantSummary> getMerchantSummary(Long userRowId, LocalDate startDate, LocalDate endDate);
    List<ExpenseServiceDto.AssetSummary> getAssetSummary(Long userRowId, LocalDate startDate, LocalDate endDate);
    List<ExpenseServiceDto.ExpenseInfo> searchExpenses(ExpenseServiceDto.SearchCommand command);
    List<ExpenseServiceDto.ExpenseInfo> getExpensesByCalendarEvent(Long userRowId, Long calendarEventRowId);
    List<ExpenseServiceDto.ExpenseInfo> getExpensesByTodo(Long userRowId, Long todoRowId);
    List<ExpenseServiceDto.HeatmapCell> getHeatmap(Long userRowId, LocalDate startDate, LocalDate endDate);

    /**
     * 해당 월의 EXPENSE 지출을 카테고리별로 집계해 반환(split-aware).
     * 분할이 있는 거래는 분할 항목별 카테고리로, 없으면 거래 카테고리로 귀속하며,
     * 각 leaf 금액을 leaf 키와 부모 키 모두에 누적(롤업)한다. 예산 알림 등 카테고리 귀속이 필요한 곳에서 사용.
     */
    Map<Long, Long> getMonthlyExpenseSpendByCategory(Long userRowId, int year, int month);

    /** 환불 마크 — 삭제 대신. {@code refundedAt} 이 null 이면 지금. */
    ExpenseServiceDto.ExpenseInfo refund(Long expenseId, Long userRowId,
                                        java.time.LocalDateTime refundedAt);

    /** 환불 취소 — 표식·환급 이체를 무르고 원거래 흐름을 되살린다. */
    ExpenseServiceDto.ExpenseInfo cancelRefund(Long expenseId, Long userRowId);

    /**
     * 이 거래를 지우거나 고치면 돈이 <b>어떻게 움직이는지</b> 미리 센다(설계 13-1, 닫힌 회차 R2·R3·R6).
     *
     * <p>DB 를 바꾸지 않는다. 네 인자를 모두 비우면 삭제 미리보기다. 환불 확인창도 삭제
     * 미리보기를 쓴다 — 돈과 집계는 삭제와 똑같이 움직인다.
     *
     * @param amountAfter            수정 뒤 금액 (null = 그대로)
     * @param assetRowIdAfter        수정 뒤 자산 (null = 그대로)
     * @param dateAfter              수정 뒤 일시 (null = 그대로)
     * @param installmentMonthsAfter 수정 뒤 할부 개월 (null = 그대로)
     */
    ExpenseServiceDto.RefundPreviewInfo refundPreview(
        Long expenseId, Long userRowId, Long amountAfter, Long assetRowIdAfter,
        java.time.LocalDateTime dateAfter, Integer installmentMonthsAfter);

    /**
     * 새 카드 지출을 저장하면 어떻게 되는지 미리 센다 — 닫힌 회차면 기록만(R2), 결제일 당일이면
     * 결제계좌에서 추가로 빠진다(R3). DB 를 바꾸지 않는다.
     */
    ExpenseServiceDto.RefundPreviewInfo cardSavePreview(
        Long userRowId, Long assetRowId, Long amount, java.time.LocalDateTime expenseDate,
        Integer installmentMonths);
}
