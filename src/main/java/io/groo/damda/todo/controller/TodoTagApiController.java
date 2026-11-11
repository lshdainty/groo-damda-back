package io.groo.damda.todo.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import io.groo.damda.todo.controller.dto.TodoTagApiDto;
import io.groo.damda.todo.service.TodoTagService;
import io.groo.damda.todo.service.dto.TodoTagServiceDto;
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

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TodoTagApiController {
    private final TodoTagService todoTagService;

    @PostMapping("/todo-tag")
    public ApiResponse<TodoTagApiDto.Response> createTag(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody TodoTagApiDto.CreateRequest request) {
        TodoTagServiceDto.TagInfo info = todoTagService.createTag(
            new TodoTagServiceDto.CreateCommand(
                loginUser.getRowId(),
                request.tagName(),
                request.color()
            )
        );
        return ApiResponse.success(TodoTagApiDto.Response.from(info));
    }

    @GetMapping("/todo-tags")
    public ApiResponse<TodoTagApiDto.ListResponse> getTags(
            @LoginUser UserPrincipal loginUser) {
        List<TodoTagServiceDto.TagInfo> infos = todoTagService.getTags(loginUser.getRowId());
        return ApiResponse.success(TodoTagApiDto.ListResponse.from(infos));
    }

    @PutMapping("/todo-tag/{id}")
    public ApiResponse<TodoTagApiDto.Response> updateTag(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @Valid @RequestBody TodoTagApiDto.UpdateRequest request) {
        TodoTagServiceDto.TagInfo info = todoTagService.updateTag(id, loginUser.getRowId(),
            new TodoTagServiceDto.UpdateCommand(request.tagName(), request.color())
        );
        return ApiResponse.success(TodoTagApiDto.Response.from(info));
    }

    @DeleteMapping("/todo-tag/{id}")
    public ApiResponse<Void> deleteTag(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        todoTagService.deleteTag(id, loginUser.getRowId());
        return ApiResponse.success();
    }
}
