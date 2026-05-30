package com.timetable.operator.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.AgentCommandTargetType;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-agent-orchestrator-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.ai.enabled=true",
        "app.sync.polling.enabled=false"
})
class AiAgentOrchestratorTest {

    @Autowired
    private AiAgentOrchestrator orchestrator;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private ScheduleBlockRepository scheduleBlockRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private EventRepository eventRepository;

    @MockBean
    private AiAgentStageClient stageClient;

    private AppUser user;
    private ScheduleBlock block;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        taskRepository.deleteAll();
        scheduleBlockRepository.deleteAll();
        appUserRepository.deleteAll();

        AppUser newUser = new AppUser();
        newUser.setEmail("agent-orchestrator@time-table.dev");
        newUser.setDisplayName("Agent Orchestrator User");
        newUser.setProvider("local");
        newUser.setDemoUser(true);
        newUser.setTimezone("Asia/Seoul");
        newUser.setAutoRescheduleEnabled(false);
        newUser.setFocusAutoEnterEnabled(false);
        user = appUserRepository.save(newUser);

        ScheduleBlock newBlock = new ScheduleBlock();
        newBlock.setUserId(user.getId());
        newBlock.setDayOfWeek(DayOfWeek.TUESDAY);
        newBlock.setStartTime(LocalTime.of(15, 0));
        newBlock.setEndTime(LocalTime.of(16, 0));
        newBlock.setActivity("제품 회의");
        newBlock.setCategory(ScheduleCategory.WORK);
        newBlock.setSourceType(ScheduleSourceType.MANUAL);
        newBlock.setSourceRef("test");
        block = scheduleBlockRepository.save(newBlock);
    }

    @Test
    void validInterpretationAndDraftDoesNotRepair() {
        AiAgentRequest request = request("수요일 15:00-16:00 제품 회의 추가해줘");
        AiAgentInterpretation interpretation = createInterpretation("제품 회의", "WEDNESDAY", "15:00", "16:00");
        when(stageClient.interpret(any())).thenReturn(interpretation);
        when(stageClient.draft(any(), any())).thenReturn(batch(scheduleCreate("WEDNESDAY", "15:00", "16:00", "제품 회의")));

        StructuredAiCommandBatch resolved = orchestrator.resolve(request);

        assertThat(resolved.commands()).anyMatch(StructuredAiCommand::requiresConfirmation);
        verify(stageClient).interpret(any());
        verify(stageClient).draft(any(), any());
        verify(stageClient, never()).repair(any(), any(), any(), any());
    }

    @Test
    void missingUserInfoSkipsDraftAndRepair() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create", "event", null, "회의", null, null, null, null, null, null,
                List.of("startTime"), List.of(), 0.45, false, "언제 회의를 추가할까요?"
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(request("오후에 회의 넣어줘"));

        assertThat(resolved.summary()).isEqualTo("확인이 필요합니다");
        assertThat(resolved.commands()).noneMatch(StructuredAiCommand::requiresConfirmation);
        verify(stageClient).interpret(any());
        verify(stageClient, never()).draft(any(), any());
        verify(stageClient, never()).repair(any(), any(), any(), any());
    }

    @Test
    void unsupportedActionSkipsDraftAndRepair() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "run_sync", "none", null, null, null, null, null, null, null, null,
                List.of(), List.of(), 0.90, false, "무엇을 바꿀까요?"
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(request("구글 동기화해줘"));

        assertThat(resolved.summary()).isEqualTo("확인이 필요합니다");
        assertThat(resolved.commands()).noneMatch(StructuredAiCommand::requiresConfirmation);
        verify(stageClient).interpret(any());
        verify(stageClient, never()).draft(any(), any());
        verify(stageClient, never()).repair(any(), any(), any(), any());
    }

    @Test
    void repairableMismatchCallsRepairOnceAndAcceptsRepairedBatch() {
        AiAgentInterpretation interpretation = createInterpretation("제품 회의", "WEDNESDAY", "15:00", "16:00");
        StructuredAiCommandBatch originalDraft = batch(scheduleCreate("WEDNESDAY", "17:00", "18:00", "제품 회의"));
        when(stageClient.interpret(any())).thenReturn(interpretation);
        when(stageClient.draft(any(), any())).thenReturn(originalDraft);
        when(stageClient.repair(any(), any(), any(), any())).thenReturn(batch(scheduleCreate("WEDNESDAY", "15:00", "16:00", "제품 회의")));

        StructuredAiCommandBatch resolved = orchestrator.resolve(request("수요일 15:00-16:00 제품 회의 추가해줘"));

        assertThat(resolved.commands()).anyMatch(StructuredAiCommand::requiresConfirmation);
        assertThat(resolved.commands().getFirst().payload()).containsEntry("startTime", "15:00");
        ArgumentCaptor<StructuredAiCommandBatch> failedBatch = ArgumentCaptor.forClass(StructuredAiCommandBatch.class);
        verify(stageClient).repair(any(), any(), failedBatch.capture(), any());
        assertThat(failedBatch.getValue()).isSameAs(originalDraft);
    }

    @Test
    void repairFailureDoesNotTriggerSecondRepairAndClarifies() {
        AiAgentInterpretation interpretation = createInterpretation("제품 회의", "WEDNESDAY", "15:00", "16:00");
        when(stageClient.interpret(any())).thenReturn(interpretation);
        when(stageClient.draft(any(), any())).thenReturn(batch(scheduleCreate("WEDNESDAY", "17:00", "18:00", "제품 회의")));
        when(stageClient.repair(any(), any(), any(), any())).thenReturn(batch(scheduleCreate("WEDNESDAY", "18:00", "19:00", "제품 회의")));

        StructuredAiCommandBatch resolved = orchestrator.resolve(request("수요일 15:00-16:00 제품 회의 추가해줘"));

        assertThat(resolved.summary()).isEqualTo("확인이 필요합니다");
        assertThat(resolved.commands()).noneMatch(StructuredAiCommand::requiresConfirmation);
        verify(stageClient).repair(any(), any(), any(), any());
    }

    @Test
    void providerFailureAtInterpretationStopsImmediately() {
        when(stageClient.interpret(any())).thenThrow(new IllegalStateException("provider down"));

        assertThatThrownBy(() -> orchestrator.resolve(request("수요일 15:00-16:00 제품 회의 추가해줘")))
                .isInstanceOf(IllegalStateException.class);
        verify(stageClient).interpret(any());
        verify(stageClient, never()).draft(any(), any());
        verify(stageClient, never()).repair(any(), any(), any(), any());
    }

    @Test
    void providerFailureAtDraftDoesNotRepair() {
        when(stageClient.interpret(any())).thenReturn(createInterpretation("제품 회의", "WEDNESDAY", "15:00", "16:00"));
        when(stageClient.draft(any(), any())).thenThrow(new IllegalStateException("provider down"));

        assertThatThrownBy(() -> orchestrator.resolve(request("수요일 15:00-16:00 제품 회의 추가해줘")))
                .isInstanceOf(IllegalStateException.class);
        verify(stageClient, never()).repair(any(), any(), any(), any());
    }

    @Test
    void providerFailureAtRepairDoesNotCallRepairAgain() {
        AiAgentInterpretation interpretation = createInterpretation("제품 회의", "WEDNESDAY", "15:00", "16:00");
        when(stageClient.interpret(any())).thenReturn(interpretation);
        when(stageClient.draft(any(), any())).thenReturn(batch(scheduleCreate("WEDNESDAY", "17:00", "18:00", "제품 회의")));
        when(stageClient.repair(any(), any(), any(), any())).thenThrow(new IllegalStateException("provider down"));

        assertThatThrownBy(() -> orchestrator.resolve(request("수요일 15:00-16:00 제품 회의 추가해줘")))
                .isInstanceOf(IllegalStateException.class);
        verify(stageClient).repair(any(), any(), any(), any());
    }

    @Test
    void explicitHighImpactDeleteCanBecomeExecutable() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "delete", "event", block.getId().toString(), "제품 회의", null, null, null, null, null, null,
                List.of(), List.of(), 0.96, true, ""
        ));
        when(stageClient.draft(any(), any())).thenReturn(batch(new StructuredAiCommand(
                AgentCommandActionType.DELETE_EVENT.wireValue(),
                AgentCommandTargetType.EVENT.wireValue(),
                block.getId().toString(),
                Map.of(),
                "명시된 일정을 삭제합니다.",
                true
        )));

        StructuredAiCommandBatch resolved = orchestrator.resolve(request(block.getId() + " 일정 삭제해줘"));

        assertThat(resolved.commands()).anyMatch(StructuredAiCommand::requiresConfirmation);
        verify(stageClient, never()).repair(any(), any(), any(), any());
    }

    @Test
    void vagueDeleteAsksWhichItemWithoutDraft() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "delete", "event", null, "회의", null, null, null, null, null, null,
                List.of("targetId"), List.of("target"), 0.50, false, "어떤 회의를 삭제할까요?"
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(request("회의 삭제해줘"));

        assertThat(resolved.summary()).isEqualTo("확인이 필요합니다");
        verify(stageClient, never()).draft(any(), any());
        verify(stageClient, never()).repair(any(), any(), any(), any());
    }

    private AiAgentRequest request(String reason) {
        return new AiAgentRequest(user, reason, new AiRescheduleClient.RescheduleAiContext(
                new AiRescheduleClient.UserContext(user.getId().toString(), "Asia/Seoul"),
                new AiRescheduleClient.RequestContext("manual_request", reason, null, null),
                List.of(),
                List.of(),
                List.of()
        ));
    }

    private AiAgentInterpretation createInterpretation(String title, String day, String start, String end) {
        return new AiAgentInterpretation(
                "create", "event", null, title, day, start, end, null, null, null,
                List.of(), List.of(), 0.95, true, ""
        );
    }

    private StructuredAiCommandBatch batch(StructuredAiCommand command) {
        return new StructuredAiCommandBatch("제안", "설명", List.of(command));
    }

    private StructuredAiCommand scheduleCreate(String day, String start, String end, String activity) {
        return new StructuredAiCommand(
                AgentCommandActionType.CREATE_EVENT.wireValue(),
                AgentCommandTargetType.EVENT.wireValue(),
                null,
                Map.of(
                        "dayOfWeek", day,
                        "startTime", start,
                        "endTime", end,
                        "activity", activity,
                        "category", ScheduleCategory.WORK.name()
                ),
                "일정 추가",
                true
        );
    }
}

