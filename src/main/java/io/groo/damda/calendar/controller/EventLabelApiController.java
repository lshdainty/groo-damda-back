package io.groo.damda.calendar.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.calendar.controller.dto.EventLabelApiDto;
import io.groo.damda.calendar.service.EventLabelService;
import io.groo.damda.calendar.service.dto.EventLabelServiceDto;
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
public class EventLabelApiController {
    private final EventLabelService eventLabelService;

    @PostMapping("/calendar/label")
    public ApiResponse<EventLabelApiDto.Response> createLabel(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody EventLabelApiDto.CreateRequest request) {
        EventLabelServiceDto.LabelInfo info = eventLabelService.createLabel(new EventLabelServiceDto.CreateCommand(
            loginUser.getRowId(),
            request.labelName(),
            request.color()
        ));
        return ApiResponse.success(EventLabelApiDto.Response.from(info));
    }

    @GetMapping("/calendar/labels")
    public ApiResponse<EventLabelApiDto.ListResponse> getLabels(
            @LoginUser UserPrincipal loginUser) {
        List<EventLabelServiceDto.LabelInfo> infos = eventLabelService.getLabels(loginUser.getRowId());
        return ApiResponse.success(EventLabelApiDto.ListResponse.from(infos));
    }

    @PutMapping("/calendar/label/{id}")
    public ApiResponse<EventLabelApiDto.Response> updateLabel(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @Valid @RequestBody EventLabelApiDto.UpdateRequest request) {
        EventLabelServiceDto.LabelInfo info = eventLabelService.updateLabel(id, loginUser.getRowId(), new EventLabelServiceDto.UpdateCommand(
            request.labelName(),
            request.color()
        ));
        return ApiResponse.success(EventLabelApiDto.Response.from(info));
    }

    @DeleteMapping("/calendar/label/{id}")
    public ApiResponse<Void> deleteLabel(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        eventLabelService.deleteLabel(id, loginUser.getRowId());
        return ApiResponse.success();
    }
}
