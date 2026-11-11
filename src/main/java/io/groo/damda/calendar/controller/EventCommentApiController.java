package io.groo.damda.calendar.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.calendar.controller.dto.EventCommentApiDto;
import io.groo.damda.calendar.service.EventCommentService;
import io.groo.damda.calendar.service.dto.EventCommentServiceDto;
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

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class EventCommentApiController {
    private final EventCommentService eventCommentService;

    @PostMapping("/calendar/event/{eventId}/comment")
    public ApiResponse<EventCommentApiDto.Response> createComment(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long eventId,
            @Valid @RequestBody EventCommentApiDto.CreateRequest request) {
        EventCommentServiceDto.CreateCommand command = new EventCommentServiceDto.CreateCommand(
            eventId,
            loginUser.getRowId(),
            request.parentRowId(),
            request.content()
        );
        EventCommentServiceDto.CommentInfo info = eventCommentService.createComment(command);
        return ApiResponse.success(EventCommentApiDto.Response.from(info));
    }

    @GetMapping("/calendar/event/{eventId}/comments")
    public ApiResponse<EventCommentApiDto.ListResponse> getComments(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long eventId) {
        List<EventCommentServiceDto.CommentInfo> infos = eventCommentService.getComments(eventId, loginUser.getRowId());
        return ApiResponse.success(EventCommentApiDto.ListResponse.from(infos));
    }

    @PutMapping("/calendar/comment/{commentId}")
    public ApiResponse<EventCommentApiDto.Response> updateComment(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long commentId,
            @Valid @RequestBody EventCommentApiDto.UpdateRequest request) {
        EventCommentServiceDto.UpdateCommand command = new EventCommentServiceDto.UpdateCommand(
            commentId,
            request.content()
        );
        EventCommentServiceDto.CommentInfo info = eventCommentService.updateComment(loginUser.getRowId(), command);
        return ApiResponse.success(EventCommentApiDto.Response.from(info));
    }

    @DeleteMapping("/calendar/comment/{commentId}")
    public ApiResponse<Void> deleteComment(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long commentId) {
        eventCommentService.deleteComment(commentId, loginUser.getRowId());
        return ApiResponse.success();
    }
}
