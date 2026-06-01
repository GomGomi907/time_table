package com.timetable.operator.sync.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.auth.infrastructure.AppUserRepository;
import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.calendar.domain.CalendarConnectionStatus;
import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import com.timetable.operator.common.domain.PlannerSyncState;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.domain.EventSourceType;
import com.timetable.operator.events.domain.EventStatus;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.sync.application.GoogleInboundSyncClient;
import com.timetable.operator.sync.application.GoogleOutboundSyncClient;
import com.timetable.operator.sync.application.ProviderWriteOutboxService;
import com.timetable.operator.sync.application.ProviderWriteProcessor;
import com.timetable.operator.sync.application.SyncOrchestrationService;
import com.timetable.operator.sync.domain.ProviderWriteOperation;
import com.timetable.operator.sync.domain.ProviderWriteOutbox;
import com.timetable.operator.sync.domain.ProviderWriteState;
import com.timetable.operator.sync.domain.SyncMappingLocalType;
import com.timetable.operator.sync.domain.SyncProvider;
import com.timetable.operator.sync.infrastructure.ProviderWriteOutboxRepository;
import com.timetable.operator.tasks.domain.Task;
import com.timetable.operator.tasks.domain.TaskSourceType;
import com.timetable.operator.tasks.domain.TaskStatus;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sync-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.ai.enabled=false",
        "app.sync.polling.enabled=false"
})
@AutoConfigureMockMvc
class SyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private CalendarConnectionRepository calendarConnectionRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ProviderWriteOutboxRepository providerWriteOutboxRepository;

    @Autowired
    private ProviderWriteOutboxService providerWriteOutboxService;

    @Autowired
    private ProviderWriteProcessor providerWriteProcessor;

    @Autowired
    private SyncOrchestrationService syncOrchestrationService;

    @MockitoBean
    private GoogleInboundSyncClient googleInboundSyncClient;

    @MockitoBean
    private GoogleOutboundSyncClient googleOutboundSyncClient;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(providerWriteProcessor, "googleWriteEnabled", true);
        ReflectionTestUtils.setField(syncOrchestrationService, "googleWriteEnabled", true);
        providerWriteOutboxRepository.deleteAll();

        when(googleInboundSyncClient.importCalendar(any(CalendarConnection.class), any(Instant.class), any(Instant.class)))
                .thenReturn(new GoogleInboundSyncClient.InboundSyncResult(2, "Mock Calendar inbound read applied."));
        when(googleInboundSyncClient.importTasks(any(CalendarConnection.class), any(Instant.class), any(Instant.class)))
                .thenReturn(new GoogleInboundSyncClient.InboundSyncResult(3, "Mock Tasks inbound read applied."));

        AppUser savedUser = appUserRepository.findByEmail("local@time-table.dev")
                .orElseGet(() -> {
                    AppUser user = new AppUser();
                    user.setEmail("local@time-table.dev");
                    user.setDisplayName("Local User");
                    user.setProvider("local");
                    user.setDemoUser(true);
                    user.setTimezone("Asia/Seoul");
                    user.setAutoRescheduleEnabled(false);
                    user.setFocusAutoEnterEnabled(false);
                    return appUserRepository.save(user);
                });

        CalendarConnection connection = calendarConnectionRepository.findByUserIdAndProvider(savedUser.getId(), "google")
                .orElseGet(CalendarConnection::new);
        connection.setUserId(savedUser.getId());
        connection.setProvider("google");
        connection.setStatus(CalendarConnectionStatus.CONNECTED);
        connection.setEmail(savedUser.getEmail());
        connection.setAccessToken("test-access-token");
        connection.setTokenExpiresAt(Instant.now().plusSeconds(3_600));
        connection.setCalendarReadEnabled(true);
        connection.setTasksReadEnabled(true);
        connection.setCalendarWriteEnabled(false);
        connection.setTasksWriteEnabled(false);
        connection.setCapabilityStatus("read_only_token");
        calendarConnectionRepository.save(connection);
    }

    @Test
    void syncStatusWebhookAndConflictResolutionFlowWorks() throws Exception {
        mockMvc.perform(post("/api/sync/google/calendar")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "inbound",
                                  "resolvePolicy": "proposal_first"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.targetSystem").value("google_calendar"))
                .andExpect(jsonPath("$.data.status").value("success"))
                .andExpect(jsonPath("$.data.affectedCount").value(2));

        mockMvc.perform(post("/api/sync/google/tasks")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "inbound",
                                  "resolvePolicy": "proposal_first"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.targetSystem").value("google_tasks"))
                .andExpect(jsonPath("$.data.status").value("success"))
                .andExpect(jsonPath("$.data.affectedCount").value(3));

        mockMvc.perform(get("/api/sync/status").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.googleCalendar.status").value("success"))
                .andExpect(jsonPath("$.data.googleTasks.status").value("success"))
                .andExpect(jsonPath("$.meta.webhookTarget").value("google_calendar"))
                .andExpect(jsonPath("$.meta.pollingTarget").value("google_tasks"))
                .andExpect(jsonPath("$.meta.adapterMode").value("read_only"))
                .andExpect(jsonPath("$.meta.externalReadEnabled").value(true))
                .andExpect(jsonPath("$.meta.externalWriteEnabled").value(false));

        MvcResult webhookResult = mockMvc.perform(post("/api/sync/google/calendar/webhook")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .header("X-Goog-Channel-Id", "channel-123")
                        .header("X-Goog-Resource-Id", "resource-456")
                        .header("X-Goog-Resource-State", "exists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncRunId", notNullValue()))
                .andExpect(jsonPath("$.data.conflictId", notNullValue()))
                .andReturn();

        String conflictId = webhookResult.getResponse().getContentAsString()
                .replaceAll(".*\"conflictId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/sync/conflicts/{conflictId}/resolve", conflictId)
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resolution": "fork_local"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("resolved"))
                .andExpect(jsonPath("$.data.resolution").value("fork_local"));
    }

    @Test
    void outboundManualSyncFlushesProviderWriteOutbox() throws Exception {
        AppUser user = appUserRepository.findByEmail("local@time-table.dev").orElseThrow();
        CalendarConnection connection = calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .orElseThrow();
        connection.setCalendarWriteEnabled(true);
        connection.setTasksWriteEnabled(true);
        connection.setCapabilityStatus("write_enabled");
        calendarConnectionRepository.save(connection);

        Event event = new Event();
        event.setUserId(user.getId());
        event.setTitle("Mock outbound meeting");
        event.setDescription("Provider write outbox controller test");
        event.setCategory(ScheduleCategory.WORK);
        event.setStartAt(Instant.now().plus(1, ChronoUnit.HOURS));
        event.setEndAt(Instant.now().plus(2, ChronoUnit.HOURS));
        event.setPriority((short) 3);
        event.setStatus(EventStatus.PLANNED);
        event.setSourceType(EventSourceType.LOCAL);
        event.setSyncState(PlannerSyncState.DIRTY_PENDING_WRITE);
        event = eventRepository.save(event);

        ProviderWriteOutbox outbox = new ProviderWriteOutbox();
        outbox.setUserId(user.getId());
        outbox.setLocalType(SyncMappingLocalType.EVENT);
        outbox.setLocalId(event.getId());
        outbox.setProvider(SyncProvider.GOOGLE_CALENDAR);
        outbox.setOperation(ProviderWriteOperation.CREATE);
        outbox.setState(ProviderWriteState.DIRTY_PENDING_WRITE);
        providerWriteOutboxRepository.save(outbox);

        when(googleOutboundSyncClient.createCalendarEvent(any(CalendarConnection.class), any(Event.class)))
                .thenReturn(new GoogleOutboundSyncClient.ProviderWriteResult("mock-api-event", "etag-v2", "{}"));

        mockMvc.perform(post("/api/sync/google/calendar")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "outbound",
                                  "resolvePolicy": "proposal_first"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.targetSystem").value("google_calendar"))
                .andExpect(jsonPath("$.data.status").value("success"))
                .andExpect(jsonPath("$.data.affectedCount").value(1));

        Event synced = eventRepository.findById(event.getId()).orElseThrow();
        assertThat(synced.getSyncState()).isEqualTo(PlannerSyncState.SYNCED);
        assertThat(synced.getExternalSourceId()).isEqualTo("google_calendar:mock-api-event");
    }

    @Test
    void outboundCalendarWriteFailureMarksOutboxRetryableAndDoesNotReportSuccess() throws Exception {
        AppUser user = appUserRepository.findByEmail("local@time-table.dev").orElseThrow();
        CalendarConnection connection = calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .orElseThrow();
        connection.setCalendarWriteEnabled(true);
        connection.setCapabilityStatus("write_enabled");
        calendarConnectionRepository.save(connection);

        Event event = new Event();
        event.setUserId(user.getId());
        event.setTitle("Provider failure meeting");
        event.setDescription("Provider write failure regression");
        event.setCategory(ScheduleCategory.WORK);
        event.setStartAt(Instant.now().plus(1, ChronoUnit.HOURS));
        event.setEndAt(Instant.now().plus(2, ChronoUnit.HOURS));
        event.setPriority((short) 3);
        event.setStatus(EventStatus.PLANNED);
        event.setSourceType(EventSourceType.LOCAL);
        event.setSyncState(PlannerSyncState.DIRTY_PENDING_WRITE);
        event = eventRepository.save(event);

        ProviderWriteOutbox outbox = new ProviderWriteOutbox();
        outbox.setUserId(user.getId());
        outbox.setLocalType(SyncMappingLocalType.EVENT);
        outbox.setLocalId(event.getId());
        outbox.setProvider(SyncProvider.GOOGLE_CALENDAR);
        outbox.setOperation(ProviderWriteOperation.CREATE);
        outbox.setState(ProviderWriteState.DIRTY_PENDING_WRITE);
        providerWriteOutboxRepository.save(outbox);

        UUID eventId = event.getId();

        when(googleOutboundSyncClient.createCalendarEvent(any(CalendarConnection.class), any(Event.class)))
                .thenThrow(new RuntimeException("provider down"));

        mockMvc.perform(post("/api/sync/google/calendar")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "outbound",
                                  "resolvePolicy": "proposal_first"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("failed"))
                .andExpect(jsonPath("$.data.affectedCount").value(1))
                .andExpect(jsonPath("$.data.detail").value("Provider write flush completed: 0 applied, 1 failed."));

        ProviderWriteOutbox failed = providerWriteOutboxRepository.findAll().stream()
                .filter(row -> row.getLocalId().equals(eventId))
                .findFirst()
                .orElseThrow();
        assertThat(failed.getState()).isEqualTo(ProviderWriteState.WRITE_FAILED_RETRYABLE);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getLastErrorCode()).isEqualTo("RuntimeException");
        assertThat(failed.getLastErrorMessage()).isEqualTo("provider down");
        assertThat(failed.getNextRetryAt()).isNotNull();

        Event unsynced = eventRepository.findById(event.getId()).orElseThrow();
        assertThat(unsynced.getSyncState()).isEqualTo(PlannerSyncState.DIRTY_PENDING_WRITE);
        assertThat(unsynced.getExternalSourceId()).isNull();
    }

    @Test
    void outboundGoogleWriteKillSwitchSkipsProviderClientAndDoesNotDrainOutbox() throws Exception {
        ReflectionTestUtils.setField(providerWriteProcessor, "googleWriteEnabled", false);
        ReflectionTestUtils.setField(syncOrchestrationService, "googleWriteEnabled", false);

        AppUser user = appUserRepository.findByEmail("local@time-table.dev").orElseThrow();
        CalendarConnection connection = calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .orElseThrow();
        connection.setCalendarWriteEnabled(true);
        connection.setCapabilityStatus("write_enabled");
        calendarConnectionRepository.save(connection);

        Event event = new Event();
        event.setUserId(user.getId());
        event.setTitle("Paused writeback meeting");
        event.setDescription("Global write kill switch regression");
        event.setCategory(ScheduleCategory.WORK);
        event.setStartAt(Instant.now().plus(1, ChronoUnit.HOURS));
        event.setEndAt(Instant.now().plus(2, ChronoUnit.HOURS));
        event.setPriority((short) 3);
        event.setStatus(EventStatus.PLANNED);
        event.setSourceType(EventSourceType.LOCAL);
        event.setSyncState(PlannerSyncState.DIRTY_PENDING_WRITE);
        event = eventRepository.save(event);

        ProviderWriteOutbox outbox = new ProviderWriteOutbox();
        outbox.setUserId(user.getId());
        outbox.setLocalType(SyncMappingLocalType.EVENT);
        outbox.setLocalId(event.getId());
        outbox.setProvider(SyncProvider.GOOGLE_CALENDAR);
        outbox.setOperation(ProviderWriteOperation.CREATE);
        outbox.setState(ProviderWriteState.DIRTY_PENDING_WRITE);
        providerWriteOutboxRepository.save(outbox);
        UUID eventId = event.getId();

        mockMvc.perform(post("/api/sync/google/calendar")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "outbound",
                                  "resolvePolicy": "proposal_first"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("failed"))
                .andExpect(jsonPath("$.data.affectedCount").value(0))
                .andExpect(jsonPath("$.data.detail")
                        .value("Provider writes are disabled by APP_SYNC_GOOGLE_WRITE_ENABLED=false; no Google writes attempted."));

        verify(googleOutboundSyncClient, never()).createCalendarEvent(any(CalendarConnection.class), any(Event.class));

        ProviderWriteOutbox pending = providerWriteOutboxRepository.findAll().stream()
                .filter(row -> row.getLocalId().equals(eventId))
                .findFirst()
                .orElseThrow();
        assertThat(pending.getState()).isEqualTo(ProviderWriteState.DIRTY_PENDING_WRITE);
        assertThat(pending.getAttemptCount()).isZero();

        mockMvc.perform(get("/api/sync/status").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.googleWriteEnabled").value(false))
                .andExpect(jsonPath("$.meta.externalWriteEnabled").value(false))
                .andExpect(jsonPath("$.meta.pendingProviderWriteCount").value(1));
    }

    @Test
    void outboundCalendarRetryBackoffDoesNotReportSuccessOrClearConnectionError() throws Exception {
        AppUser user = appUserRepository.findByEmail("local@time-table.dev").orElseThrow();
        CalendarConnection connection = calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .orElseThrow();
        connection.setCalendarWriteEnabled(true);
        connection.setCapabilityStatus("write_enabled");
        connection.setStatus(CalendarConnectionStatus.DEGRADED);
        connection.setLastSyncError("provider down");
        calendarConnectionRepository.save(connection);

        Event event = new Event();
        event.setUserId(user.getId());
        event.setTitle("Backoff waiting meeting");
        event.setDescription("Retry backoff overclaim regression");
        event.setCategory(ScheduleCategory.WORK);
        event.setStartAt(Instant.now().plus(1, ChronoUnit.HOURS));
        event.setEndAt(Instant.now().plus(2, ChronoUnit.HOURS));
        event.setPriority((short) 3);
        event.setStatus(EventStatus.PLANNED);
        event.setSourceType(EventSourceType.LOCAL);
        event.setSyncState(PlannerSyncState.DIRTY_PENDING_WRITE);
        event = eventRepository.save(event);

        ProviderWriteOutbox outbox = new ProviderWriteOutbox();
        outbox.setUserId(user.getId());
        outbox.setLocalType(SyncMappingLocalType.EVENT);
        outbox.setLocalId(event.getId());
        outbox.setProvider(SyncProvider.GOOGLE_CALENDAR);
        outbox.setOperation(ProviderWriteOperation.CREATE);
        outbox.setState(ProviderWriteState.WRITE_FAILED_RETRYABLE);
        outbox.setAttemptCount(1);
        outbox.setLastErrorCode("RuntimeException");
        outbox.setLastErrorMessage("provider down");
        outbox.setNextRetryAt(Instant.now().plus(5, ChronoUnit.MINUTES));
        providerWriteOutboxRepository.save(outbox);
        UUID eventId = event.getId();

        mockMvc.perform(post("/api/sync/google/calendar")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "outbound",
                                  "resolvePolicy": "proposal_first"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("failed"))
                .andExpect(jsonPath("$.data.affectedCount").value(1))
                .andExpect(jsonPath("$.data.detail")
                        .value("Provider write flush completed: 0 applied, 0 failed, 1 waiting for retry."));

        verify(googleOutboundSyncClient, never()).createCalendarEvent(any(CalendarConnection.class), any(Event.class));

        ProviderWriteOutbox waiting = providerWriteOutboxRepository.findAll().stream()
                .filter(row -> row.getLocalId().equals(eventId))
                .findFirst()
                .orElseThrow();
        assertThat(waiting.getState()).isEqualTo(ProviderWriteState.WRITE_FAILED_RETRYABLE);
        assertThat(waiting.getAttemptCount()).isEqualTo(1);
        assertThat(waiting.getNextRetryAt()).isNotNull();

        CalendarConnection degraded = calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .orElseThrow();
        assertThat(degraded.getStatus()).isEqualTo(CalendarConnectionStatus.DEGRADED);
        assertThat(degraded.getLastSyncError())
                .isEqualTo("Provider write flush completed: 0 applied, 0 failed, 1 waiting for retry.");
    }

    @Test
    void outboundCalendarFlushesReconnectRequiredRowsAfterWriteCapabilityRestored() throws Exception {
        AppUser user = appUserRepository.findByEmail("local@time-table.dev").orElseThrow();
        CalendarConnection connection = calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .orElseThrow();
        connection.setCalendarWriteEnabled(true);
        connection.setTasksWriteEnabled(true);
        connection.setCapabilityStatus("write_enabled");
        connection.setCapabilityError(null);
        connection.setStatus(CalendarConnectionStatus.CONNECTED);
        calendarConnectionRepository.save(connection);

        Event event = new Event();
        event.setUserId(user.getId());
        event.setTitle("Reconnect restored meeting");
        event.setDescription("Reconnect-required flush regression");
        event.setCategory(ScheduleCategory.WORK);
        event.setStartAt(Instant.now().plus(1, ChronoUnit.HOURS));
        event.setEndAt(Instant.now().plus(2, ChronoUnit.HOURS));
        event.setPriority((short) 3);
        event.setStatus(EventStatus.PLANNED);
        event.setSourceType(EventSourceType.LOCAL);
        event.setSyncState(PlannerSyncState.DIRTY_PENDING_WRITE);
        event = eventRepository.save(event);
        UUID eventId = event.getId();

        ProviderWriteOutbox outbox = new ProviderWriteOutbox();
        outbox.setUserId(user.getId());
        outbox.setLocalType(SyncMappingLocalType.EVENT);
        outbox.setLocalId(eventId);
        outbox.setProvider(SyncProvider.GOOGLE_CALENDAR);
        outbox.setOperation(ProviderWriteOperation.CREATE);
        outbox.setState(ProviderWriteState.WRITE_FAILED_NEEDS_RECONNECT);
        outbox.setAttemptCount(0);
        outbox.setLastErrorCode("reconnect_required");
        outbox.setLastErrorMessage("Google OAuth token does not include the required write scope.");
        providerWriteOutboxRepository.save(outbox);

        when(googleOutboundSyncClient.createCalendarEvent(any(CalendarConnection.class), any(Event.class)))
                .thenReturn(new GoogleOutboundSyncClient.ProviderWriteResult("reconnected-event", "etag-v2", "{}"));

        mockMvc.perform(post("/api/sync/google/calendar")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "outbound",
                                  "resolvePolicy": "proposal_first"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("success"))
                .andExpect(jsonPath("$.data.affectedCount").value(1))
                .andExpect(jsonPath("$.data.detail").value("Provider write flush completed: 1 applied, 0 failed."));

        verify(googleOutboundSyncClient).createCalendarEvent(any(CalendarConnection.class), any(Event.class));

        ProviderWriteOutbox syncedOutbox = providerWriteOutboxRepository.findAll().stream()
                .filter(row -> row.getLocalId().equals(eventId))
                .findFirst()
                .orElseThrow();
        assertThat(syncedOutbox.getState()).isEqualTo(ProviderWriteState.SYNCED);
        assertThat(syncedOutbox.getLastErrorCode()).isNull();
        assertThat(syncedOutbox.getLastErrorMessage()).isNull();
        assertThat(syncedOutbox.getAppliedAt()).isNotNull();

        Event synced = eventRepository.findById(eventId).orElseThrow();
        assertThat(synced.getSyncState()).isEqualTo(PlannerSyncState.SYNCED);
        assertThat(synced.getSourceType()).isEqualTo(EventSourceType.GOOGLE_CALENDAR);
        assertThat(synced.getExternalSourceId()).isEqualTo("google_calendar:reconnected-event");
        assertThat(synced.getExternalEtag()).isEqualTo("etag-v2");

        mockMvc.perform(get("/api/sync/status").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.providerWriteReconnectRequiredCount").value(0))
                .andExpect(jsonPath("$.meta.pendingProviderWriteCount").value(0));
    }

    @Test
    void outboundTasksFlushesReconnectRequiredRowsAfterWriteCapabilityRestored() throws Exception {
        AppUser user = appUserRepository.findByEmail("local@time-table.dev").orElseThrow();
        CalendarConnection connection = calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .orElseThrow();
        connection.setCalendarWriteEnabled(true);
        connection.setTasksWriteEnabled(true);
        connection.setCapabilityStatus("write_enabled");
        connection.setCapabilityError(null);
        connection.setStatus(CalendarConnectionStatus.CONNECTED);
        calendarConnectionRepository.save(connection);

        Task task = new Task();
        task.setUserId(user.getId());
        task.setTitle("Reconnect restored task");
        task.setDescription("Reconnect-required tasks flush regression");
        task.setDueDate(Instant.now().plus(1, ChronoUnit.HOURS));
        task.setEstimatedMinutes(30);
        task.setActualMinutes(0);
        task.setPriority((short) 3);
        task.setStatus(TaskStatus.TODO);
        task.setSourceType(TaskSourceType.LOCAL);
        task.setSyncState(PlannerSyncState.DIRTY_PENDING_WRITE);
        task = taskRepository.save(task);
        UUID taskId = task.getId();

        ProviderWriteOutbox outbox = new ProviderWriteOutbox();
        outbox.setUserId(user.getId());
        outbox.setLocalType(SyncMappingLocalType.TASK);
        outbox.setLocalId(taskId);
        outbox.setProvider(SyncProvider.GOOGLE_TASKS);
        outbox.setOperation(ProviderWriteOperation.CREATE);
        outbox.setState(ProviderWriteState.WRITE_FAILED_NEEDS_RECONNECT);
        outbox.setAttemptCount(0);
        outbox.setLastErrorCode("reconnect_required");
        outbox.setLastErrorMessage("Google OAuth token does not include the required write scope.");
        providerWriteOutboxRepository.save(outbox);

        when(googleOutboundSyncClient.createTask(any(CalendarConnection.class), any(String.class), any(Task.class)))
                .thenReturn(new GoogleOutboundSyncClient.ProviderWriteResult("reconnected-task", "task-etag-v2", "{}"));

        mockMvc.perform(post("/api/sync/google/tasks")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "outbound",
                                  "resolvePolicy": "proposal_first"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("success"))
                .andExpect(jsonPath("$.data.affectedCount").value(1))
                .andExpect(jsonPath("$.data.detail").value("Provider write flush completed: 1 applied, 0 failed."));

        verify(googleOutboundSyncClient).createTask(any(CalendarConnection.class), any(String.class), any(Task.class));

        ProviderWriteOutbox syncedOutbox = providerWriteOutboxRepository.findAll().stream()
                .filter(row -> row.getLocalId().equals(taskId))
                .findFirst()
                .orElseThrow();
        assertThat(syncedOutbox.getState()).isEqualTo(ProviderWriteState.SYNCED);
        assertThat(syncedOutbox.getLastErrorCode()).isNull();
        assertThat(syncedOutbox.getLastErrorMessage()).isNull();
        assertThat(syncedOutbox.getAppliedAt()).isNotNull();

        Task synced = taskRepository.findById(taskId).orElseThrow();
        assertThat(synced.getSyncState()).isEqualTo(PlannerSyncState.SYNCED);
        assertThat(synced.getSourceType()).isEqualTo(TaskSourceType.GOOGLE_TASKS);
        assertThat(synced.getExternalSourceId()).isEqualTo("google_tasks:@default:reconnected-task");
        assertThat(synced.getExternalEtag()).isEqualTo("task-etag-v2");

        mockMvc.perform(get("/api/sync/status").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.providerWriteReconnectRequiredCount").value(0))
                .andExpect(jsonPath("$.meta.pendingProviderWriteCount").value(0));
    }

    @Test
    void providerWriteOutboxCoalescesLocalCreateThenUpdateBeforeFlush() {
        AppUser user = appUserRepository.findByEmail("local@time-table.dev").orElseThrow();
        CalendarConnection connection = calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .orElseThrow();
        connection.setCalendarWriteEnabled(true);
        connection.setCapabilityStatus("write_enabled");
        calendarConnectionRepository.save(connection);

        Event event = new Event();
        event.setUserId(user.getId());
        event.setTitle("Initial local meeting");
        event.setDescription("Coalescing test");
        event.setCategory(ScheduleCategory.WORK);
        event.setStartAt(Instant.now().plus(3, ChronoUnit.HOURS));
        event.setEndAt(Instant.now().plus(4, ChronoUnit.HOURS));
        event.setPriority((short) 3);
        event.setStatus(EventStatus.PLANNED);
        event.setSourceType(EventSourceType.LOCAL);
        event.setSyncState(PlannerSyncState.LOCAL_ONLY);
        event = eventRepository.save(event);

        providerWriteOutboxService.enqueueEventWrite(event, ProviderWriteOperation.CREATE);
        event.setTitle("Updated local meeting");
        providerWriteOutboxService.enqueueEventWrite(event, ProviderWriteOperation.UPDATE);
        UUID eventId = event.getId();

        List<ProviderWriteOutbox> rows = providerWriteOutboxRepository.findAll().stream()
                .filter(row -> row.getLocalId().equals(eventId))
                .toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getOperation()).isEqualTo(ProviderWriteOperation.CREATE);
        assertThat(rows.get(0).getPayloadSnapshot()).contains("Updated local meeting");
    }

    @Test
    void staleCreateOutboxUsesUpdateWhenProviderIdExistsAtFlushTime() throws Exception {
        AppUser user = appUserRepository.findByEmail("local@time-table.dev").orElseThrow();
        CalendarConnection connection = calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .orElseThrow();
        connection.setCalendarWriteEnabled(true);
        connection.setCapabilityStatus("write_enabled");
        calendarConnectionRepository.save(connection);

        Event event = new Event();
        event.setUserId(user.getId());
        event.setTitle("Already mapped local meeting");
        event.setDescription("Stale create guard test");
        event.setCategory(ScheduleCategory.WORK);
        event.setStartAt(Instant.now().plus(5, ChronoUnit.HOURS));
        event.setEndAt(Instant.now().plus(6, ChronoUnit.HOURS));
        event.setPriority((short) 3);
        event.setStatus(EventStatus.PLANNED);
        event.setSourceType(EventSourceType.LOCAL);
        event.setSyncState(PlannerSyncState.DIRTY_PENDING_WRITE);
        event.setExternalSourceId("google_calendar:already-created-event");
        event = eventRepository.save(event);

        ProviderWriteOutbox outbox = new ProviderWriteOutbox();
        outbox.setUserId(user.getId());
        outbox.setLocalType(SyncMappingLocalType.EVENT);
        outbox.setLocalId(event.getId());
        outbox.setProvider(SyncProvider.GOOGLE_CALENDAR);
        outbox.setOperation(ProviderWriteOperation.CREATE);
        outbox.setState(ProviderWriteState.DIRTY_PENDING_WRITE);
        providerWriteOutboxRepository.save(outbox);

        when(googleOutboundSyncClient.updateCalendarEvent(any(CalendarConnection.class), any(String.class), any(Event.class)))
                .thenReturn(new GoogleOutboundSyncClient.ProviderWriteResult("already-created-event", "etag-update", "{}"));

        mockMvc.perform(post("/api/sync/google/calendar")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "outbound",
                                  "resolvePolicy": "proposal_first"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(googleOutboundSyncClient, never()).createCalendarEvent(any(CalendarConnection.class), any(Event.class));
        verify(googleOutboundSyncClient).updateCalendarEvent(any(CalendarConnection.class), any(String.class), any(Event.class));
    }

    @Test
    void manualSyncWithoutGoogleConnectionReturnsConflictNotServiceUnavailable() throws Exception {
        AppUser user = appUserRepository.findByEmail("local@time-table.dev").orElseThrow();
        calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .ifPresent(calendarConnectionRepository::delete);

        mockMvc.perform(post("/api/sync/google/calendar")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "inbound",
                                  "resolvePolicy": "proposal_first"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Google 연동이 필요합니다."));
    }
}
