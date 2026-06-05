package com.timetable.operator.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timetable.operator.agent.application.AiAgentInterpretation;
import com.timetable.operator.agent.application.AiAgentRequest;
import com.timetable.operator.agent.application.AiAgentStageClient;
import com.timetable.operator.agent.application.AiRescheduleClient;
import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.AgentCommandTargetType;
import com.timetable.operator.agent.domain.RescheduleSuggestion;
import com.timetable.operator.agent.domain.RescheduleSuggestionStatus;
import com.timetable.operator.agent.domain.RescheduleSuggestionTriggerType;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.agent.infrastructure.RescheduleSuggestionRepository;
import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.auth.infrastructure.AppUserRepository;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.domain.EventSourceType;
import com.timetable.operator.events.domain.EventStatus;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.schedule.domain.ScheduleSourceType;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent-ai-orchestration-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.ai.enabled=true",
        "app.ai.api-key=test-fake-key",
        "app.ai.base-url=https://generativelanguage.googleapis.com",
        "app.ai.model=gemini-test",
        "app.ai.max-tokens=1024",
        "app.ai.temperature=0",
        "app.ai.timeout-seconds=3",
        "app.sync.polling.enabled=false"
})
@AutoConfigureMockMvc
class AgentAiOrchestrationControllerTest {

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
    private RescheduleSuggestionRepository rescheduleSuggestionRepository;

    @MockitoBean
    private AiAgentStageClient aiAgentStageClient;

    private AppUser user;

    @BeforeEach
    void setUp() {
        rescheduleSuggestionRepository.deleteAll();
        eventRepository.deleteAll();
        taskRepository.deleteAll();
        scheduleBlockRepository.deleteAll();
        appUserRepository.deleteAll();

        AppUser newUser = new AppUser();
        newUser.setEmail("local@time-table.dev");
        newUser.setDisplayName("Local User");
        newUser.setProvider("local");
        newUser.setDemoUser(true);
        newUser.setTimezone("Asia/Seoul");
        newUser.setAutoRescheduleEnabled(false);
        newUser.setFocusAutoEnterEnabled(false);
        user = appUserRepository.save(newUser);
    }

