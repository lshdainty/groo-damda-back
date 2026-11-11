package io.groo.damda.expense.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import io.groo.damda.expense.controller.dto.ExpenseCategoryApiDto;
import io.groo.damda.expense.service.ExpenseCategoryService;
import io.groo.damda.expense.service.dto.ExpenseCategoryServiceDto;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ExpenseCategoryApiController {
    private final ExpenseCategoryService expenseCategoryService;

    @PostMapping("/expense/category")
    public ApiResponse<ExpenseCategoryApiDto.Response> createCategory(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody ExpenseCategoryApiDto.CreateRequest request) {
        ExpenseCategoryServiceDto.CategoryInfo info = expenseCategoryService.createCategory(new ExpenseCategoryServiceDto.CreateCommand(
            loginUser.getRowId(),
            request.categoryName(),
            request.icon(),
            request.color(),
            request.expenseType(),
            request.sortOrder(),
            request.parentRowId()
        ));
        return ApiResponse.success(ExpenseCategoryApiDto.Response.from(info));
    }

    @GetMapping("/expense/categories")
    public ApiResponse<ExpenseCategoryApiDto.ListResponse> getCategories(
            @LoginUser UserPrincipal loginUser) {
        List<ExpenseCategoryServiceDto.CategoryInfo> infos = expenseCategoryService.getCategories(loginUser.getRowId());
        return ApiResponse.success(ExpenseCategoryApiDto.ListResponse.from(infos));
    }

    @PutMapping("/expense/category/{id}")
    public ApiResponse<ExpenseCategoryApiDto.Response> updateCategory(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @Valid @RequestBody ExpenseCategoryApiDto.UpdateRequest request) {
        ExpenseCategoryServiceDto.CategoryInfo info = expenseCategoryService.updateCategory(id, loginUser.getRowId(), new ExpenseCategoryServiceDto.UpdateCommand(
            request.categoryName(),
            request.icon(),
            request.color(),
            request.expenseType(),
            request.sortOrder(),
            request.parentRowId()
        ));
        return ApiResponse.success(ExpenseCategoryApiDto.Response.from(info));
    }

    @DeleteMapping("/expense/category/{id}")
    public ApiResponse<Void> deleteCategory(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        expenseCategoryService.deleteCategory(id, loginUser.getRowId());
        return ApiResponse.success();
    }

    @PatchMapping("/expense/categories/reorder")
    public ApiResponse<Void> reorderCategories(
            @LoginUser UserPrincipal loginUser,
            @RequestBody ExpenseCategoryApiDto.ReorderRequest request) {
        expenseCategoryService.reorderCategories(
            loginUser.getRowId(),
            request.items().stream()
                .map(i -> new ExpenseCategoryServiceDto.ReorderItem(
                    i.categoryRowId(), i.sortOrder(), i.parentRowId()
                ))
                .toList()
        );
        return ApiResponse.success();
    }

    /**
     * 카테고리에 달린 거래를 다른 카테고리로 일괄 이동.
     *
     * <p>거래가 직접 달린 카테고리는 부모가 될 수 없어 하위 분류를 만들 수 없다.
     * 그 상태를 푸는 유일한 방법인데, 지금까지는 거래를 하나씩 편집하는 수밖에 없었다.
     */
    @PostMapping("/expense/category/{id}/move-transactions")
    public ApiResponse<ExpenseCategoryApiDto.MoveResponse> moveTransactions(
            @LoginUser UserPrincipal loginUser,
            @PathVariable("id") Long id,
            @RequestBody ExpenseCategoryApiDto.MoveRequest request) {
        var moved = expenseCategoryService.moveTransactions(id, request.targetCategoryRowId(), loginUser.getRowId());
        return ApiResponse.success(new ExpenseCategoryApiDto.MoveResponse(
            moved.expenses(), moved.recurring(), moved.splits()));
    }

    /**
     * 하위 카테고리를 만들면서 이 카테고리의 거래를 그리로 옮긴다.
     *
     * <p>거래가 달린 카테고리는 하위를 만들 수 없고, 옮길 하위가 없으면 거래도 못 옮기는
     * 교착을 푸는 경로. 생성과 이동이 한 트랜잭션이라 커밋 시점엔 규칙이 지켜진다.
     */
    @PostMapping("/expense/category/{id}/split-into-child")
    public ApiResponse<ExpenseCategoryApiDto.MoveResponse> splitIntoChild(
            @LoginUser UserPrincipal loginUser,
            @PathVariable("id") Long id,
            @Valid @RequestBody ExpenseCategoryApiDto.SplitIntoChildRequest request) {
        var moved = expenseCategoryService.moveTransactionsToNewChild(
            id, request.childName(), request.icon(), request.color(), loginUser.getRowId());
        return ApiResponse.success(new ExpenseCategoryApiDto.MoveResponse(
            moved.expenses(), moved.recurring(), moved.splits()));
    }
}
