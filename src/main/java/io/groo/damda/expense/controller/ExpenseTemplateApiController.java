package io.groo.damda.expense.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.expense.controller.dto.ExpenseApiDto;
import io.groo.damda.common.patch.Patch;
import io.groo.damda.expense.controller.dto.ExpenseTemplateApiDto;
import io.groo.damda.expense.service.ExpenseTemplateService;
import io.groo.damda.expense.service.dto.ExpenseServiceDto;
import io.groo.damda.expense.service.dto.ExpenseTemplateServiceDto;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
public class ExpenseTemplateApiController {
    private final ExpenseTemplateService expenseTemplateService;

    @PostMapping("/expense-template")
    public ApiResponse<ExpenseTemplateApiDto.Response> createTemplate(
            @LoginUser UserPrincipal loginUser,
            @RequestBody ExpenseTemplateApiDto.CreateRequest request) {
        ExpenseTemplateServiceDto.TemplateInfo info = expenseTemplateService.createTemplate(
            new ExpenseTemplateServiceDto.CreateCommand(
                loginUser.getRowId(),
                request.templateName(), request.categoryRowId(), request.assetRowId(),
                request.toAssetRowId(), request.fee(), request.interestAmount(),
                request.expenseType(), request.amount(), request.description(),
                request.merchant(), request.paymentMethod(), request.sortOrder(),
                request.lockAmount(), request.isFixed()
            )
        );
        return ApiResponse.success(ExpenseTemplateApiDto.Response.from(info));
    }

    @GetMapping("/expense-templates")
    public ApiResponse<ExpenseTemplateApiDto.ListResponse> getTemplates(@LoginUser UserPrincipal loginUser) {
        List<ExpenseTemplateServiceDto.TemplateInfo> infos = expenseTemplateService.getTemplates(loginUser.getRowId());
        return ApiResponse.success(ExpenseTemplateApiDto.ListResponse.from(infos));
    }

    @PutMapping("/expense-template/{id}")
    public ApiResponse<ExpenseTemplateApiDto.Response> updateTemplate(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @RequestBody ExpenseTemplateApiDto.UpdateRequest request) {
        ExpenseTemplateServiceDto.TemplateInfo info = expenseTemplateService.updateTemplate(id, loginUser.getRowId(),
            new ExpenseTemplateServiceDto.UpdateCommand(
                Patch.from(request.templateName()), Patch.from(request.categoryRowId()),
                Patch.from(request.assetRowId()), Patch.from(request.toAssetRowId()),
                Patch.from(request.fee()), Patch.from(request.interestAmount()),
                Patch.from(request.expenseType()),
                Patch.from(request.amount()), Patch.from(request.description()),
                Patch.from(request.merchant()), Patch.from(request.paymentMethod()),
                Patch.from(request.lockAmount()),
                Patch.from(request.isFixed())
            )
        );
        return ApiResponse.success(ExpenseTemplateApiDto.Response.from(info));
    }

    @DeleteMapping("/expense-template/{id}")
    public ApiResponse<Void> deleteTemplate(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        expenseTemplateService.deleteTemplate(id, loginUser.getRowId());
        return ApiResponse.success();
    }

    @PostMapping("/expense-template/{id}/use")
    public ApiResponse<ExpenseApiDto.Response> useTemplate(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @RequestBody ExpenseTemplateApiDto.UseRequest request) {
        ExpenseServiceDto.ExpenseInfo info = expenseTemplateService.useTemplate(id, loginUser.getRowId(), request.expenseDate());
        return ApiResponse.success(ExpenseApiDto.Response.from(info));
    }

    /**
     * AddTxSheet 칩으로 프리셋을 불러왔지만 폼을 수정해 일반 거래로 저장한 경우,
     * 거래 저장 성공 후 이 엔드포인트를 호출해 useCount/lastUsedAt 만 갱신한다.
     * (Expense 생성은 일반 /v1/expense 흐름을 그대로 사용한다.)
     */
    @PostMapping("/expense-template/{id}/touch")
    public ApiResponse<ExpenseTemplateApiDto.Response> touchTemplate(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        ExpenseTemplateServiceDto.TemplateInfo info = expenseTemplateService.markTemplateUsed(id, loginUser.getRowId());
        return ApiResponse.success(ExpenseTemplateApiDto.Response.from(info));
    }
}