    @Test
    void fakeLlmExecutableScheduleCreateUsesExistingApiEnvelopeAndAppliesToDb() throws Exception {
        when(aiAgentStageClient.interpret(any())).thenReturn(new AiAgentInterpretation("create", "event", null, "제품 회의", "WEDNESDAY", "15:00", "16:00", null, null, null, List.of(), List.of(), 0.95, true, ""));
        when(aiAgentStageClient.draft(any(), any())).thenReturn(new StructuredAiCommandBatch(
                "주간 일정 추가",
                "명확한 시간과 활동이 있어 적용 후보로 만들었습니다.",
                List.of(scheduleCreate("WEDNESDAY", "15:00", "16:00", "제품 회의"))
        ));

        long beforeCount = scheduleBlockRepository.countByUserId(user.getId());
        MvcResult suggestionResult = mockMvc.perform(post("/api/agent/reschedule")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "triggerType": "manual_request",
                                  "reason": "수요일 15:00-16:00 제품 회의 추가해줘"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.triggerType").value("manual_request"))
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.summary").value("주간 일정 추가"))
                .andExpect(jsonPath("$.data.commandBatch.commands[0].action_type").value("create_event"))
                .andExpect(jsonPath("$.data.previewItems[0].title").value("제품 회의"))
                .andExpect(jsonPath("$.data.executableCommandCount").value(1))
                .andExpect(jsonPath("$.data.executable").value(true))
                .andReturn();

        String suggestionId = suggestionResult.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/agent/suggestions/{suggestionId}/apply", suggestionId)
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "fake LLM 일정 추가 적용"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("applied"))
                .andExpect(jsonPath("$.data.executionSummary.appliedCount").value(1));

        assertThat(scheduleBlockRepository.countByUserId(user.getId())).isEqualTo(beforeCount + 1);
        ScheduleBlock created = scheduleBlockRepository.findByUserId(user.getId()).stream()
                .filter(block -> "제품 회의".equals(block.getActivity()))
                .findFirst()
                .orElseThrow();
        assertThat(created.getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(created.getStartTime()).isEqualTo(LocalTime.of(15, 0));
        assertThat(created.getEndTime()).isEqualTo(LocalTime.of(16, 0));
    }

    @Test
    void fakeLlmMissingFieldsReturnsClarificationAndApplyDoesNotMutateDb() throws Exception {
        when(aiAgentStageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create",
                "event",
                null,
                "회의",
                null,
                null,
                null,
                null,
                null,
                null,
                List.of("startTime"),
                List.of(),
                0.45,
                false,
                "언제 회의를 추가할까요?"
        ));

        long beforeCount = scheduleBlockRepository.countByUserId(user.getId());
        MvcResult suggestionResult = mockMvc.perform(post("/api/agent/reschedule")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "triggerType": "manual_request",
                                  "reason": "오후에 회의 넣어줘"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("확인이 필요합니다"))
                .andExpect(jsonPath("$.data.executableCommandCount").value(0))
                .andExpect(jsonPath("$.data.executable").value(false))
                .andExpect(jsonPath("$.data.commandBatch.commands[0].payload.resolutionType").value("clarification_required"))
                .andReturn();

        String suggestionId = suggestionResult.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/agent/suggestions/{suggestionId}/apply", suggestionId)
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "직접 apply해도 no mutation"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        assertThat(scheduleBlockRepository.countByUserId(user.getId())).isEqualTo(beforeCount);
        assertThat(rescheduleSuggestionRepository.findById(java.util.UUID.fromString(suggestionId)).orElseThrow().getStatus())
                .isEqualTo(RescheduleSuggestionStatus.PENDING);
    }
    @Test
    void providerFailureIsNotReportedAsAiDisabledOrExecutable() throws Exception {
        when(aiAgentStageClient.interpret(any())).thenThrow(new IllegalStateException("fake provider down"));

        long beforeCount = scheduleBlockRepository.countByUserId(user.getId());
        MvcResult suggestionResult = mockMvc.perform(post("/api/agent/reschedule")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "triggerType": "manual_request",
                                  "reason": "수요일 15:00-16:00 제품 회의 추가해줘"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("AI 요청 처리 실패"))
                .andExpect(jsonPath("$.data.executableCommandCount").value(0))
                .andExpect(jsonPath("$.data.executable").value(false))
                .andExpect(jsonPath("$.data.commandBatch.commands[0].payload.resolutionType").value("provider_unavailable"))
                .andReturn();

        String body = suggestionResult.getResponse().getContentAsString();
        assertThat(body).doesNotContain("confidence", "stage", "matchEvidence", "validationTrace", "repairAttempt", "chainOfThought", "reasoning");
        String suggestionId = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/agent/suggestions/{suggestionId}/apply", suggestionId)
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "provider unavailable apply safety"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        assertThat(scheduleBlockRepository.countByUserId(user.getId())).isEqualTo(beforeCount);
        assertThat(rescheduleSuggestionRepository.findById(java.util.UUID.fromString(suggestionId)).orElseThrow().getStatus())
                .isEqualTo(RescheduleSuggestionStatus.PENDING);
    }

    @Test
    void providerQuotaFailureShowsActionableLimitMessage() throws Exception {
        when(aiAgentStageClient.interpret(any())).thenThrow(new IllegalStateException(
                "Gemini provider quota exhausted: status=429, body={\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"prepayment credits are depleted\"}}"
        ));

        mockMvc.perform(post("/api/agent/reschedule")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "triggerType": "manual_request",
                                  "reason": "오늘 내일 연차를 썼다. 일정을 수정해라"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("AI 요청 처리 실패"))
                .andExpect(jsonPath("$.data.executable").value(false))
                .andExpect(jsonPath("$.data.commandBatch.commands[0].payload.resolutionType").value("provider_unavailable"))
                .andExpect(jsonPath("$.data.commandBatch.commands[0].payload.message").value(
                        "AI 사용량 한도 또는 결제 크레딧이 소진되어 요청을 처리하지 못했습니다. 관리자 설정을 확인한 뒤 다시 요청해 주세요."
                ));
    }

    @Test
    void providerFailureDuringDraftReturnsProviderUnavailableSuggestion() throws Exception {
        seedScheduleBlock(DayOfWeek.MONDAY, LocalTime.of(15, 0), LocalTime.of(16, 0), "제품 회의");
        String targetId = scheduleBlockRepository.findByUserId(user.getId()).getFirst().getId().toString();
        when(aiAgentStageClient.interpret(any())).thenReturn(new AiAgentInterpretation("update", "event", targetId, "제품 회의", null, null, null, null, null, null, List.of(), List.of(), 0.95, true, ""));
        when(aiAgentStageClient.draft(any(), any())).thenThrow(new IllegalStateException("fake draft down"));

        long beforeCount = scheduleBlockRepository.countByUserId(user.getId());

        mockMvc.perform(post("/api/agent/reschedule")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "triggerType": "manual_request",
                                  "reason": "제품 회의 제목 바꿔줘"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("AI 요청 처리 실패"))
                .andExpect(jsonPath("$.data.executableCommandCount").value(0))
                .andExpect(jsonPath("$.data.executable").value(false))
                .andExpect(jsonPath("$.data.commandBatch.commands[0].payload.resolutionType").value("provider_unavailable"));

        assertThat(scheduleBlockRepository.countByUserId(user.getId())).isEqualTo(beforeCount);
    }

