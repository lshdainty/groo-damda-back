package io.groo.damda.dashboard.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.dashboard.controller.dto.DashboardApiDto;
import io.groo.damda.dashboard.service.DashboardService;
import io.groo.damda.dashboard.service.dto.DashboardServiceDto;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DashboardApiController {
    private final DashboardService dashboardService;

    @GetMapping("/dashboard/summary")
    public ApiResponse<DashboardApiDto.SummaryResponse> getDashboardSummary(
            @LoginUser UserPrincipal loginUser) {
        DashboardServiceDto.DashboardSummary summary = dashboardService.getDashboardSummary(loginUser.getRowId());
        return ApiResponse.success(DashboardApiDto.SummaryResponse.from(summary));
    }

    @GetMapping("/dashboard/layout")
    public ApiResponse<DashboardApiDto.LayoutResponse> getDashboardLayout(
            @LoginUser UserPrincipal loginUser) {
        String dashboard = dashboardService.getDashboardLayout(loginUser.getRowId());
        return ApiResponse.success(new DashboardApiDto.LayoutResponse(dashboard));
    }

    @PatchMapping("/dashboard/layout")
    public ApiResponse<DashboardApiDto.LayoutResponse> updateDashboardLayout(
            @LoginUser UserPrincipal loginUser,
            @RequestBody DashboardApiDto.UpdateLayoutRequest request) {
        String dashboard = dashboardService.updateDashboardLayout(loginUser.getRowId(), request.dashboard());
        return ApiResponse.success(new DashboardApiDto.LayoutResponse(dashboard));
    }
}
