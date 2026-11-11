package io.groo.damda.memo.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.memo.controller.dto.MemoTagApiDto;
import io.groo.damda.memo.service.MemoTagService;
import io.groo.damda.memo.service.dto.MemoTagServiceDto;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import jakarta.validation.Valid;
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
 * 메모 태그 관리 API — 경로·본문·응답 모두 {@code /todo-tag} 와 같은 모양이다.
 * 설정 화면이 할 일 태그와 메모 태그를 나란히 놓으므로 두 API 가 갈라질 이유가 없다.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MemoTagApiController {
    private final MemoTagService memoTagService;

    @PostMapping("/memo-tag")
    public ApiResponse<MemoTagApiDto.Response> createTag(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody MemoTagApiDto.CreateRequest request) {
        MemoTagServiceDto.TagInfo info = memoTagService.createTag(
            new MemoTagServiceDto.CreateCommand(
                loginUser.getRowId(),
                request.tagName(),
                request.color()
            )
        );
        return ApiResponse.success(MemoTagApiDto.Response.from(info));
    }

    @GetMapping("/memo-tags")
    public ApiResponse<MemoTagApiDto.ListResponse> getTags(
            @LoginUser UserPrincipal loginUser) {
        List<MemoTagServiceDto.TagInfo> infos = memoTagService.getTags(loginUser.getRowId());
        return ApiResponse.success(MemoTagApiDto.ListResponse.from(infos));
    }

    @PutMapping("/memo-tag/{id}")
    public ApiResponse<MemoTagApiDto.Response> updateTag(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @Valid @RequestBody MemoTagApiDto.UpdateRequest request) {
        MemoTagServiceDto.TagInfo info = memoTagService.updateTag(id, loginUser.getRowId(),
            new MemoTagServiceDto.UpdateCommand(request.tagName(), request.color())
        );
        return ApiResponse.success(MemoTagApiDto.Response.from(info));
    }

    @DeleteMapping("/memo-tag/{id}")
    public ApiResponse<Void> deleteTag(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        memoTagService.deleteTag(id, loginUser.getRowId());
        return ApiResponse.success();
    }
}
