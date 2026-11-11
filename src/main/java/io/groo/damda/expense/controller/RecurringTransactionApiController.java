package io.groo.damda.expense.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.expense.controller.dto.RecurringTransactionApiDto;
import io.groo.damda.expense.service.RecurringTransactionService;
import io.groo.damda.expense.service.dto.RecurringTransactionServiceDto;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RecurringTransactionApiController {
    private final RecurringTransactionService recurringTransactionService;

    @PostMapping("/recurring-transaction")
    public ApiResponse<RecurringTransactionApiDto.Response> createRecurring(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody RecurringTransactionApiDto.CreateRequest request) {
        RecurringTransactionServiceDto.RecurringInfo info = recurringTransactionService.createRecurring(
            new RecurringTransactionServiceDto.CreateCommand(
                loginUser.getRowId(),
                request.categoryRowId(), request.assetRowId(),
                request.toAssetRowId(), request.fee(), request.interestAmount(),
                request.sourceExpenseRowId(),
                request.expenseType(), request.amount(), request.description(),
                request.merchant(), request.paymentMethod(),
                request.frequency(), request.intervalValue(),
                request.dayOfWeek(), request.dayOfMonth(),
                request.executionTime(),
                request.startDate(), request.endDate(),
                request.maxOccurrences(),
                request.autoLog(), request.notifyDayBefore(),
                request.isFixed()
            )
        );
        return ApiResponse.success(RecurringTransactionApiDto.Response.from(info));
    }

    @GetMapping("/recurring-transactions")
    public ApiResponse<RecurringTransactionApiDto.ListResponse> getRecurrings(
            @LoginUser UserPrincipal loginUser,
            @RequestParam(required = false) Boolean upcoming,
            @RequestParam(required = false) Integer limit) {
        List<RecurringTransactionServiceDto.RecurringInfo> infos = recurringTransactionService.getRecurrings(
            loginUser.getRowId(),
            Boolean.TRUE.equals(upcoming),
            limit
        );
        return ApiResponse.success(RecurringTransactionApiDto.ListResponse.from(infos));
    }

    @PutMapping("/recurring-transaction/{id}")
    public ApiResponse<RecurringTransactionApiDto.Response> updateRecurring(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @Valid @RequestBody RecurringTransactionApiDto.UpdateRequest request) {
        RecurringTransactionServiceDto.RecurringInfo info = recurringTransactionService.updateRecurring(id, loginUser.getRowId(),
            new RecurringTransactionServiceDto.UpdateCommand(
                request.categoryRowId(), request.assetRowId(),
                request.toAssetRowId(), request.fee(), request.interestAmount(),
                request.expenseType(), request.amount(), request.description(),
                request.merchant(), request.paymentMethod(),
                request.frequency(), request.intervalValue(),
                request.dayOfWeek(), request.dayOfMonth(),
                request.executionTime(),
                request.startDate(), request.endDate(),
                request.maxOccurrences(),
                request.autoLog(), request.notifyDayBefore(),
                request.isFixed()
            )
        );
        return ApiResponse.success(RecurringTransactionApiDto.Response.from(info));
    }

    @DeleteMapping("/recurring-transaction/{id}")
    public ApiResponse<Void> deleteRecurring(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        recurringTransactionService.deleteRecurring(id, loginUser.getRowId());
        return ApiResponse.success();
    }

    @PatchMapping("/recurring-transaction/{id}/toggle")
    public ApiResponse<RecurringTransactionApiDto.Response> toggleActive(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        RecurringTransactionServiceDto.RecurringInfo info = recurringTransactionService.toggleActive(id, loginUser.getRowId());
        return ApiResponse.success(RecurringTransactionApiDto.Response.from(info));
    }
}
