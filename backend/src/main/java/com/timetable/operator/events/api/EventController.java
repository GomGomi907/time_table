package com.timetable.operator.events.api;

import com.timetable.operator.common.api.ApiEnvelope;
import com.timetable.operator.common.api.ApiResponses;
import com.timetable.operator.events.application.EventService;
import com.timetable.operator.events.domain.Event;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping
    public ApiEnvelope<List<EventResponse>> getEvents(
            @RequestParam Instant from,
            @RequestParam Instant to
    ) {
        return ApiEnvelope.ok(eventService.getEvents(from, to).stream().map(EventResponse::from).toList());
    }

    @PostMapping
    public ApiEnvelope<EventResponse> createEvent(@Valid @RequestBody EventService.EventWriteRequest request) {
        return ApiEnvelope.ok(EventResponse.from(eventService.createEvent(request)));
    }

    @PatchMapping("/{id}")
    public ApiEnvelope<EventResponse> updateEvent(
            @PathVariable UUID id,
            @Valid @RequestBody EventService.EventWriteRequest request
    ) {
        return ApiEnvelope.ok(EventResponse.from(eventService.updateEvent(id, request)));
    }

    @DeleteMapping("/{id}")
    public ApiEnvelope<EventResponse> deleteEvent(@PathVariable UUID id) {
        return ApiEnvelope.ok(EventResponse.from(eventService.deleteEvent(id)));
    }

    @PostMapping("/{id}/start")
    public ApiEnvelope<EventResponse> startEvent(
            @PathVariable UUID id,
            @Valid @RequestBody EventService.StartEventRequest request
    ) {
        return ApiEnvelope.ok(EventResponse.from(eventService.startEvent(id, request)));
    }

    @PostMapping("/{id}/complete")
    public ApiEnvelope<EventResponse> completeEvent(
            @PathVariable UUID id,
            @Valid @RequestBody EventService.CompleteEventRequest request
    ) {
        return ApiEnvelope.ok(EventResponse.from(eventService.completeEvent(id, request)));
    }

    @PostMapping("/{id}/postpone")
    public ApiEnvelope<EventResponse> postponeEvent(
            @PathVariable UUID id,
            @RequestBody EventService.PostponeEventRequest request
    ) {
        return ApiEnvelope.ok(EventResponse.from(eventService.postponeEvent(id, request)));
    }

    @PostMapping("/{id}/extend")
    public ApiEnvelope<EventResponse> extendEvent(
            @PathVariable UUID id,
            @Valid @RequestBody EventService.ExtendEventRequest request
    ) {
        return ApiEnvelope.ok(EventResponse.from(eventService.extendEvent(id, request)));
    }

    public record EventResponse(
            String id,
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            Instant actualStartAt,
            Instant actualEndAt,
            short priority,
            String status,
            String category,
            String sourceType,
            String syncState,
            String goalId,
            String externalSourceId
    ) {
        static EventResponse from(Event event) {
            return new EventResponse(
                    event.getId().toString(),
                    event.getTitle(),
                    event.getDescription(),
                    event.getStartAt(),
                    event.getEndAt(),
                    event.getActualStartAt(),
                    event.getActualEndAt(),
                    event.getPriority(),
                    event.getStatus().name(),
                    event.getCategory().name(),
                    event.getSourceType().name(),
                    event.getSyncState().name(),
                    event.getGoalId() == null ? null : event.getGoalId().toString(),
                    event.getExternalSourceId()
            );
        }
    }
}
