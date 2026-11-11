package io.groo.damda.expense.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.expense.controller.dto.ExpenseSplitApiDto;
import io.groo.damda.expense.service.ExpenseSplitService;
import io.groo.damda.expense.service.dto.ExpenseSplitServiceDto;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ExpenseSplitApiController {
    private final ExpenseSplitService expenseSplitService;

    @GetMapping("/expense/{expenseId}/splits")
    public ApiResponse<ExpenseSplitApiDto.ListResponse> getSplits(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long expenseId) {
        List<ExpenseSplitServiceDto.SplitInfo> infos = expenseSplitService.getSplits(expenseId, loginUser.getRowId());
        return ApiResponse.success(ExpenseSplitApiDto.ListResponse.from(infos));
    }

    @PutMapping("/expense/{expenseId}/splits")
    public ApiResponse<ExpenseSplitApiDto.ListResponse> replaceSplits(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long expenseId,
            @RequestBody ExpenseSplitApiDto.ReplaceRequest request) {
        List<ExpenseSplitServiceDto.SplitCommand> splits = request.splits() != null
            ? request.splits().stream()
                .map(s -> new ExpenseSplitServiceDto.SplitCommand(
                    s.rowId(), s.categoryRowId(), s.amount(), s.label(), s.sortOrder()))
                .toList()
            : List.of();

        List<ExpenseSplitServiceDto.SplitInfo> infos = expenseSplitService.replaceSplits(
            new ExpenseSplitServiceDto.ReplaceCommand(expenseId, loginUser.getRowId(), splits)
        );
        return ApiResponse.success(ExpenseSplitApiDto.ListResponse.from(infos));
    }

    @DeleteMapping("/expense/{expenseId}/splits")
    public ApiResponse<Void> deleteAllSplits(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long expenseId) {
        expenseSplitService.deleteAllSplits(expenseId, loginUser.getRowId());
        return ApiResponse.success();
    }
}