    @Test
    void providerFailureDuringRepairReturnsProviderUnavailableSuggestion() throws Exception {
        when(aiAgentStageClient.interpret(any())).thenReturn(new AiAgentInterpretation("create", "event", null, "제품 회의", "WEDNESDAY", "15:00", "16:00", null, null, null, List.of(), List.of(), 0.95, true, ""));
        when(aiAgentStageClient.draft(any(), any())).thenReturn(new StructuredAiCommandBatch(
                "잘못된 초안",
                "수리 대상",
                List.of(scheduleCreate("WEDNESDAY", "17:00", "18:00", "제품 회의"))
        ));
        when(aiAgentStageClient.repair(any(), any(), any(), any())).thenThrow(new IllegalStateException("fake repair down"));

        long beforeCount = scheduleBlockRepository.countByUserId(user.getId());

        mockMvc.perform(post("/api/agent/reschedule")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "triggerType": "manual_request",
                                  "reason": "수요일 15:00-16:00 제품 회의 추가해줘"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("AI 요청 처리 실패"))
                .andExpect(jsonPath("$.data.executableCommandCount").value(0))
                .andExpect(jsonPath("$.data.executable").value(false))
                .andExpect(jsonPath("$.data.commandBatch.commands[0].payload.resolutionType").value("provider_unavailable"));

        assertThat(scheduleBlockRepository.countByUserId(user.getId())).isEqualTo(beforeCount);
    }

    @Test
    void manualAiRequestContextIncludesRecentHistoryAndAvailabilityWindows() throws Exception {
        ZoneId userZone = ZoneId.of("Asia/Seoul");
        LocalDate today = LocalDate.now(userZone);
        seedHistory("근무 히스토리 1", "근무 요약 1", RescheduleSuggestionStatus.PENDING);
        seedHistory("근무 히스토리 2", "근무 요약 2", RescheduleSuggestionStatus.APPLIED);
        seedHistory("근무 히스토리 3", "근무 요약 3", RescheduleSuggestionStatus.PENDING);
        seedHistory("거절된 요청", "거절된 요약", RescheduleSuggestionStatus.REJECTED);
        seedHistory("근무 히스토리 4", "근무 요약 4", RescheduleSuggestionStatus.APPLIED);
        seedHistory("근무 히스토리 5", "근무 요약 5", RescheduleSuggestionStatus.PENDING);
        seedHistory("근무 히스토리 6", "근무 요약 6", RescheduleSuggestionStatus.APPLIED);
        seedScheduleBlock(today.getDayOfWeek(), LocalTime.of(9, 0), LocalTime.of(10, 0), "기존 근무");
        seedEvent(
                "팀 회의",
                ZonedDateTime.of(today, LocalTime.of(11, 0), userZone).toInstant(),
                ZonedDateTime.of(today, LocalTime.of(12, 0), userZone).toInstant()
        );
        when(aiAgentStageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create",
                "event",
                null,
                "근무 일정",
                null,
                null,
                null,
                ZonedDateTime.of(today.plusDays(1), LocalTime.of(9, 0), userZone).toInstant().toString(),
                ZonedDateTime.of(today.plusDays(1), LocalTime.of(10, 0), userZone).toInstant().toString(),
                null,
                List.of(),
                List.of(),
                0.92,
                true,
                ""
        ));
        when(aiAgentStageClient.draft(any(), any())).thenReturn(new StructuredAiCommandBatch(
                "근무 일정 추가",
                "최근 대화의 근무 일정 요청과 새 시간 답변을 합쳐 제안합니다.",
                List.of(new StructuredAiCommand(
                        AgentCommandActionType.CREATE_EVENT.wireValue(),
                        AgentCommandTargetType.EVENT.wireValue(),
                        null,
                        Map.of(
                                "title", "근무 일정",
                                "startAt", ZonedDateTime.of(today.plusDays(1), LocalTime.of(9, 0), userZone).toInstant().toString(),
                                "endAt", ZonedDateTime.of(today.plusDays(1), LocalTime.of(10, 0), userZone).toInstant().toString(),
                                "category", ScheduleCategory.WORK.name()
                        ),
                        "최근 대화 이력을 반영",
                        true
                ))
        ));

