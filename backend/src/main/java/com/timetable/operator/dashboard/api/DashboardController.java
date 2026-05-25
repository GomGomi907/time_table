package com.timetable.operator.dashboard.api;

import com.timetable.operator.common.api.ApiEnvelope;
import com.timetable.operator.common.api.ApiResponses;
import com.timetable.operator.dashboard.application.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    public ResponseEntity<ApiEnvelope<DashboardService.DashboardSummaryResponse>> getSummary() {
        return ApiResponses.ok(dashboardService.getSummary());
    }
}
