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
        assertThat(resolved.explanation()).contains("확인용 변경안");
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
        assertThat(command.reason()).contains("자동 생성 응답이 불안정");
        verify(stageClient, never()).repair(any(), any(), any(), any());
    }

    @Test
    void incompleteCreateDraftIsRepairedAfterValidationFailureInsteadOfWeeklyFormCopy() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create", "event", null, "점심약속", null, "12:00", null,
                null, null, null,
                List.of("date", "endTime"), List.of(), 0.72, true, "점심 약속은 몇 시까지인가요?"
        ));
        when(stageClient.draft(any(), any())).thenReturn(batch(new StructuredAiCommand(
                AgentCommandActionType.CREATE_EVENT.wireValue(),
                AgentCommandTargetType.EVENT.wireValue(),
                null,
                Map.of("title", "점심약속", "startTime", "12:00", "category", ScheduleCategory.LIFE.name()),
                "모델이 시작 시간만 반환했습니다.",
                true
        )));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithResolvedRange(
                "12시에 점심약속",
                "2026-06-05T01:00:00Z",
                "2026-06-09T00:00:00Z"
        ));

        assertThat(resolved.commands()).anyMatch(StructuredAiCommand::requiresConfirmation);
        assertThat(resolved.explanation()).doesNotContain("요일, 시작 시간, 종료 시간");
        assertThat(resolved.commands().getFirst().payload())
                .containsEntry("startAt", "2026-06-05T03:00:00Z")
                .containsEntry("endAt", "2026-06-05T04:00:00Z");
    }

    @Test
    void defaultableCreateAfterRequestedTimeUsesNextDayInsteadOfPast() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create", "event", null, "점심약속", null, "12:00", null,
                null, null, null,
                List.of("date", "endTime"), List.of(), 0.72, true, ""
        ));
        when(stageClient.draft(any(), any())).thenThrow(new IllegalStateException("provider down"));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithResolvedRange(
                "12시에 점심약속",
                "2026-06-05T04:30:00Z",
                "2026-06-09T00:00:00Z"
        ));

        assertThat(resolved.commands().getFirst().payload())
                .containsEntry("startAt", "2026-06-06T03:00:00Z")
                .containsEntry("endAt", "2026-06-06T04:00:00Z");
    }

    @Test
    void afterWorkExerciseUsesWorkEndContextAsHealthDraft() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create", "event", null, "운동", null, null, null,
                null, null, null,
                List.of("time"), List.of(), 0.60, true, ""
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithContext(
                "퇴근 후 운동 넣어줘",
                List.of(),
                List.of(blockCtx("FRIDAY", "09:00", "18:00", "근무", "WORK")),
                List.of(),
                List.of(),
                List.of()
        ));

        assertThat(resolved.commands()).anyMatch(StructuredAiCommand::requiresConfirmation);
        assertThat(resolved.commands().getFirst().payload())
                .containsEntry("title", "운동")
                .containsEntry("startAt", "2026-06-05T09:00:00Z")
                .containsEntry("endAt", "2026-06-05T10:00:00Z")
                .containsEntry("category", ScheduleCategory.HEALTH.name());
        verify(stageClient, never()).draft(any(), any());
    }

    @Test
    void missingExactMorningTimeProducesAvailabilityCandidates() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create", "event", null, "병원", null, null, null,
                null, null, null,
                List.of("startTime"), List.of(), 0.60, true, ""
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithContext(
                "내일 오전에 병원 가야 해",
                List.of(), List.of(), List.of(), List.of(),
                List.of(window("2026-06-06T00:00:00Z", "2026-06-06T01:00:00Z", "내일 09:00-10:00"))
        ));

        assertThat(resolved.commands().getFirst().payload())
                .containsEntry("requestKind", "availability_candidate")
                .extractingByKey("candidateWindows")
                .asList()
                .contains("내일 09:00-10:00");
    }

    @Test
    void thisWeekHaircutProducesCandidateSlotsNotFixedEvent() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create", "event", null, "미용실", null, null, null,
                null, null, null,
                List.of("date", "startTime"), List.of(), 0.60, true, ""
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithContext(
                "이번 주 안에 머리 자르러 가야 돼",
                List.of(), List.of(), List.of(), List.of(),
                List.of(
                        window("2026-06-06T04:00:00Z", "2026-06-06T05:00:00Z", "토 13:00-14:00"),
                        window("2026-06-07T05:00:00Z", "2026-06-07T06:00:00Z", "일 14:00-15:00")
                )
        ));

        assertThat(resolved.commands()).noneMatch(StructuredAiCommand::requiresConfirmation);
        assertThat(resolved.commands().getFirst().payload()).containsEntry("requestKind", "availability_candidate");
    }

    @Test
    void leaveDeclarationAnalyzesWorkImpactAndProtectsExternalAndPersonalItems() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "request_reschedule", "event", null, null, null, null, null, null, null, null,
                List.of(), List.of(), 0.70, true, ""
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithContext(
                "오늘 내일 연차 썼어. 일정 수정해줘.",
                List.of(
                        event("업무 회의", "WORK", "2026-06-05T05:00:00Z", "2026-06-05T06:00:00Z", "DIRTY_PENDING_WRITE"),
                        event("친구 약속", "LIFE", "2026-06-05T10:00:00Z", "2026-06-05T11:00:00Z", "LOCAL_ONLY")
                ),
                List.of(blockCtx("FRIDAY", "09:00", "18:00", "근무", "WORK")),
                List.of(task("보고서 작성", "WORK")),
                List.of(),
                List.of()
        ));

        Map<String, Object> payload = resolved.commands().getFirst().payload();
        assertThat(payload).containsEntry("requestKind", "status_declaration")
                .containsEntry("externalMutationAllowed", false);
        assertThat(payload.get("workEvents").toString()).contains("업무 회의").doesNotContain("친구 약속");
        assertThat(resolved.explanation()).contains("개인 일정은 보존").contains("외부 일정은 직접 삭제하지 않습니다");
    }

    @Test
    void halfDayOnlyScopesMorningWorkImpact() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "request_reschedule", "event", null, null, null, null, null, null, null, null,
                List.of(), List.of(), 0.70, true, ""
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithContext(
                "내일 오전 반차야.",
                List.of(event("오전 업무 회의", "WORK", "2026-06-06T00:00:00Z", "2026-06-06T01:00:00Z", "LOCAL_ONLY")),
                List.of(), List.of(), List.of(), List.of()
        ));

        assertThat(resolved.commands().getFirst().payload()).containsEntry("mode", "반차");
    }

    @Test
    void travelWithoutDetailsAsksTargetedQuestion() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "request_reschedule", "event", null, null, null, null, null, null, null, null,
                List.of(), List.of(), 0.70, true, ""
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(request("출장 잡혔어."));

        assertThat(resolved.commands().getFirst().payload())
                .containsEntry("mode", "출장")
                .containsEntry("resolutionType", "clarification_required");
        assertThat(resolved.explanation()).contains("출장 날짜");
    }

    @Test
    void travelWithDateRangeAnalyzesImpact() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "request_reschedule", "event", null, null, null, null, null, null, null, null,
                List.of(), List.of(), 0.70, true, ""
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithContext(
                "내일 출장 잡혔어.",
                List.of(event("출장일 업무 회의", "WORK", "2026-06-06T05:00:00Z", "2026-06-06T06:00:00Z", "LOCAL_ONLY")),
                List.of(), List.of(), List.of(), List.of()
        ));

        assertThat(resolved.commands().getFirst().payload())
                .containsEntry("mode", "출장")
                .containsEntry("requestKind", "status_declaration");
    }

    @Test
    void sickDayProducesLowEnergyImpactAnalysis() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "request_reschedule", "event", null, null, null, null, null, null, null, null,
                List.of(), List.of(), 0.70, true, ""
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithContext(
                "오늘 몸이 안 좋아.",
                List.of(event("강도 높은 운동", "HEALTH", "2026-06-05T10:00:00Z", "2026-06-05T11:00:00Z", "LOCAL_ONLY")),
                List.of(), List.of(task("긴급 마감", "WORK")), List.of(), List.of()
        ));

        assertThat(resolved.commands().getFirst().payload()).containsEntry("mode", "저에너지/병가");
    }

    @Test
    void destructiveBulkDeleteShowsCandidatesAndDoesNotCallDraft() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "delete", "event", null, "업무", null, null, null, null, null, null,
                List.of("targetId"), List.of(), 0.80, true, ""
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithContext(
                "내일 일 관련 일정 다 지워줘.",
                List.of(
                        event("업무 회의", "WORK", "2026-06-06T05:00:00Z", "2026-06-06T06:00:00Z", "LOCAL_ONLY"),
                        event("개인 약속", "LIFE", "2026-06-06T08:00:00Z", "2026-06-06T09:00:00Z", "LOCAL_ONLY")
                ),
                List.of(), List.of(), List.of(), List.of()
        ));

        assertThat(resolved.commands().getFirst().payload()).containsEntry("requestKind", "destructive_bulk")
                .containsEntry("externalMutationAllowed", false);
        assertThat(resolved.commands().getFirst().payload().get("eventCandidates").toString()).contains("업무 회의").doesNotContain("개인 약속");
        verify(stageClient, never()).draft(any(), any());
    }

    @Test
    void destructiveAllMeetingsDistinguishesExternalCandidates() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "delete", "event", null, "회의", null, null, null, null, null, null,
                List.of("targetId"), List.of(), 0.80, true, ""
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithContext(
                "이번 주 회의 다 없애줘.",
                List.of(event("외부 회의", "WORK", "2026-06-06T05:00:00Z", "2026-06-06T06:00:00Z", "DIRTY_PENDING_WRITE")),
                List.of(), List.of(), List.of(), List.of()
        ));

        assertThat(resolved.commands().getFirst().payload().get("eventCandidates").toString()).contains("외부 원본 보호");
    }

    @Test
    void clearAllDayClassifiesBeforeApplying() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "delete", "event", null, "오늘 일정", null, null, null, null, null, null,
                List.of("targetId"), List.of(), 0.80, true, ""
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithContext(
                "오늘 일정 싹 비워줘.",
                List.of(event("개인 약속", "LIFE", "2026-06-05T08:00:00Z", "2026-06-05T09:00:00Z", "LOCAL_ONLY")),
                List.of(), List.of(), List.of(), List.of()
        ));

        assertThat(resolved.commands()).noneMatch(StructuredAiCommand::requiresConfirmation);
        assertThat(resolved.explanation()).contains("삭제/취소 후보");
    }

    @Test
    void followUpWithSingleHistoryCanUsePreservedContext() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create", "event", null, "점심 약속", null, "12:00", null,
                null, null, null,
                List.of("endTime"), List.of(), 0.80, true, ""
        ));
        when(stageClient.draft(any(), any())).thenThrow(new IllegalStateException("provider down"));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithContext(
                "12시",
                List.of(), List.of(), List.of(),
                List.of(history("pending", "내일 점심 약속 넣어줘", "시간 확인")),
                List.of()
        ));

        assertThat(resolved.commands().getFirst().payload()).containsEntry("title", "점심 약속");
        verify(stageClient).draft(any(), any());
    }

    @Test
    void followUpWithMultipleHistoryAsksWhichOne() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "move", "event", null, null, null, null, null,
                null, null, 30L,
                List.of("targetId"), List.of(), 0.80, true, ""
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithContext(
                "그거 30분 늦춰줘",
                List.of(), List.of(), List.of(),
                List.of(
                        history("pending", "점심 약속", "12시 제안"),
                        history("pending", "회의", "14시 제안")
                ),
                List.of()
        ));

        assertThat(resolved.commands().getFirst().payload()).containsEntry("requestKind", "follow_up");
        verify(stageClient, never()).draft(any(), any());
    }

    @Test
    void rejectedHistoryFollowUpCanDraftAlternativeWhenModelResolvesIt() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create", "event", null, "점심 약속", null, "15:00", null,
                null, null, null,
                List.of("endTime"), List.of(), 0.80, true, ""
        ));
        when(stageClient.draft(any(), any())).thenThrow(new IllegalStateException("provider down"));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithContext(
                "아니 그건 싫고 오후로",
                List.of(), List.of(), List.of(),
                List.of(history("rejected", "내일 점심 약속", "12시는 거절됨")),
                List.of()
        ));

        assertThat(resolved.commands().getFirst().payload()).containsEntry("startAt", "2026-06-05T06:00:00Z");
    }

    @Test
    void conflictGuardExplainsConflictInsteadOfCreatingOverlappingEvent() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create", "event", null, "회의", null, "14:00", "15:00",
                null, null, null,
                List.of(), List.of(), 0.95, true, ""
        ));
        when(stageClient.draft(any(), any())).thenReturn(batch(new StructuredAiCommand(
                AgentCommandActionType.CREATE_EVENT.wireValue(),
                AgentCommandTargetType.EVENT.wireValue(),
                null,
                Map.of("title", "회의", "startAt", "2026-06-06T05:00:00Z", "endAt", "2026-06-06T06:00:00Z", "category", "WORK"),
                "회의 추가",
                true
        )));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithContext(
                "내일 2시에 회의 넣어줘",
                List.of(event("기존 일정", "WORK", "2026-06-06T05:00:00Z", "2026-06-06T06:00:00Z", "LOCAL_ONLY")),
                List.of(), List.of(), List.of(), List.of()
        ));

        assertThat(resolved.commands().getFirst().payload()).containsEntry("requestKind", "conflict");
        assertThat(resolved.explanation()).contains("이미 일정");
    }

    @Test
    void anytimeWorkoutSuggestsCandidateSlots() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create", "event", null, "운동", null, null, null,
                null, null, null,
                List.of("startTime"), List.of(), 0.60, true, ""
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithContext(
                "아무 때나 운동 넣어줘",
                List.of(), List.of(), List.of(), List.of(),
                List.of(window("2026-06-05T10:00:00Z", "2026-06-05T11:00:00Z", "오늘 19:00-20:00"))
        ));

        assertThat(resolved.commands().getFirst().payload()).containsEntry("requestKind", "availability_candidate");
    }

    @Test
    void fifteenMinuteWalkHonorsShortDuration() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create", "event", null, "산책", null, "15:00", null,
                null, null, null,
                List.of("endTime"), List.of(), 0.72, true, ""
        ));
        when(stageClient.draft(any(), any())).thenThrow(new IllegalStateException("provider down"));

        StructuredAiCommandBatch resolved = orchestrator.resolve(requestWithResolvedRange(
                "오늘 15분만 산책 넣어줘",
                "2026-06-05T01:00:00Z",
                "2026-06-09T00:00:00Z"
        ));

        assertThat(resolved.commands().getFirst().payload())
                .containsEntry("startAt", "2026-06-05T06:00:00Z")
                .containsEntry("endAt", "2026-06-05T06:15:00Z");
    }

    @Test
    void recurringRoutineAsksForExplicitScopeInsteadOfDatedFallback() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "create", "event", null, "운동", "MONDAY", null, null,
                null, null, null,
                List.of("startTime", "duration"), List.of(), 0.80, true, ""
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(request("매주 월요일 운동 넣어줘"));

        assertThat(resolved.commands().getFirst().payload()).containsEntry("requestKind", "recurring_routine");
        verify(stageClient, never()).draft(any(), any());
    }

    @Test
    void permanentCommuteRoutineChangeAsksForScope() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "update", "event", null, "출근", null, null, null,
                null, null, null,
                List.of("targetId"), List.of(), 0.80, true, ""
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(request("앞으로 출근 시간 바꿔줘"));

        assertThat(resolved.explanation()).contains("이번만인지 앞으로 계속인지");
    }

    @Test
    void destructiveProviderFailureDoesNotFakeCandidateDeletion() {
        when(stageClient.interpret(any())).thenReturn(new AiAgentInterpretation(
                "delete", "event", null, "회의", null, null, null, null, null, null,
                List.of("targetId"), List.of(), 0.80, true, ""
        ));

        StructuredAiCommandBatch resolved = orchestrator.resolve(request("이번 주 회의 다 없애줘."));

        assertThat(resolved.commands()).noneMatch(StructuredAiCommand::requiresConfirmation);
        verify(stageClient, never()).draft(any(), any());
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

    private AiAgentRequest requestWithContext(
            String reason,
            List<AiRescheduleClient.EventContext> events,
            List<AiRescheduleClient.ScheduleBlockContext> blocks,
            List<AiRescheduleClient.TaskContext> tasks,
            List<AiRescheduleClient.MessageHistoryContext> history,
            List<AiRescheduleClient.AvailabilityWindowContext> windows
    ) {
        return new AiAgentRequest(user, reason, new AiRescheduleClient.RescheduleAiContext(
                new AiRescheduleClient.UserContext(user.getId().toString(), "Asia/Seoul"),
                new AiRescheduleClient.RequestContext(
                        "manual_request",
                        reason,
                        null,
                        null,
                        "2026-06-05T01:00:00Z",
                        "2026-06-09T00:00:00Z"
                ),
                blocks,
                events,
                tasks,
                history,
                windows
        ));
    }

    private AiRescheduleClient.EventContext event(String title, String category, String startAt, String endAt, String syncState) {
        return new AiRescheduleClient.EventContext(
                java.util.UUID.randomUUID().toString(),
                title,
                null,
                category,
                startAt,
                endAt,
                "PLANNED",
                syncState
        );
    }

    private AiRescheduleClient.ScheduleBlockContext blockCtx(String day, String start, String end, String activity, String category) {
        return new AiRescheduleClient.ScheduleBlockContext(
                java.util.UUID.randomUUID().toString(),
                day,
                start,
                end,
                activity,
                category,
                null
        );
    }

    private AiRescheduleClient.TaskContext task(String title, String category) {
        return new AiRescheduleClient.TaskContext(
                java.util.UUID.randomUUID().toString(),
                title,
                null,
                category,
                null,
                60,
                0,
                (short) 3,
                "TODO",
                "LOCAL_ONLY"
        );
    }

    private AiRescheduleClient.MessageHistoryContext history(String status, String userRequest, String summary) {
        return new AiRescheduleClient.MessageHistoryContext(
                "2026-06-05T00:00:00Z",
                status,
                userRequest,
                summary,
                null
        );
    }

    private AiRescheduleClient.AvailabilityWindowContext window(String startAt, String endAt, String label) {
        return new AiRescheduleClient.AvailabilityWindowContext(
                startAt,
                endAt,
                label,
                60,
                "test"
        );
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

