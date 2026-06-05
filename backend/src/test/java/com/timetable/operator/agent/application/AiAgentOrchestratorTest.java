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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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

    @MockitoBean
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
    void defaultableCreateMissingDateAndEndTimeStillDraftsAssistantProposal() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create", "event", null, "점심약속", null, "12:00", null, null, null, null,
                List.of("date", "endTime"), List.of(), 0.72, true, "점심 약속은 몇 시까지인가요?"
        ));
        when(stageClient.draft(any(), any())).thenReturn(batch(new StructuredAiCommand(
                AgentCommandActionType.CREATE_EVENT.wireValue(),
                AgentCommandTargetType.EVENT.wireValue(),
                null,
                Map.of(
                        "title", "점심약속",
                        "startAt", "2026-06-05T03:00:00Z",
                        "endAt", "2026-06-05T04:00:00Z",
                        "category", ScheduleCategory.LIFE.name()
                ),
                "날짜는 오늘, 종료는 점심 기본 60분으로 가정했습니다.",
                true
        )));

        StructuredAiCommandBatch resolved = orchestrator.resolve(request("오늘 12시에 점심약속"));

        assertThat(resolved.commands()).anyMatch(StructuredAiCommand::requiresConfirmation);
        assertThat(resolved.commands().getFirst().payload()).containsEntry("title", "점심약속");
        verify(stageClient).draft(any(), any());
        verify(stageClient, never()).repair(any(), any(), any(), any());
    }

    @Test
    void defaultableCreateSurvivesDraftProviderFailureWithDeterministicFallback() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create", "event", null, "점심약속", null, "12:00", null,
                "2026-06-05T03:00:00Z",
                "2026-06-05T04:00:00Z",
                null,
                List.of("date", "endTime"),
                List.of(),
                0.72,
                true,
                "점심 약속은 몇 시까지인가요?"
        ));
        when(stageClient.draft(any(), any())).thenThrow(new IllegalStateException("provider down"));

        StructuredAiCommandBatch resolved = orchestrator.resolve(request("12시에 점심약속"));

        assertThat(resolved.commands()).anyMatch(StructuredAiCommand::requiresConfirmation);
        StructuredAiCommand command = resolved.commands().getFirst();
        assertThat(command.actionType()).isEqualTo(AgentCommandActionType.CREATE_EVENT.wireValue());
        assertThat(command.targetId()).isNull();
        assertThat(command.payload())
                .containsEntry("title", "점심약속")
                .containsEntry("startAt", "2026-06-05T03:00:00Z")
                .containsEntry("endAt", "2026-06-05T04:00:00Z")
                .containsEntry("category", ScheduleCategory.LIFE.name());
        assertThat(resolved.explanation()).contains("확인용 제안");
        verify(stageClient, never()).repair(any(), any(), any(), any());
    }

    @Test
    void defaultableCreateSurvivesDraftProviderFailureWhenOnlyStartTimeWasInterpreted() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create", "event", null, "점심약속", null, "12:00", null,
                null,
                null,
                null,
                List.of("date", "endTime"),
                List.of(),
                0.72,
                true,
                "점심 약속은 몇 시까지인가요?"
        ));
        when(stageClient.draft(any(), any())).thenThrow(new IllegalStateException("provider down"));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithResolvedRange(
                "12시에 점심약속",
                "2026-06-05T01:00:00Z",
                "2026-06-09T00:00:00Z"
        ));

        assertThat(resolved.commands()).anyMatch(StructuredAiCommand::requiresConfirmation);
        StructuredAiCommand command = resolved.commands().getFirst();
        assertThat(command.payload())
                .containsEntry("title", "점심약속")
                .containsEntry("startAt", "2026-06-05T03:00:00Z")
                .containsEntry("endAt", "2026-06-05T04:00:00Z")
                .containsEntry("category", ScheduleCategory.LIFE.name());
        assertThat(command.reason()).contains("AI 제공자 draft가 실패");
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
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "update", "event", block.getId().toString(), "제품 회의", null, null, null, null, null, null,
                List.of(), List.of(), 0.95, true, ""
        ));
        when(stageClient.draft(any(), any())).thenThrow(new IllegalStateException("provider down"));

        assertThatThrownBy(() -> orchestrator.resolve(request(block.getId() + " 제품 회의 제목 바꿔줘")))
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

    private AiAgentRequest requestWithResolvedRange(String reason, String resolvedRangeStart, String resolvedRangeEnd) {
        return new AiAgentRequest(user, reason, new AiRescheduleClient.RescheduleAiContext(
                new AiRescheduleClient.UserContext(user.getId().toString(), "Asia/Seoul"),
                new AiRescheduleClient.RequestContext("manual_request", reason, null, null, resolvedRangeStart, resolvedRangeEnd),
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

