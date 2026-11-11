package io.groo.damda.calendar.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.calendar.controller.dto.CalendarEventApiDto;
import io.groo.damda.calendar.service.CalendarEventService;
import io.groo.damda.calendar.service.dto.CalendarEventServiceDto;
import io.groo.damda.common.patch.Patch;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CalendarEventApiController {
    private final CalendarEventService calendarEventService;

    @PostMapping("/calendar/event")
    public ApiResponse<CalendarEventApiDto.Response> createEvent(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody CalendarEventApiDto.CreateRequest request) {
        CalendarEventServiceDto.EventInfo info = calendarEventService.createEvent(new CalendarEventServiceDto.CreateCommand(
            loginUser.getRowId(),
            request.title(),
            request.description(),
            request.eventType(),
            request.color(),
            request.startDate(),
            request.endDate(),
            request.isAllDay(),
            request.labelRowId(),
            request.location(),
            request.rrule(),
            request.reminderMinutes(),
            request.calendarRowId()
        ));
        return ApiResponse.success(CalendarEventApiDto.Response.from(info));
    }

    @GetMapping("/calendar/events")
    public ApiResponse<CalendarEventApiDto.ListResponse> getEvents(
            @LoginUser UserPrincipal loginUser,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        List<CalendarEventServiceDto.EventInfo> infos = calendarEventService.getEvents(
            loginUser.getRowId(), startDate, endDate
        );
        return ApiResponse.success(CalendarEventApiDto.ListResponse.from(infos));
    }

    @PutMapping("/calendar/event/{id}")
    public ApiResponse<CalendarEventApiDto.Response> updateEvent(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @Valid @RequestBody CalendarEventApiDto.UpdateRequest request) {
        CalendarEventServiceDto.EventInfo info = calendarEventService.updateEvent(id, loginUser.getRowId(), new CalendarEventServiceDto.UpdateCommand(
            request.title(),
            Patch.from(request.description()),
            request.eventType(),
            Patch.from(request.color()),
            request.startDate(),
            request.endDate(),
            request.isAllDay(),
            Patch.from(request.labelRowId()),
            Patch.from(request.location()),
            Patch.from(request.rrule()),
            request.reminderMinutes(),
            Patch.from(request.calendarRowId()),
            request.occurrenceStartDate()
        ));
        return ApiResponse.success(CalendarEventApiDto.Response.from(info));
    }

    @DeleteMapping("/calendar/event/{id}")
    public ApiResponse<Void> deleteEvent(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        calendarEventService.deleteEvent(id, loginUser.getRowId());
        return ApiResponse.success();
    }
}
