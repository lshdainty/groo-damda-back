package io.groo.damda.savingGoal.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.common.patch.Patch;
import io.groo.damda.savingGoal.controller.dto.SavingGoalApiDto;
import io.groo.damda.savingGoal.service.SavingGoalService;
import io.groo.damda.savingGoal.service.dto.SavingGoalServiceDto;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SavingGoalApiController {
    private final SavingGoalService savingGoalService;

    @PostMapping("/saving-goal")
    public ApiResponse<SavingGoalApiDto.SavingGoalResponse> createSavingGoal(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody SavingGoalApiDto.CreateSavingGoalRequest request) {
        SavingGoalServiceDto.GoalInfo info = savingGoalService.createSavingGoal(
            new SavingGoalServiceDto.CreateCommand(
                loginUser.getRowId(),
                request.title(),
                request.description(),
                request.targetAmount(),
                request.currency(),
                request.deadlineDate(),
                request.icon(),
                request.color(),
                request.linkedAssetRowId(),
                request.sortOrder()
            )
        );
        return ApiResponse.success(SavingGoalApiDto.SavingGoalResponse.from(info));
    }

    @GetMapping("/saving-goals")
    public ApiResponse<SavingGoalApiDto.SavingGoalListResponse> getSavingGoals(@LoginUser UserPrincipal loginUser) {
        List<SavingGoalServiceDto.GoalInfo> infos = savingGoalService.getSavingGoals(loginUser.getRowId());
        return ApiResponse.success(SavingGoalApiDto.SavingGoalListResponse.from(infos));
    }

    @GetMapping("/saving-goal/{id}")
    public ApiResponse<SavingGoalApiDto.SavingGoalResponse> getSavingGoal(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        SavingGoalServiceDto.GoalInfo info = savingGoalService.getSavingGoal(id, loginUser.getRowId());
        return ApiResponse.success(SavingGoalApiDto.SavingGoalResponse.from(info));
    }

    @PutMapping("/saving-goal/{id}")
    public ApiResponse<SavingGoalApiDto.SavingGoalResponse> updateSavingGoal(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @Valid @RequestBody SavingGoalApiDto.UpdateSavingGoalRequest request) {
        SavingGoalServiceDto.GoalInfo info = savingGoalService.updateSavingGoal(
            id, loginUser.getRowId(),
            new SavingGoalServiceDto.UpdateCommand(
                Patch.from(request.title()),
                Patch.from(request.description()),
                Patch.from(request.targetAmount()),
                Patch.from(request.deadlineDate()),
                Patch.from(request.icon()),
                Patch.from(request.color()),
                Patch.from(request.linkedAssetRowId())
            )
        );
        return ApiResponse.success(SavingGoalApiDto.SavingGoalResponse.from(info));
    }

    @PatchMapping("/saving-goal/{id}/contribute")
    public ApiResponse<SavingGoalApiDto.SavingGoalResponse> contribute(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @Valid @RequestBody SavingGoalApiDto.ContributeRequest request) {
        SavingGoalServiceDto.GoalInfo info = savingGoalService.contribute(
            id, loginUser.getRowId(),
            new SavingGoalServiceDto.ContributeCommand(request.amount(), request.note())
        );
        return ApiResponse.success(SavingGoalApiDto.SavingGoalResponse.from(info));
    }

    @DeleteMapping("/saving-goal/{id}")
    public ApiResponse<Void> deleteSavingGoal(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        savingGoalService.deleteSavingGoal(id, loginUser.getRowId());
        return ApiResponse.success();
    }

    @PatchMapping("/saving-goals/reorder")
    public ApiResponse<Void> reorderSavingGoals(
            @LoginUser UserPrincipal loginUser,
            @RequestBody SavingGoalApiDto.ReorderRequest request) {
        savingGoalService.reorderSavingGoals(
            loginUser.getRowId(),
            request.items().stream()
                .map(i -> new SavingGoalServiceDto.ReorderItem(i.id(), i.sortOrder()))
                .toList()
        );
        return ApiResponse.success();
    }
}
