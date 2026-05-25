package com.timetable.operator.events.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.domain.PlannerSyncState;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.domain.EventSourceType;
import com.timetable.operator.events.domain.EventStatus;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.sync.application.ProviderWriteOutboxService;
import com.timetable.operator.sync.domain.ProviderWriteOperation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ProviderWriteOutboxService providerWriteOutboxService;

    @Transactional(readOnly = true)
    public List<Event> getEvents(Instant from, Instant to) {
        AppUser user = currentUserProvider.getCurrentUser();
        return eventRepository.findByUserIdAndStatusNotAndStartAtBeforeAndEndAtAfterOrderByStartAtAsc(
                        user.getId(),
                        EventStatus.CANCELLED,
                        to,
                        from
                ).stream()
                .filter(event -> event.getSyncState() != PlannerSyncState.DETACHED)
                .toList();
    }

    @Transactional(readOnly = true)
    public Event getEvent(UUID id) {
        return getEditableEvent(id);
    }

    @Transactional
    public Event createEvent(EventWriteRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        validateRange(request.startAt(), request.endAt());

        Event event = new Event();
        event.setUserId(user.getId());
        applyWritableFields(event, request);
        event.setSourceType(EventSourceType.LOCAL);
        event.setSyncState(PlannerSyncState.LOCAL_ONLY);
        Event saved = eventRepository.save(event);
        providerWriteOutboxService.enqueueEventWrite(saved, ProviderWriteOperation.CREATE);
        return eventRepository.save(saved);
    }

    @Transactional
    public Event updateEvent(UUID id, EventWriteRequest request) {
        validateRange(request.startAt(), request.endAt());
        Event editable = getEditableEvent(id);
        applyWritableFields(editable, request);
        providerWriteOutboxService.enqueueEventWrite(editable, ProviderWriteOperation.UPDATE);
        return eventRepository.save(editable);
    }

    @Transactional
    public Event deleteEvent(UUID id) {
        Event editable = getEditableEvent(id);
        editable.setStatus(EventStatus.CANCELLED);
        providerWriteOutboxService.enqueueEventWrite(editable, ProviderWriteOperation.DELETE);
        return eventRepository.save(editable);
    }

    @Transactional
    public Event startEvent(UUID id, StartEventRequest request) {
        Event editable = getEditableEvent(id);
        editable.setActualStartAt(request.startedAt());
        editable.setStatus(EventStatus.ACTIVE);
        providerWriteOutboxService.enqueueEventWrite(editable, ProviderWriteOperation.UPDATE);
        return eventRepository.save(editable);
    }

    @Transactional
    public Event completeEvent(UUID id, CompleteEventRequest request) {
        Event editable = getEditableEvent(id);
        if (editable.getActualStartAt() == null) {
            editable.setActualStartAt(editable.getStartAt());
        }
        editable.setActualEndAt(request.completedAt());
        editable.setStatus(EventStatus.COMPLETED);
        providerWriteOutboxService.enqueueEventWrite(editable, ProviderWriteOperation.UPDATE);
        return eventRepository.save(editable);
    }

    @Transactional
    public Event postponeEvent(UUID id, PostponeEventRequest request) {
        Event editable = getEditableEvent(id);
        editable.setStatus(EventStatus.POSTPONED);
        if (request.preferredWindowStart() != null) {
            editable.setStartAt(request.preferredWindowStart());
        }
        if (request.preferredWindowEnd() != null) {
            editable.setEndAt(request.preferredWindowEnd());
        }
        validateRange(editable.getStartAt(), editable.getEndAt());
        providerWriteOutboxService.enqueueEventWrite(editable, ProviderWriteOperation.UPDATE);
        return eventRepository.save(editable);
    }

    @Transactional
    public Event extendEvent(UUID id, ExtendEventRequest request) {
        Event editable = getEditableEvent(id);
        editable.setEndAt(editable.getEndAt().plusSeconds(request.extendMinutes() * 60L));
        providerWriteOutboxService.enqueueEventWrite(editable, ProviderWriteOperation.UPDATE);
        return eventRepository.save(editable);
    }

    private Event getEditableEvent(UUID id) {
        AppUser user = currentUserProvider.getCurrentUser();
        Event event = eventRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("해당 이벤트를 찾을 수 없습니다."));
        return event;
    }

    private void applyWritableFields(Event event, EventWriteRequest request) {
        event.setTitle(request.title().trim());
        event.setDescription(blankToNull(request.description()));
        event.setStartAt(request.startAt());
        event.setEndAt(request.endAt());
        event.setPriority(request.priority());
        event.setCategory(request.category());
        event.setGoalId(request.goalId());
    }

    private void validateRange(Instant startAt, Instant endAt) {
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("이벤트 종료 시각은 시작 시각보다 늦어야 합니다.");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record EventWriteRequest(
            @NotBlank String title,
            String description,
            @NotNull Instant startAt,
            @NotNull Instant endAt,
            @Min(1) @Max(5) short priority,
            @NotNull ScheduleCategory category,
            UUID goalId
    ) {
    }

    public record StartEventRequest(
            @NotNull Instant startedAt,
            @NotBlank String triggerSource
    ) {
    }

    public record CompleteEventRequest(
            @NotNull Instant completedAt,
            @NotBlank String completionType,
            String memo
    ) {
    }

    public record PostponeEventRequest(
            String reason,
            String mode,
            Instant preferredWindowStart,
            Instant preferredWindowEnd
    ) {
    }

    public record ExtendEventRequest(
            @Min(1) int extendMinutes,
            String reason
    ) {
    }
}
