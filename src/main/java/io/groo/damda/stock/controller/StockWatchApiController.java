package io.groo.damda.stock.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import io.groo.damda.stock.controller.dto.StockWatchApiDto;
import io.groo.damda.stock.service.StockWatchService;
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

/**
 * 증권 관심목록 (그룹 + 종목).
 *
 * <p>종목 검색과 마찬가지로 구독 게이트 없이 로그인 사용자 전체에게 연다.
 * 시세는 클라이언트가 기존 /api/v1/toss/** (SECURITIES 게이트)로 별도 조회한다.
 */
@RestController
@RequestMapping("/api/v1/stock-watch")
@RequiredArgsConstructor
public class StockWatchApiController {
    private final StockWatchService stockWatchService;

    @GetMapping("/groups")
    public ApiResponse<List<StockWatchApiDto.GroupResponse>> getGroups(@LoginUser UserPrincipal loginUser) {
        return ApiResponse.success(stockWatchService.getGroups(loginUser.getRowId()).stream()
            .map(StockWatchApiDto.GroupResponse::from)
            .toList());
    }

    @PostMapping("/groups")
    public ApiResponse<StockWatchApiDto.GroupResponse> createGroup(
        @LoginUser UserPrincipal loginUser,
        @RequestBody StockWatchApiDto.GroupRequest request) {
        return ApiResponse.success(StockWatchApiDto.GroupResponse.from(
            stockWatchService.createGroup(loginUser.getRowId(), request.groupName())));
    }

    @PutMapping("/groups/{groupId}")
    public ApiResponse<StockWatchApiDto.GroupResponse> renameGroup(
        @LoginUser UserPrincipal loginUser,
        @PathVariable Long groupId,
        @RequestBody StockWatchApiDto.GroupRequest request) {
        return ApiResponse.success(StockWatchApiDto.GroupResponse.from(
            stockWatchService.renameGroup(loginUser.getRowId(), groupId, request.groupName())));
    }

    @DeleteMapping("/groups/{groupId}")
    public ApiResponse<Void> deleteGroup(
        @LoginUser UserPrincipal loginUser,
        @PathVariable Long groupId) {
        stockWatchService.deleteGroup(loginUser.getRowId(), groupId);
        return ApiResponse.success();
    }

    @PostMapping("/groups/{groupId}/items")
    public ApiResponse<StockWatchApiDto.ItemResponse> addItem(
        @LoginUser UserPrincipal loginUser,
        @PathVariable Long groupId,
        @RequestBody StockWatchApiDto.ItemRequest request) {
        return ApiResponse.success(StockWatchApiDto.ItemResponse.from(
            stockWatchService.addItem(loginUser.getRowId(), groupId, request.symbol(), request.marketCode())));
    }

    @DeleteMapping("/items/{itemId}")
    public ApiResponse<Void> removeItem(
        @LoginUser UserPrincipal loginUser,
        @PathVariable Long itemId) {
        stockWatchService.removeItem(loginUser.getRowId(), itemId);
        return ApiResponse.success();
    }
}
