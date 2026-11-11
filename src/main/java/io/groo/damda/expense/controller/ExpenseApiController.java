package io.groo.damda.expense.controller;

import io.groo.core.controller.ApiResponse;
import jakarta.validation.Valid;
import io.groo.damda.common.time.WallClockDateTimeParser;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import io.groo.damda.common.patch.Patch;
import io.groo.damda.expense.controller.dto.ExpenseApiDto;
import io.groo.damda.expense.service.ExpenseService;
import io.groo.damda.expense.service.dto.ExpenseServiceDto;
import io.groo.damda.expense.service.dto.ExpenseSplitServiceDto;
import io.groo.damda.expense.type.ExpenseType;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ExpenseApiController {
    private final ExpenseService expenseService;

    @PostMapping("/expense")
    public ApiResponse<ExpenseApiDto.Response> createExpense(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody ExpenseApiDto.CreateRequest request) {
        rejectDeprecatedRefundField(request.refundOfExpenseRowId());
        ExpenseServiceDto.ExpenseInfo info = expenseService.createExpense(new ExpenseServiceDto.CreateCommand(
            loginUser.getRowId(),
            request.categoryRowId(),
            request.assetRowId(),
            request.expenseType(),
            request.amount(),
            request.description(),
            parseExpenseDate(request.expenseDate()),
            request.merchant(),
            request.paymentMethod(),
            request.installmentMonths(),
            request.originalAmount(), request.originalCurrency(), request.exchangeRate(),
            request.calendarEventRowId(),
            request.todoRowId(),
            request.isFixed()
        ));
        return ApiResponse.success(ExpenseApiDto.Response.from(info));
    }

    @GetMapping("/expenses")
    public ApiResponse<ExpenseApiDto.ListResponse> getExpenses(
            @LoginUser UserPrincipal loginUser,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long assetId,
            @RequestParam(required = false) ExpenseType expenseType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<ExpenseServiceDto.ExpenseInfo> infos = expenseService.getExpenses(
            loginUser.getRowId(), categoryId, assetId, expenseType, startDate, endDate
        );
        return ApiResponse.success(ExpenseApiDto.ListResponse.from(infos));
    }

    @PutMapping("/expense/{id}")
    public ApiResponse<ExpenseApiDto.Response> updateExpense(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @Valid @RequestBody ExpenseApiDto.UpdateRequest request) {
        // null = 분할 미변경(기존 유지), 리스트 = 새 분할로 교체. null 의미를 보존하기 위해 null 체크.
        List<ExpenseSplitServiceDto.SplitCommand> splits = request.splits() == null
            ? null
            : request.splits().stream()
                .map(s -> new ExpenseSplitServiceDto.SplitCommand(
                    s.rowId(), s.categoryRowId(), s.amount(), s.label(), s.sortOrder()))
                .toList();

        rejectDeprecatedRefundField(request.refundOfExpenseRowId());
        ExpenseServiceDto.ExpenseInfo info = expenseService.updateExpense(id, loginUser.getRowId(), new ExpenseServiceDto.UpdateCommand(
            Patch.from(request.categoryRowId()),
            Patch.from(request.assetRowId()),
            Patch.from(request.expenseType()),
            Patch.from(request.amount()),
            Patch.from(request.description()),
            Patch.from(request.expenseDate()).map(ExpenseApiController::parseExpenseDate),
            Patch.from(request.merchant()),
            Patch.from(request.paymentMethod()),
            Patch.from(request.installmentMonths()),
            Patch.from(request.originalAmount()),
            Patch.from(request.originalCurrency()),
            Patch.from(request.exchangeRate()),
            Patch.from(request.calendarEventRowId()),
            Patch.from(request.todoRowId()),
            splits,
            isDateOnly(request.expenseDate()),
            Patch.from(request.isFixed())
        ));
        return ApiResponse.success(ExpenseApiDto.Response.from(info));
    }

    /**
     * 고쳐 쓰기(D13) — 결제가 끝난 거래를 지우고 새로 적는다. 본문은 새 거래 생성과 같고 {@code splits} 를
     * 더 받는다. 응답은 <b>새 거래</b>다(새 rowId).
     */
    @PostMapping("/expense/{id}/replace")
    public ApiResponse<ExpenseApiDto.Response> replaceExpense(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @Valid @RequestBody ExpenseApiDto.ReplaceRequest request) {
        List<ExpenseSplitServiceDto.SplitCommand> splits = request.splits() == null
            ? null
            : request.splits().stream()
                .map(s -> new ExpenseSplitServiceDto.SplitCommand(
                    null, s.categoryRowId(), s.amount(), s.label(), s.sortOrder()))
                .toList();
        ExpenseServiceDto.ExpenseInfo info = expenseService.replaceExpense(id, loginUser.getRowId(),
            new ExpenseServiceDto.CreateCommand(
                loginUser.getRowId(),
                request.categoryRowId(),
                request.assetRowId(),
                request.expenseType(),
                request.amount(),
                request.description(),
                parseExpenseDate(request.expenseDate()),
                request.merchant(),
                request.paymentMethod(),
                request.installmentMonths(),
                request.originalAmount(), request.originalCurrency(), request.exchangeRate(),
                request.calendarEventRowId(),
                request.todoRowId(),
                request.isFixed()),
            splits);
        return ApiResponse.success(ExpenseApiDto.Response.from(info));
    }

    /** 날짜만(yyyy-MM-dd) 온 일시인가 — 잠긴 거래의 날짜 비교를 날짜로만 한다(D12). */
    private static boolean isDateOnly(java.util.Optional<String> raw) {
        return raw != null && raw.isPresent() && raw.get() != null && raw.get().trim().length() == 10;
    }

    /** 고정 표식 일괄 변경 — 고른 거래 전부를 고정 또는 변동으로. 남의 거래가 섞여 있으면 전체가 거절된다. */
    @PutMapping("/expenses/fixed")
    public ApiResponse<ExpenseApiDto.BulkFixedResponse> bulkMarkFixed(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody ExpenseApiDto.BulkFixedRequest request) {
        int updated = expenseService.bulkMarkFixed(new ExpenseServiceDto.BulkFixedCommand(
            loginUser.getRowId(), request.expenseIds(), request.isFixed()));
        return ApiResponse.success(new ExpenseApiDto.BulkFixedResponse(updated));
    }

    @DeleteMapping("/expense/{id}")
    public ApiResponse<ExpenseApiDto.DeleteResponse> deleteExpense(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        Long refundedAmount = expenseService.deleteExpense(id, loginUser.getRowId());
        return ApiResponse.success(ExpenseApiDto.DeleteResponse.of(refundedAmount));
    }

    /** 일괄 삭제 — 한 트랜잭션. 하나라도 없거나 남의 것이거나 시스템 거래면 전체가 거절된다. */
    @PostMapping("/expenses/delete")
    public ApiResponse<ExpenseApiDto.BulkDeleteResponse> bulkDeleteExpenses(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody ExpenseApiDto.BulkDeleteRequest request) {
        ExpenseServiceDto.BulkDeleteResult result = expenseService.bulkDeleteExpenses(
            new ExpenseServiceDto.BulkDeleteCommand(loginUser.getRowId(), request.expenseIds()));
        return ApiResponse.success(new ExpenseApiDto.BulkDeleteResponse(result.deletedCount(), result.refundedAmount()));
    }

    @GetMapping("/expenses/summary/daily")
    public ApiResponse<ExpenseApiDto.DailySummaryResponse> getDailySummary(
            @LoginUser UserPrincipal loginUser,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        ExpenseServiceDto.DailySummary summary = expenseService.getDailySummary(loginUser.getRowId(), date);
        return ApiResponse.success(ExpenseApiDto.DailySummaryResponse.from(summary));
    }

    @GetMapping("/expenses/summary/range")
    public ApiResponse<ExpenseApiDto.RangeSummaryResponse> getRangeSummary(
            @LoginUser UserPrincipal loginUser,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long assetId) {
        ExpenseServiceDto.RangeSummary summary =
            expenseService.getRangeSummary(loginUser.getRowId(), startDate, endDate, assetId);
        return ApiResponse.success(ExpenseApiDto.RangeSummaryResponse.from(summary));
    }

    @GetMapping("/expenses/summary/trend")
    public ApiResponse<ExpenseApiDto.MonthlyTrendListResponse> getMonthlyTrend(
            @LoginUser UserPrincipal loginUser,
            @RequestParam(required = false, defaultValue = "6") Integer months) {
        List<ExpenseServiceDto.MonthlyTrend> trends = expenseService.getMonthlyTrend(loginUser.getRowId(), months);
        return ApiResponse.success(ExpenseApiDto.MonthlyTrendListResponse.from(trends));
    }

    @GetMapping("/expenses/summary/by-merchant")
    public ApiResponse<ExpenseApiDto.MerchantSummaryListResponse> getMerchantSummary(
            @LoginUser UserPrincipal loginUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<ExpenseServiceDto.MerchantSummary> summaries = expenseService.getMerchantSummary(loginUser.getRowId(), startDate, endDate);
        return ApiResponse.success(ExpenseApiDto.MerchantSummaryListResponse.from(summaries));
    }

    @GetMapping("/expenses/summary/by-asset")
    public ApiResponse<ExpenseApiDto.AssetSummaryListResponse> getAssetSummary(
            @LoginUser UserPrincipal loginUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<ExpenseServiceDto.AssetSummary> summaries = expenseService.getAssetSummary(loginUser.getRowId(), startDate, endDate);
        return ApiResponse.success(ExpenseApiDto.AssetSummaryListResponse.from(summaries));
    }

    @GetMapping("/calendar/event/{eventId}/expenses")
    public ApiResponse<ExpenseApiDto.ListResponse> getExpensesByCalendarEvent(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long eventId) {
        List<ExpenseServiceDto.ExpenseInfo> infos = expenseService.getExpensesByCalendarEvent(eventId);
        return ApiResponse.success(ExpenseApiDto.ListResponse.from(infos));
    }

    @GetMapping("/todo/{todoId}/expenses")
    public ApiResponse<ExpenseApiDto.ListResponse> getExpensesByTodo(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long todoId) {
        List<ExpenseServiceDto.ExpenseInfo> infos = expenseService.getExpensesByTodo(todoId);
        return ApiResponse.success(ExpenseApiDto.ListResponse.from(infos));
    }

    @GetMapping("/expenses/summary/heatmap")
    public ApiResponse<ExpenseApiDto.HeatmapResponse> getHeatmap(
            @LoginUser UserPrincipal loginUser,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<ExpenseServiceDto.HeatmapCell> cells = expenseService.getHeatmap(loginUser.getRowId(), startDate, endDate);
        return ApiResponse.success(ExpenseApiDto.HeatmapResponse.from(cells));
    }

    @GetMapping("/expenses/search")
    public ApiResponse<ExpenseApiDto.ListResponse> searchExpenses(
            @LoginUser UserPrincipal loginUser,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long assetId,
            @RequestParam(required = false) ExpenseType expenseType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String merchant,
            @RequestParam(required = false) Long minAmount,
            @RequestParam(required = false) Long maxAmount,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Boolean fixed) {
        List<ExpenseServiceDto.ExpenseInfo> infos = expenseService.searchExpenses(new ExpenseServiceDto.SearchCommand(
            loginUser.getRowId(), categoryId, assetId, expenseType, keyword, merchant,
            minAmount, maxAmount, startDate, endDate, fixed
        ));
        return ApiResponse.success(ExpenseApiDto.ListResponse.from(infos));
    }

    /** expenseDate 문자열 → LocalDateTime. 규칙은 {@link WallClockDateTimeParser} 참조. */
    private static LocalDateTime parseExpenseDate(String s) {
        return WallClockDateTimeParser.parse(s);
    }

    /**
     * 환불 마크 — 원거래에 표식을 찍는다. 수입 행을 만들지 않는다.
     *
     * <p>{@code refundedAt} 을 안 보내면 지금이다. 카드였고 그 회차를 이미 냈다면
     * 남는 돈만큼 결제계좌로 환급 이체가 함께 생긴다(응답의 {@code refundTransferRowId}).
     */
    @PostMapping("/expense/{id}/refund")
    public ApiResponse<ExpenseApiDto.Response> refund(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @RequestBody(required = false) ExpenseApiDto.RefundRequest request) {
        LocalDateTime at = request != null && request.refundedAt() != null
            ? parseExpenseDate(request.refundedAt()) : null;
        ExpenseServiceDto.ExpenseInfo info =
            expenseService.refund(id, loginUser.getRowId(), at);
        return ApiResponse.success(ExpenseApiDto.Response.from(info));
    }

    /**
     * 지우거나 고치면 돈이 어떻게 움직이는지 미리 센다(설계 13-1, 닫힌 회차 R2·R3·R6).
     *
     * <p>쿼리를 비우면 <b>삭제</b> 미리보기다. 수정 미리보기는 바뀔 값만 싣는다 —
     * {@code amount}(감액) · {@code assetRowId}(자산 변경) · {@code expenseDate}(날짜 이동) ·
     * {@code installmentMonths}(할부 개월). DB 는 바뀌지 않는다.
     */
    @GetMapping("/expense/{id}/refund-preview")
    public ApiResponse<ExpenseApiDto.RefundPreviewResponse> refundPreview(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @RequestParam(required = false) Long amount,
            @RequestParam(required = false) Long assetRowId,
            @RequestParam(required = false) String expenseDate,
            @RequestParam(required = false) Integer installmentMonths) {
        ExpenseServiceDto.RefundPreviewInfo info = expenseService.refundPreview(
            id, loginUser.getRowId(), amount, assetRowId,
            expenseDate != null ? parseExpenseDate(expenseDate) : null, installmentMonths);
        return ApiResponse.success(ExpenseApiDto.RefundPreviewResponse.from(info));
    }

    /**
     * 새 카드 지출을 저장하면 어떻게 되는지 미리 센다 — 닫힌 회차면 기록만(R2), 결제일
     * 당일이면 결제계좌에서 추가로 빠진다(R3). DB 는 바뀌지 않는다.
     */
    @GetMapping("/expense/card-save-preview")
    public ApiResponse<ExpenseApiDto.RefundPreviewResponse> cardSavePreview(
            @LoginUser UserPrincipal loginUser,
            @RequestParam Long assetRowId,
            @RequestParam Long amount,
            @RequestParam String expenseDate,
            @RequestParam(required = false) Integer installmentMonths) {
        ExpenseServiceDto.RefundPreviewInfo info = expenseService.cardSavePreview(
            loginUser.getRowId(), assetRowId, amount, parseExpenseDate(expenseDate), installmentMonths);
        return ApiResponse.success(ExpenseApiDto.RefundPreviewResponse.from(info));
    }

    /** 환불 취소 — 표식·환급 이체를 무르고 원거래 흐름을 되살린다. */
    @DeleteMapping("/expense/{id}/refund")
    public ApiResponse<ExpenseApiDto.Response> cancelRefund(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        ExpenseServiceDto.ExpenseInfo info =
            expenseService.cancelRefund(id, loginUser.getRowId());
        return ApiResponse.success(ExpenseApiDto.Response.from(info));
    }

    /**
     * 폐기된 환불 칸을 <b>눈에 보이게</b> 막는다.
     *
     * <p>조용히 무시하면 옛 앱의 환불이 일반 수입으로 저장된다 — 사용자는 환불한 줄 알고
     * 통계는 부푼다. 400 으로 끊으면 "앱을 업데이트해 주세요" 가 화면에 뜨므로 강제
     * 업데이트가 필요 없다(설계서 6절).
     */
    private static void rejectDeprecatedRefundField(java.util.Optional<Long> field) {
        // PUT 은 "키가 없으면 유지" 라 안 실린 칸은 **Optional 자체가 null** 이다
        // (Jackson 이 없는 키를 Optional.empty 로 채우지 않는다). null 검사를 빼면
        // 환불과 무관한 모든 수정이 500 으로 죽는다 — 테스트가 그렇게 잡혔다.
        rejectDeprecatedRefundField(field != null ? field.orElse(null) : null);
    }

    private static void rejectDeprecatedRefundField(Long refundOfExpenseRowId) {
        if (refundOfExpenseRowId != null) {
            throw new io.groo.core.exception.InvalidValueException(
                io.groo.damda.common.exception.DeskErrorCode.DEPRECATED_FIELD);
        }
    }
}
