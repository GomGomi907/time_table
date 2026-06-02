package com.timetable.operator.schedule.api;

import com.timetable.operator.schedule.application.ScheduleService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedule")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    @GetMapping("/week")
    public ScheduleService.WeekScheduleResponse getWeeklySchedule() {
        return scheduleService.getWeeklySchedule();
    }

    @GetMapping("/conflicts/preflight")
    public ScheduleService.ScheduleMutationPreflightResponse getMutationPreflight() {
        return scheduleService.getMutationPreflight();
    }

    @PostMapping("/import")
    public ScheduleService.WeekScheduleResponse importSchedule(
            @Valid @RequestBody ScheduleService.ImportScheduleRequest request
    ) {
        return scheduleService.importSchedule(request);
    }

    @PostMapping("/blocks")
    public ScheduleService.TimeBlockResponse createBlock(
            @Valid @RequestBody ScheduleService.ScheduleBlockWriteRequest request
    ) {
        return scheduleService.createBlock(request);
    }

    @PutMapping("/blocks/{blockId}")
    public ScheduleService.TimeBlockResponse updateBlock(
            @PathVariable UUID blockId,
            @Valid @RequestBody ScheduleService.ScheduleBlockWriteRequest request
    ) {
        return scheduleService.updateBlock(blockId, request);
    }

    @DeleteMapping("/blocks/{blockId}")
    public void deleteBlock(@PathVariable UUID blockId) {
        scheduleService.deleteBlock(blockId);
    }
}