        mockMvc.perform(post("/api/agent/reschedule")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "triggerType": "manual_request",
                                  "reason": "내일 오전 9시"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("근무 일정 추가"));

        ArgumentCaptor<AiAgentRequest> captor = ArgumentCaptor.forClass(AiAgentRequest.class);
        verify(aiAgentStageClient).interpret(captor.capture());
        AiAgentRequest capturedRequest = captor.getValue();

        assertThat(capturedRequest.context().messageHistory())
                .extracting(AiRescheduleClient.MessageHistoryContext::userRequest)
                .containsExactly("근무 히스토리 3", "거절된 요청", "근무 히스토리 4", "근무 히스토리 5", "근무 히스토리 6");
        assertThat(capturedRequest.context().messageHistory())
                .extracting(AiRescheduleClient.MessageHistoryContext::status)
                .containsExactly("pending", "rejected", "applied", "pending", "applied");
        assertThat(capturedRequest.context().messageHistory().getLast().assistantSummary())
                .isEqualTo("근무 요약 6");
        assertThat(capturedRequest.context().messageHistory())
                .extracting(AiRescheduleClient.MessageHistoryContext::assistantExplanation)
                .containsExactly("테스트 대화 이력", null, "테스트 대화 이력", "테스트 대화 이력", "테스트 대화 이력");
        Instant resolvedRangeStart = Instant.parse(capturedRequest.context().request().resolvedRangeStart());
        Instant resolvedRangeEnd = Instant.parse(capturedRequest.context().request().resolvedRangeEnd());
        assertThat(resolvedRangeStart.atZone(userZone).toLocalDate()).isEqualTo(today);
        assertThat(resolvedRangeEnd.atZone(userZone).toLocalDate()).isEqualTo(today.plusDays(4));
        assertThat(resolvedRangeEnd.atZone(userZone).toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(capturedRequest.context().availabilityWindows())
                .isNotEmpty()
                .allSatisfy(window -> {
                    assertThat(window.durationMinutes()).isGreaterThanOrEqualTo(30);
                    assertThat(window.source()).isEqualTo("computed_empty_slot");
                    assertThat(window.localLabel()).contains("-");
                });
    }

