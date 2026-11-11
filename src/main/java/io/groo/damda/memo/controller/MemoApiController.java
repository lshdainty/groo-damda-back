package io.groo.damda.memo.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.common.patch.Patch;
import io.groo.damda.memo.controller.dto.MemoApiDto;
import io.groo.damda.memo.service.MemoService;
import io.groo.damda.memo.service.dto.MemoServiceDto;
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
public class MemoApiController {
    private final MemoService memoService;

    @PostMapping("/memo")
    public ApiResponse<MemoApiDto.Response> createMemo(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody MemoApiDto.CreateRequest request) {
        MemoServiceDto.MemoInfo info = memoService.createMemo(new MemoServiceDto.CreateCommand(
            loginUser.getRowId(),
            request.title(),
            request.content(),
            request.tag(),
            request.memoTagRowId(),
            request.color()
        ));
        return ApiResponse.success(MemoApiDto.Response.from(info));
    }

    @GetMapping("/memos")
    public ApiResponse<MemoApiDto.ListResponse> getMemos(
            @LoginUser UserPrincipal loginUser,
            @RequestParam(required = false) String search) {
        List<MemoServiceDto.MemoInfo> infos = memoService.getMemos(
            loginUser.getRowId(), search
        );
        return ApiResponse.success(MemoApiDto.ListResponse.from(infos));
    }

    @GetMapping("/memo/{id}")
    public ApiResponse<MemoApiDto.Response> getMemo(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        MemoServiceDto.MemoInfo info = memoService.getMemo(id, loginUser.getRowId());
        return ApiResponse.success(MemoApiDto.Response.from(info));
    }

    @PutMapping("/memo/{id}")
    public ApiResponse<MemoApiDto.Response> updateMemo(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @Valid @RequestBody MemoApiDto.UpdateRequest request) {
        MemoServiceDto.MemoInfo info = memoService.updateMemo(id, loginUser.getRowId(), new MemoServiceDto.UpdateCommand(
            Patch.from(request.title()),
            Patch.from(request.content()),
            Patch.from(request.tag()),
            Patch.from(request.memoTagRowId()),
            Patch.from(request.color())
        ));
        return ApiResponse.success(MemoApiDto.Response.from(info));
    }

    @PatchMapping("/memo/{id}/pin")
    public ApiResponse<MemoApiDto.Response> togglePin(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        MemoServiceDto.MemoInfo info = memoService.togglePin(id, loginUser.getRowId());
        return ApiResponse.success(MemoApiDto.Response.from(info));
    }

    @DeleteMapping("/memo/{id}")
    public ApiResponse<Void> deleteMemo(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        memoService.deleteMemo(id, loginUser.getRowId());
        return ApiResponse.success();
    }
}
