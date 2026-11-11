package io.groo.damda.todo.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import io.groo.damda.common.patch.Patch;
import io.groo.damda.todo.controller.dto.TodoApiDto;
import io.groo.damda.todo.service.TodoService;
import io.groo.damda.todo.service.dto.TodoServiceDto;
import io.groo.damda.todo.type.TodoPriority;
import io.groo.damda.todo.type.TodoStatus;
import io.groo.damda.todo.type.TodoType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TodoApiController {
    private final TodoService todoService;

    @PostMapping("/todo")
    public ApiResponse<TodoApiDto.Response> createTodo(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody TodoApiDto.CreateRequest request) {
        TodoServiceDto.TodoInfo info = todoService.createTodo(new TodoServiceDto.CreateCommand(
            loginUser.getRowId(),
            request.title(),
            request.content(),
            request.priority(),
            request.category(),
            request.dueDate(),
            request.tagIds(),
            request.type()
        ));
        return ApiResponse.success(TodoApiDto.Response.from(info));
    }

    @GetMapping("/todos")
    public ApiResponse<TodoApiDto.ListResponse> getTodos(
            @LoginUser UserPrincipal loginUser,
            @RequestParam(required = false) TodoStatus status,
            @RequestParam(required = false) TodoPriority priority,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) TodoType type) {
        List<TodoServiceDto.TodoInfo> infos = todoService.getTodos(
            loginUser.getRowId(), status, priority, category, startDate, endDate, type
        );
        return ApiResponse.success(TodoApiDto.ListResponse.from(infos));
    }

    @GetMapping("/todo/{id}")
    public ApiResponse<TodoApiDto.Response> getTodo(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        TodoServiceDto.TodoInfo info = todoService.getTodo(id, loginUser.getRowId());
        return ApiResponse.success(TodoApiDto.Response.from(info));
    }

    @PutMapping("/todo/{id}")
    public ApiResponse<TodoApiDto.Response> updateTodo(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @Valid @RequestBody TodoApiDto.UpdateRequest request) {
        TodoServiceDto.TodoInfo info = todoService.updateTodo(id, loginUser.getRowId(), new TodoServiceDto.UpdateCommand(
            Patch.from(request.title()),
            Patch.from(request.content()),
            Patch.from(request.priority()),
            Patch.from(request.category()),
            Patch.from(request.dueDate()),
            request.tagIds()
        ));
        return ApiResponse.success(TodoApiDto.Response.from(info));
    }

    /**
     * 상태 변경. 본문 {@code {"status":"IN_PROGRESS"}} 를 실으면 <b>그 상태로</b> 바꾼다.
     *
     * <p><b>본문은 필수가 아니다.</b> 웹({@code todoApi.toggleTodoStatus})이 본문 없이 부르고,
     * 그 뜻은 종전대로 "완료 ↔ 대기 토글" 이다. 필수로 걸면 지금 되는 화면이 400 을 맞는다.
     * 앱은 {@code {"status":"..."}} 를 보내고 있었는데 서버가 그걸 읽지 않아 진행 중을 고르면
     * 완료가 됐다(QA #93) — 읽는 쪽을 고친다.
     *
     * <p>없는 상태 값({@code "DONE"} 등)은 Jackson 역직렬화에서 걸려
     * core 의 {@code GlobalExceptionHandler} 가 400 으로 답한다.
     */
    @PatchMapping("/todo/{id}/status")
    public ApiResponse<TodoApiDto.Response> changeStatus(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @RequestBody(required = false) TodoApiDto.StatusUpdateRequest request) {
        TodoServiceDto.TodoInfo info = todoService.changeStatus(
            id, loginUser.getRowId(), request != null ? request.status() : null);
        return ApiResponse.success(TodoApiDto.Response.from(info));
    }

    @PatchMapping("/todo/{id}/pin")
    public ApiResponse<TodoApiDto.Response> togglePin(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        TodoServiceDto.TodoInfo info = todoService.togglePin(id, loginUser.getRowId());
        return ApiResponse.success(TodoApiDto.Response.from(info));
    }

    @PatchMapping("/todos/reorder")
    public ApiResponse<Void> reorderTodos(
            @LoginUser UserPrincipal loginUser,
            @RequestBody TodoApiDto.ReorderRequest request) {
        List<TodoServiceDto.ReorderCommand.ReorderItem> items = request.items().stream()
            .map(item -> new TodoServiceDto.ReorderCommand.ReorderItem(item.todoId(), item.sortOrder()))
            .toList();
        todoService.reorderTodos(loginUser.getRowId(), new TodoServiceDto.ReorderCommand(items));
        return ApiResponse.success();
    }

    @DeleteMapping("/todo/{id}")
    public ApiResponse<Void> deleteTodo(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        todoService.deleteTodo(id, loginUser.getRowId());
        return ApiResponse.success();
    }

    // GET /todo/{id}/subtasks 는 없앴다 — 하위 할 일 개념째 걷었다(사용자 결정 2026-09-08).
    //
    // 빈 배열을 돌려주는 껍데기로 남기지 않은 이유: 그러면 옛 앱의 하위 할 일 칸이 "아직 하나도
    // 없음" 으로 그려지고, 거기 제목을 적으면 서버는 parentRowId 를 무시해 보통 할 일로
    // 만든다. 화면은 다시 빈 목록을 받으므로 사용자 눈에는 적은 것이 사라진다 — 에러 없이 틀리는
    // 쪽이다. 404 는 그 자리에서 "불러오지 못했어요" 로 보이고, 적은 것이 사라지는 일은 없다.
    //
    // 앱은 이 칸을 같은 라운드에 걷는다. 그때까지 옛 앱은 할 일 편집 창의 그 칸에서만 실패하고
    // 나머지는 멀쩡하다 — 눈에 보이는 실패라 minBuildNumber 는 올리지 않는다(레포 CLAUDE.md 기준).

    /**
     * 태그 일괄 지정. <b>{@code tagIds} 는 필수</b>고, 빈 배열만 "전부 해제" 로 인정한다(QA #87).
     * 왜 그렇게 갈랐는지는 {@link TodoApiDto.TagUpdateRequest} 에 적어 뒀다.
     */
    @PatchMapping("/todo/{id}/tags")
    public ApiResponse<Void> updateTags(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @Valid @RequestBody TodoApiDto.TagUpdateRequest request) {
        todoService.updateTags(id, loginUser.getRowId(), request.tagIds());
        return ApiResponse.success();
    }

    @GetMapping("/todos/stats")
    public ApiResponse<TodoApiDto.StatsResponse> getStats(
            @LoginUser UserPrincipal loginUser) {
        TodoServiceDto.TodoStats stats = todoService.getStats(loginUser.getRowId());
        return ApiResponse.success(TodoApiDto.StatsResponse.from(stats));
    }
}