    @Test
    void manualAiRequestSkipsAvailabilityWindowsWhenNoTimeSlotReasoningIsNeeded() throws Exception {
        seedHistory("그 시간은 싫어", "사용자가 이전 제안을 거절했습니다.", RescheduleSuggestionStatus.REJECTED);
        seedScheduleBlock(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 0), "기존 근무");
        when(aiAgentStageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "explain_only",
                "none",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of("time"),
                List.of(),
                0.2,
                true,
                "어떤 방향으로 다시 제안할까요?"
        ));

        mockMvc.perform(post("/api/agent/reschedule")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "triggerType": "manual_request",
                                  "reason": "그건 싫어 다른 제안 줘"
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<AiAgentRequest> captor = ArgumentCaptor.forClass(AiAgentRequest.class);
        verify(aiAgentStageClient).interpret(captor.capture());
        AiAgentRequest capturedRequest = captor.getValue();

        assertThat(capturedRequest.context().messageHistory())
                .extracting(AiRescheduleClient.MessageHistoryContext::status)
                .containsExactly("rejected");
        assertThat(capturedRequest.context().messageHistory().getFirst().assistantSummary())
                .isEqualTo("사용자가 이전 제안을 거절했습니다.");
        assertThat(capturedRequest.context().messageHistory().getFirst().assistantExplanation()).isNull();
        assertThat(capturedRequest.context().availabilityWindows()).isEmpty();
    }

    @Test
    void manualSuggestionPreservesOriginalRequestWhenUserAppliesWithDecisionReason() throws Exception {
        when(aiAgentStageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create",
                "event",
                null,
                "점심약속",
                null,
                "12:00",
                null,
                "2026-06-05T03:00:00Z",
                "2026-06-05T04:00:00Z",
                null,
                List.of("endTime"),
                List.of(),
                0.72,
                true,
                "점심 약속은 몇 시까지인가요?"
        ));
        when(aiAgentStageClient.draft(any(), any())).thenReturn(new StructuredAiCommandBatch(
                "점심 약속 추가",
                "오늘 12시부터 60분으로 가정했습니다.",
                List.of(new StructuredAiCommand(
                        AgentCommandActionType.CREATE_EVENT.wireValue(),
                        AgentCommandTargetType.EVENT.wireValue(),
                        null,
                        Map.of(
                                "title", "점심약속",
                                "startAt", "2026-06-05T03:00:00Z",
                                "endAt", "2026-06-05T04:00:00Z",
                                "category", ScheduleCategory.LIFE.name()
                        ),
                        "점심 기본 60분 가정",
                        true
                ))
        ));

        MvcResult suggestionResult = mockMvc.perform(post("/api/agent/reschedule")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "triggerType": "manual_request",
                                  "reason": "오늘 12시에 점심약속"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason").value("오늘 12시에 점심약속"))
                .andExpect(jsonPath("$.data.originalRequest").value("오늘 12시에 점심약속"))
                .andExpect(jsonPath("$.data.executable").value(true))
                .andReturn();

        String suggestionId = suggestionResult.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/agent/suggestions/{suggestionId}/apply", suggestionId)
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "사용자가 기본 60분 가정을 승인함"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("applied"))
                .andExpect(jsonPath("$.data.reason").value("오늘 12시에 점심약속"))
                .andExpect(jsonPath("$.data.originalRequest").value("오늘 12시에 점심약속"))
                .andExpect(jsonPath("$.data.decisionReason").value("사용자가 기본 60분 가정을 승인함"));

        RescheduleSuggestion saved = rescheduleSuggestionRepository.findById(java.util.UUID.fromString(suggestionId)).orElseThrow();
        assertThat(saved.getReason()).isEqualTo("오늘 12시에 점심약속");
        assertThat(saved.getOriginalRequest()).isEqualTo("오늘 12시에 점심약속");
        assertThat(saved.getDecisionReason()).isEqualTo("사용자가 기본 60분 가정을 승인함");
    }

    private static StructuredAiCommand scheduleCreate(String day, String start, String end, String activity) {
        return new StructuredAiCommand(
                AgentCommandActionType.CREATE_EVENT.wireValue(),
                AgentCommandTargetType.EVENT.wireValue(),
                null,
                Map.of(
                        "dayOfWeek", day,
                        "startTime", start,
                        "endTime", end,
                        "activity", activity,
                        "category", ScheduleCategory.WORK.name(),
                        "note", "fake LLM"
                ),
                "fake LLM scripted response",
                true
        );
    }

    private void seedHistory(String reason, String summary, RescheduleSuggestionStatus status) {
        RescheduleSuggestion suggestion = new RescheduleSuggestion();
        suggestion.setUserId(user.getId());
        suggestion.setTriggerType(RescheduleSuggestionTriggerType.MANUAL_REQUEST);
        suggestion.setStatus(status);
        suggestion.setReason(reason);
        suggestion.setOriginalRequest(reason);
        suggestion.setSummary(summary);
        suggestion.setExplanation("테스트 대화 이력");
        suggestion.setSuggestionPayload("""
                {"summary":"%s","explanation":"테스트 대화 이력","commands":[{"action_type":"explain_only","target_type":"none","target_id":null,"payload":{},"reason":"clarification","requires_confirmation":false}]}
                """.formatted(summary));
        rescheduleSuggestionRepository.saveAndFlush(suggestion);
        try {
            Thread.sleep(2);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while spacing seeded history timestamps", interruptedException);
        }
    }

    private void seedScheduleBlock(DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, String activity) {
        ScheduleBlock block = new ScheduleBlock();
        block.setUserId(user.getId());
        block.setDayOfWeek(dayOfWeek);
        block.setStartTime(startTime);
        block.setEndTime(endTime);
        block.setActivity(activity);
        block.setCategory(ScheduleCategory.WORK);
        block.setSourceType(ScheduleSourceType.MANUAL);
        scheduleBlockRepository.save(block);
    }

    private void seedEvent(String title, Instant startAt, Instant endAt) {
        Event event = new Event();
        event.setUserId(user.getId());
        event.setTitle(title);
        event.setCategory(ScheduleCategory.WORK);
        event.setStartAt(startAt);
        event.setEndAt(endAt);
        event.setStatus(EventStatus.PLANNED);
        event.setSourceType(EventSourceType.LOCAL);
        eventRepository.save(event);
    }
}
