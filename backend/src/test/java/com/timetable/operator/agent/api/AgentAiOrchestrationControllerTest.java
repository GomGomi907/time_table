package com.timetable.operator.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timetable.operator.agent.application.AiAgentInterpretation;
import com.timetable.operator.agent.application.AiAgentStageClient;
import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.AgentCommandTargetType;
import com.timetable.operator.agent.domain.RescheduleSuggestionStatus;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.agent.infrastructure.RescheduleSuggestionRepository;
import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.auth.infrastructure.AppUserRepository;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.schedule.domain.ScheduleSourceType;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    void providerFailureDuringDraftReturnsProviderUnavailableSuggestion() throws Exception {
        when(aiAgentStageClient.interpret(any())).thenReturn(new AiAgentInterpretation("create", "event", null, "제품 회의", "WEDNESDAY", "15:00", "16:00", null, null, null, List.of(), List.of(), 0.95, true, ""));
        when(aiAgentStageClient.draft(any(), any())).thenThrow(new IllegalStateException("fake draft down"));

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
}
