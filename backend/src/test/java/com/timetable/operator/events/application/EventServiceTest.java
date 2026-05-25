package com.timetable.operator.events.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.domain.PlannerSyncState;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.domain.EventStatus;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.sync.application.ProviderWriteOutboxService;
import com.timetable.operator.sync.domain.ProviderWriteOperation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private ProviderWriteOutboxService providerWriteOutboxService;

    @InjectMocks
    private EventService eventService;

    @Test
    void importedEventUpdateKeepsOriginalRowAndEnqueuesProviderWrite() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        AppUser user = user(userId);
        Event imported = importedEvent(userId, eventId);

        when(currentUserProvider.getCurrentUser()).thenReturn(user);
        when(eventRepository.findByIdAndUserId(eventId, userId)).thenReturn(Optional.of(imported));
        when(eventRepository.save(same(imported))).thenReturn(imported);

        Event result = eventService.updateEvent(eventId, new EventService.EventWriteRequest(
                "Updated planning",
                "Move earlier",
                Instant.parse("2026-05-15T01:00:00Z"),
                Instant.parse("2026-05-15T02:00:00Z"),
                (short) 2,
                ScheduleCategory.WORK,
                null
        ));

        assertThat(result).isSameAs(imported);
        assertThat(result.getId()).isEqualTo(eventId);
        assertThat(result.getSyncState()).isEqualTo(PlannerSyncState.IMPORTED);
        verify(providerWriteOutboxService).enqueueEventWrite(imported, ProviderWriteOperation.UPDATE);
        verify(eventRepository).save(same(imported));
    }

    @Test
    void rangeLookupIncludesOverlappingEventsAndExcludesCancelledRows() {
        UUID userId = UUID.randomUUID();
        AppUser user = user(userId);
        Event overlapping = importedEvent(userId, UUID.randomUUID());
        Event cancelled = importedEvent(userId, UUID.randomUUID());
        cancelled.setStatus(EventStatus.CANCELLED);
        Instant from = Instant.parse("2026-05-15T00:30:00Z");
        Instant to = Instant.parse("2026-05-15T02:30:00Z");

        when(currentUserProvider.getCurrentUser()).thenReturn(user);
        when(eventRepository.findByUserIdAndStatusNotAndStartAtBeforeAndEndAtAfterOrderByStartAtAsc(
                userId,
                EventStatus.CANCELLED,
                to,
                from
        )).thenReturn(List.of(overlapping));

        List<Event> result = eventService.getEvents(from, to);

        assertThat(result).containsExactly(overlapping);
        assertThat(result).doesNotContain(cancelled);
        verifyNoInteractions(providerWriteOutboxService);
    }

    @Test
    void completeEventWithoutActualStartUsesPlannedStartNotCompletedAt() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        AppUser user = user(userId);
        Event imported = importedEvent(userId, eventId);

        when(currentUserProvider.getCurrentUser()).thenReturn(user);
        when(eventRepository.findByIdAndUserId(eventId, userId)).thenReturn(Optional.of(imported));
        when(eventRepository.save(same(imported))).thenReturn(imported);

        Event result = eventService.completeEvent(eventId, new EventService.CompleteEventRequest(
                Instant.parse("2026-05-15T03:00:00Z"),
                "late",
                null
        ));

        assertThat(result.getActualStartAt()).isEqualTo(Instant.parse("2026-05-15T00:00:00Z"));
        assertThat(result.getActualEndAt()).isEqualTo(Instant.parse("2026-05-15T03:00:00Z"));
        verify(providerWriteOutboxService).enqueueEventWrite(imported, ProviderWriteOperation.UPDATE);
    }

    private AppUser user(UUID userId) {
        AppUser user = new AppUser();
        user.setId(userId);
        user.setEmail("user@example.com");
        user.setDisplayName("User");
        user.setProvider("local");
        return user;
    }

    private Event importedEvent(UUID userId, UUID eventId) {
        Event event = new Event();
        event.setId(eventId);
        event.setUserId(userId);
        event.setTitle("Planning");
        event.setStartAt(Instant.parse("2026-05-15T00:00:00Z"));
        event.setEndAt(Instant.parse("2026-05-15T01:00:00Z"));
        event.setStatus(EventStatus.PLANNED);
        event.setCategory(ScheduleCategory.WORK);
        event.setSyncState(PlannerSyncState.IMPORTED);
        event.setExternalSourceId("google_calendar:event-1");
        event.setExternalEtag("\"etag-1\"");
        return event;
    }
}
