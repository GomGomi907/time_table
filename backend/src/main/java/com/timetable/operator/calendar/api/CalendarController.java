package com.timetable.operator.calendar.api;

import com.timetable.operator.calendar.application.CalendarRangeService;
import com.timetable.operator.common.api.ApiEnvelope;
import com.timetable.operator.common.api.ApiResponses;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final CalendarRangeService calendarRangeService;

    @GetMapping("/range")
    public ResponseEntity<ApiEnvelope<CalendarRangeService.CalendarRangeResponse>> getRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
            @RequestParam(defaultValue = "week") String view,
            @RequestParam(required = false) String timezone
    ) {
        return ApiResponses.ok(calendarRangeService.getRange(
                start,
                end,
                CalendarRangeService.CalendarView.from(view),
                timezone
        ));
    }
}
