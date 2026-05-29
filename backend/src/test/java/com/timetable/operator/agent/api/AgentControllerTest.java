package com.timetable.operator.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.auth.infrastructure.AppUserRepository;
import com.timetable.operator.agent.domain.RescheduleSuggestion;
import com.timetable.operator.agent.domain.RescheduleSuggestionStatus;
import com.timetable.operator.agent.domain.RescheduleSuggestionTriggerType;
import com.timetable.operator.agent.infrastructure.RescheduleSuggestionRepository;
import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.calendar.domain.CalendarConnectionStatus;
import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import com.timetable.operator.common.domain.PlannerSyncState;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.domain.EventSourceType;
import com.timetable.operator.events.domain.EventStatus;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.schedule.domain.ScheduleSourceType;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import com.timetable.operator.sync.infrastructure.ProviderWriteOutboxRepository;
import com.timetable.operator.tasks.domain.Task;
import com.timetable.operator.tasks.domain.TaskSourceType;
import com.timetable.operator.tasks.domain.TaskStatus;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.ai.enabled=false",
        "app.sync.polling.enabled=false"
})
@AutoConfigureMockMvc
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private ScheduleBlockRepository scheduleBlockRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private CalendarConnectionRepository calendarConnectionRepository;

    @Autowired
    private ProviderWriteOutboxRepository providerWriteOutboxRepository;

    @Autowired
    private RescheduleSuggestionRepository rescheduleSuggestionRepository;

    private ScheduleBlock savedBlock;
    private AppUser savedUser;

    @BeforeEach
    void setUp() {
        AppUser user = appUserRepository.findByEmail("local@time-table.dev")
                .orElseGet(() -> {
                    AppUser newUser = new AppUser();
                    newUser.setEmail("local@time-table.dev");
                    newUser.setDisplayName("Local User");
                    newUser.setProvider("local");
                    newUser.setDemoUser(true);
                    newUser.setTimezone("Asia/Seoul");
                    newUser.setAutoRescheduleEnabled(false);
                    newUser.setFocusAutoEnterEnabled(false);
                    return appUserRepository.save(newUser);
                });
        savedUser = user;

        if (scheduleBlockRepository.findByUserId(user.getId()).isEmpty()) {
            ScheduleBlock block = new ScheduleBlock();
            block.setUserId(user.getId());
            block.setDayOfWeek(DayOfWeek.MONDAY);
            block.setStartTime(LocalTime.of(9, 0));
            block.setEndTime(LocalTime.of(10, 0));
            block.setActivity("테스트 일정");
            block.setCategory(ScheduleCategory.WORK);
            block.setNote("agent test");
            block.setSourceType(ScheduleSourceType.MANUAL);
            block.setSourceRef("test-seed");
            savedBlock = scheduleBlockRepository.save(block);
        } else {
            savedBlock = scheduleBlockRepository.findByUserId(user.getId()).getFirst();
        }

        calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .orElseGet(() -> {
                    CalendarConnection connection = new CalendarConnection();
                    connection.setUserId(user.getId());
                    connection.setProvider("google");
                    connection.setStatus(CalendarConnectionStatus.CONNECTED);
                    connection.setAccessToken("test-access-token");
                    connection.setRefreshToken("test-refresh-token");
                    connection.setCalendarWriteEnabled(true);
                    connection.setTasksWriteEnabled(true);
                    connection.setCalendarReadEnabled(true);
                    connection.setTasksReadEnabled(true);
                    connection.setGrantedScopes("https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/tasks");
                    connection.setCapabilityStatus("write_enabled");
                    return calendarConnectionRepository.save(connection);
                });
    }

    @Test
    void chatCommandCreatesSuggestionAndApplyRevertFlowRestoresScheduleBlock() throws Exception {
        MvcResult chatResult = mockMvc.perform(post("/api/chat/command")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "%s 일정 30분 미뤄줘"
                                }
                                """.formatted(savedBlock.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intent").value("multi_command_reschedule"))
                .andExpect(jsonPath("$.data.actions[0].result").value("suggestion_created"))
                .andExpect(jsonPath("$.data.actions[0].suggestionId", notNullValue()))
                .andReturn();

        String suggestionId = chatResult.getResponse().getContentAsString()
                .replaceAll(".*\"suggestionId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/agent/suggestions/{suggestionId}/apply", suggestionId)
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "사용자 승인"
                                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("applied"))
                .andExpect(jsonPath("$.data.statusLabel").value("적용 완료"))
                .andExpect(jsonPath("$.data.executionSummary.appliedCount").value(1));

        ScheduleBlock shiftedBlock = scheduleBlockRepository.findById(savedBlock.getId()).orElseThrow();
        assertThat(shiftedBlock.getStartTime()).isEqualTo(LocalTime.of(9, 30));
        assertThat(shiftedBlock.getEndTime()).isEqualTo(LocalTime.of(10, 30));

        mockMvc.perform(post("/api/agent/suggestions/{suggestionId}/revert", suggestionId)
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "되돌리기 검증"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("reverted"));

        ScheduleBlock revertedBlock = scheduleBlockRepository.findById(savedBlock.getId()).orElseThrow();
        assertThat(revertedBlock.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(revertedBlock.getEndTime()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    void manualSuggestionCanBeRejected() throws Exception {
        MvcResult suggestionResult = mockMvc.perform(post("/api/agent/reschedule")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "triggerType": "manual_request",
                                  "reason": "이번 주 일정 전체 다시 맞춰줘"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.previewItems.length()").value(2))
                .andExpect(jsonPath("$.data.previewItems[0].title").value("재조율 요청"))
                .andExpect(jsonPath("$.data.executableCommandCount").value(1))
                .andReturn();

        String suggestionId = suggestionResult.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/agent/suggestions/{suggestionId}/reject", suggestionId)
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "지금은 수동으로 처리"
                                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("rejected"))
                .andExpect(jsonPath("$.data.statusLabel").value("보류됨"))
                .andExpect(jsonPath("$.data.statusDetail").value("지금은 수동으로 처리"));

        mockMvc.perform(get("/api/agent/suggestions").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("rejected"));
    }

    @Test
    void malformedSuggestionRequestsReturnBadRequestInsteadOfServerError() throws Exception {
        mockMvc.perform(post("/api/agent/reschedule")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "triggerType": "manual_request",
                                  "reason": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(post("/api/agent/suggestions/{suggestionId}/apply", "undefined")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "invalid id"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void manualSuggestionApplyReportsNoOpExecutionSummary() throws Exception {
        long blockCountBefore = scheduleBlockRepository.count();
        MvcResult suggestionResult = mockMvc.perform(post("/api/agent/reschedule")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "triggerType": "manual_request",
                                  "reason": "이번 주 전체 흐름 점검해줘"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andReturn();

        String suggestionId = suggestionResult.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/agent/suggestions/{suggestionId}/apply", suggestionId)
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "no-op 요약 검증"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("applied"))
                .andExpect(jsonPath("$.data.executionSummary.totalCount").value(2))
                .andExpect(jsonPath("$.data.executionSummary.appliedCount").value(0))
                .andExpect(jsonPath("$.data.executionSummary.noOpCount").value(2));

        assertThat(scheduleBlockRepository.count()).isEqualTo(blockCountBefore);
    }

    @Test
    void applySuggestionSkipsConflictingCommandWithoutBlockingDecisionFlow() throws Exception {
        ScheduleBlock movable = new ScheduleBlock();
        movable.setUserId(savedUser.getId());
        movable.setDayOfWeek(DayOfWeek.TUESDAY);
        movable.setStartTime(LocalTime.of(9, 0));
        movable.setEndTime(LocalTime.of(10, 0));
        movable.setActivity("이동 대상");
        movable.setCategory(ScheduleCategory.WORK);
        movable.setSourceType(ScheduleSourceType.MANUAL);
        movable.setSourceRef("conflict-test");
        movable = scheduleBlockRepository.save(movable);

        ScheduleBlock blocker = new ScheduleBlock();
        blocker.setUserId(savedUser.getId());
        blocker.setDayOfWeek(DayOfWeek.TUESDAY);
        blocker.setStartTime(LocalTime.of(10, 30));
        blocker.setEndTime(LocalTime.of(11, 30));
        blocker.setActivity("충돌 블록");
        blocker.setCategory(ScheduleCategory.GROWTH);
        blocker.setSourceType(ScheduleSourceType.MANUAL);
        blocker.setSourceRef("conflict-test");
        scheduleBlockRepository.save(blocker);

        MvcResult chatResult = mockMvc.perform(post("/api/chat/command")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "%s 일정 90분 미뤄줘"
                                }
                                """.formatted(movable.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actions[0].result").value("suggestion_created"))
                .andReturn();

        String suggestionId = chatResult.getResponse().getContentAsString()
                .replaceAll(".*\"suggestionId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/agent/suggestions/{suggestionId}/apply", suggestionId)
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "충돌이어도 검토 완료 처리"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("applied"))
                .andExpect(jsonPath("$.data.executionSummary.appliedCount").value(0))
                .andExpect(jsonPath("$.data.executionSummary.noOpCount").value(2));

        ScheduleBlock unchanged = scheduleBlockRepository.findById(movable.getId()).orElseThrow();
        assertThat(unchanged.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(unchanged.getEndTime()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    void applySuggestionAcceptsAiLocalDateTimeWithoutServerError() throws Exception {
        long eventCountBefore = eventRepository.count();
        RescheduleSuggestion suggestion = new RescheduleSuggestion();
        suggestion.setUserId(savedUser.getId());
        suggestion.setTriggerType(RescheduleSuggestionTriggerType.MANUAL_REQUEST);
        suggestion.setStatus(RescheduleSuggestionStatus.PENDING);
        suggestion.setSummary("로컬 시각 AI 제안");
        suggestion.setReason("LLM이 timezone 없는 local datetime을 반환");
        suggestion.setExplanation("timezone offset이 없어도 서버 오류 없이 처리해야 한다.");
        suggestion.setSuggestionPayload("""
                {
                  "summary": "로컬 시각 AI 제안",
                  "explanation": "timezone offset이 없어도 서버 오류 없이 처리해야 한다.",
                  "commands": [
                    {
                      "action_type": "create_event",
                      "target_type": "event",
                      "target_id": null,
                      "payload": {
                        "title": "AI 로컬 시각 회의",
                        "startAt": "2026-05-16T19:10:00",
                        "endAt": "2026-05-16T20:10:00",
                        "category": "WORK"
                      },
                      "reason": "LLM local datetime",
                      "requires_confirmation": true
                    }
                  ]
                }
                """);
        RescheduleSuggestion savedSuggestion = rescheduleSuggestionRepository.save(suggestion);

        mockMvc.perform(post("/api/agent/suggestions/{suggestionId}/apply", savedSuggestion.getId())
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "local datetime 적용"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("applied"))
                .andExpect(jsonPath("$.data.executionSummary.appliedCount").value(1));

        assertThat(eventRepository.count()).isEqualTo(eventCountBefore + 1);
        Event created = eventRepository.findByUserIdOrderByStartAtAsc(savedUser.getId()).stream()
                .filter(event -> "AI 로컬 시각 회의".equals(event.getTitle()))
                .findFirst()
                .orElseThrow();
        assertThat(created.getStartAt()).isEqualTo(Instant.parse("2026-05-16T10:10:00Z"));
        assertThat(created.getEndAt()).isEqualTo(Instant.parse("2026-05-16T11:10:00Z"));
    }

    @Test
    void applySuggestionCreatesScheduleBlockEvenWhenAiOmitsCategory() throws Exception {
        long blockCountBefore = scheduleBlockRepository.count();
        RescheduleSuggestion suggestion = new RescheduleSuggestion();
        suggestion.setUserId(savedUser.getId());
        suggestion.setTriggerType(RescheduleSuggestionTriggerType.MANUAL_REQUEST);
        suggestion.setStatus(RescheduleSuggestionStatus.PENDING);
        suggestion.setSummary("주간 일정 추가");
        suggestion.setReason("AI가 category 없이 주간 일정 추가 명령을 반환");
        suggestion.setExplanation("category가 없어도 기본 분류로 실제 일정 블록이 생성되어야 한다.");
        suggestion.setSuggestionPayload("""
                {
                  "summary": "주간 일정 추가",
                  "explanation": "category가 없어도 기본 분류로 실제 일정 블록이 생성되어야 한다.",
                  "commands": [
                    {
                      "action_type": "create_event",
                      "target_type": "event",
                      "target_id": null,
                      "payload": {
                        "dayOfWeek": "WEDNESDAY",
                        "startTime": "15:00",
                        "endTime": "16:00",
                        "activity": "AI 일정 추가 검증"
                      },
                      "reason": "사용자가 새 일정을 요청했다.",
                      "requires_confirmation": true
                    }
                  ]
                }
                """);
        RescheduleSuggestion savedSuggestion = rescheduleSuggestionRepository.save(suggestion);

        mockMvc.perform(post("/api/agent/suggestions/{suggestionId}/apply", savedSuggestion.getId())
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "category 없는 AI 일정 추가 적용"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("applied"))
                .andExpect(jsonPath("$.data.executionSummary.appliedCount").value(1));

        assertThat(scheduleBlockRepository.count()).isEqualTo(blockCountBefore + 1);
        ScheduleBlock created = scheduleBlockRepository.findByUserId(savedUser.getId()).stream()
                .filter(block -> "AI 일정 추가 검증".equals(block.getActivity()))
                .findFirst()
                .orElseThrow();
        assertThat(created.getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(created.getStartTime()).isEqualTo(LocalTime.of(15, 0));
        assertThat(created.getEndTime()).isEqualTo(LocalTime.of(16, 0));
        assertThat(created.getCategory()).isEqualTo(ScheduleCategory.LIFE);
    }

    @Test
    void chatSuggestionApplyMutatesCanonicalEventAndQueuesProviderWrite() throws Exception {
        Event event = new Event();
        event.setUserId(savedUser.getId());
        event.setTitle("Google 회의");
        event.setStartAt(Instant.parse("2026-05-15T01:00:00Z"));
        event.setEndAt(Instant.parse("2026-05-15T02:00:00Z"));
        event.setCategory(ScheduleCategory.WORK);
        event.setPriority((short) 2);
        event.setStatus(EventStatus.PLANNED);
        event.setSourceType(EventSourceType.GOOGLE_CALENDAR);
        event.setSyncState(PlannerSyncState.IMPORTED);
        event.setExternalSourceId("google_calendar:event-1");
        Event savedEvent = eventRepository.save(event);
        long outboxBefore = providerWriteOutboxRepository.count();

        MvcResult chatResult = mockMvc.perform(post("/api/chat/command")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "%s 일정 30분 미뤄줘"
                                }
                                """.formatted(savedEvent.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actions[0].result").value("suggestion_created"))
                .andReturn();

        String suggestionId = chatResult.getResponse().getContentAsString()
                .replaceAll(".*\"suggestionId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/agent/suggestions/{suggestionId}/apply", suggestionId)
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "canonical event 적용"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("applied"))
                .andExpect(jsonPath("$.data.executionSummary.appliedCount").value(1));

        Event shifted = eventRepository.findById(savedEvent.getId()).orElseThrow();
        assertThat(shifted.getStartAt()).isEqualTo(Instant.parse("2026-05-15T01:30:00Z"));
        assertThat(shifted.getEndAt()).isEqualTo(Instant.parse("2026-05-15T02:30:00Z"));
        assertThat(shifted.getSyncState()).isEqualTo(PlannerSyncState.DIRTY_PENDING_WRITE);
        assertThat(providerWriteOutboxRepository.count()).isGreaterThan(outboxBefore);
    }

    @Test
    void chatSuggestionApplyMutatesCanonicalTaskAndQueuesProviderWrite() throws Exception {
        Task task = new Task();
        task.setUserId(savedUser.getId());
        task.setTitle("Google 할 일");
        task.setDueDate(Instant.parse("2026-05-15T03:00:00Z"));
        task.setEstimatedMinutes(30);
        task.setActualMinutes(0);
        task.setPriority((short) 3);
        task.setStatus(TaskStatus.TODO);
        task.setSourceType(TaskSourceType.GOOGLE_TASKS);
        task.setSyncState(PlannerSyncState.IMPORTED);
        task.setExternalSourceId("google_tasks:task-1");
        Task savedTask = taskRepository.save(task);
        long outboxBefore = providerWriteOutboxRepository.count();

        MvcResult chatResult = mockMvc.perform(post("/api/chat/command")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "%s task 30분 미뤄줘"
                                }
                                """.formatted(savedTask.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actions[0].result").value("suggestion_created"))
                .andReturn();

        String suggestionId = chatResult.getResponse().getContentAsString()
                .replaceAll(".*\"suggestionId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/agent/suggestions/{suggestionId}/apply", suggestionId)
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "canonical task 적용"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("applied"))
                .andExpect(jsonPath("$.data.executionSummary.appliedCount").value(1));

        Task shifted = taskRepository.findById(savedTask.getId()).orElseThrow();
        assertThat(shifted.getDueDate()).isEqualTo(Instant.parse("2026-05-15T03:30:00Z"));
        assertThat(shifted.getSyncState()).isEqualTo(PlannerSyncState.DIRTY_PENDING_WRITE);
        assertThat(providerWriteOutboxRepository.count()).isGreaterThan(outboxBefore);
    }
}
