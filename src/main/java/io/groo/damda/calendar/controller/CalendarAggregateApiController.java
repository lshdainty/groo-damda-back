package io.groo.damda.calendar.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.calendar.controller.dto.CalendarAggregateApiDto;
import io.groo.damda.calendar.service.CalendarAggregateService;
import io.groo.damda.calendar.service.dto.CalendarAggregateDto;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CalendarAggregateApiController {
    private final CalendarAggregateService calendarAggregateService;

    @GetMapping("/calendar/aggregate")
    public ApiResponse<CalendarAggregateApiDto.AggregateResponse> getAggregateData(
            @LoginUser UserPrincipal loginUser,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        CalendarAggregateDto.AggregateData data = calendarAggregateService.getAggregateData(
            loginUser.getRowId(), startDate, endDate
        );
        return ApiResponse.success(CalendarAggregateApiDto.AggregateResponse.from(data));
    }
}
