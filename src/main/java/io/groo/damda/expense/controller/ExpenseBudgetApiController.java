package io.groo.damda.expense.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import io.groo.damda.expense.controller.dto.ExpenseBudgetApiDto;
import io.groo.damda.expense.service.ExpenseBudgetService;
import io.groo.damda.expense.service.dto.ExpenseBudgetServiceDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
public class ExpenseBudgetApiController {
    private final ExpenseBudgetService expenseBudgetService;

    @PostMapping("/expense/budget")
    public ApiResponse<ExpenseBudgetApiDto.Response> createBudget(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody ExpenseBudgetApiDto.CreateRequest request) {
        ExpenseBudgetServiceDto.BudgetInfo info = expenseBudgetService.createBudget(new ExpenseBudgetServiceDto.CreateCommand(
            loginUser.getRowId(),
            request.categoryRowId(),
            request.budgetAmount(),
            request.budgetYear(),
            request.budgetMonth()
        ));
        return ApiResponse.success(ExpenseBudgetApiDto.Response.from(info));
    }

    @GetMapping("/expense/budgets")
    public ApiResponse<ExpenseBudgetApiDto.ListResponse> getBudgets(
            @LoginUser UserPrincipal loginUser,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        List<ExpenseBudgetServiceDto.BudgetInfo> infos = expenseBudgetService.getBudgets(
            loginUser.getRowId(), year, month
        );
        return ApiResponse.success(ExpenseBudgetApiDto.ListResponse.from(infos));
    }

    @GetMapping("/expense/budgets/compliance")
    public ApiResponse<ExpenseBudgetApiDto.ComplianceListResponse> getCompliance(
            @LoginUser UserPrincipal loginUser,
            @RequestParam(required = false, defaultValue = "6") Integer months) {
        List<ExpenseBudgetServiceDto.ComplianceMonth> list =
            expenseBudgetService.getCompliance(loginUser.getRowId(), months);
        return ApiResponse.success(ExpenseBudgetApiDto.ComplianceListResponse.from(list));
    }

    @PutMapping("/expense/budget/{id}")
    public ApiResponse<ExpenseBudgetApiDto.Response> updateBudget(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @Valid @RequestBody ExpenseBudgetApiDto.UpdateRequest request) {
        ExpenseBudgetServiceDto.BudgetInfo info = expenseBudgetService.updateBudget(
            id, loginUser.getRowId(),
            new ExpenseBudgetServiceDto.UpdateCommand(request.budgetAmount())
        );
        return ApiResponse.success(ExpenseBudgetApiDto.Response.from(info));
    }

    @DeleteMapping("/expense/budget/{id}")
    public ApiResponse<Void> deleteBudget(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        expenseBudgetService.deleteBudget(id, loginUser.getRowId());
        return ApiResponse.success();
    }
}
