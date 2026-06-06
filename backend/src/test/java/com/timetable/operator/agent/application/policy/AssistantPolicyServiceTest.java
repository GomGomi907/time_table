package com.timetable.operator.agent.application.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.timetable.operator.agent.application.AiAgentInterpretation;
import com.timetable.operator.agent.application.AiAgentRequest;
import com.timetable.operator.agent.application.AiRescheduleClient;
import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.AgentCommandTargetType;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.auth.domain.AppUser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssistantPolicyServiceTest {

    private AssistantPolicyService policyService;
    private AppUser user;

    @BeforeEach
    void setUp() {
        policyService = new AssistantPolicyService();
        user = new AppUser();
        user.setTimezone("Asia/Seoul");
    }

    @Test
    void recurringRoutinePolicyRunsBeforeStatusDeclarationPolicy() {
        StructuredAiCommandBatch resolved = policyService.applyPreflightPolicies(
                request("매주 연차 루틴을 바꿔줘", context(List.of(), List.of(), List.of(), List.of(), List.of())),
                interpretation("update", "event", null, null, false)
        );

        assertThat(resolved.commands().getFirst().payload())
                .containsEntry("resolutionType", "clarification_required")
                .containsEntry("requestKind", "recurring_routine");
        assertThat(resolved.commands().getFirst().reason()).isEqualTo("recurring_routine_scope_required");
    }

    @Test
    void destructivePolicyListsCandidatesWithoutExecutableDelete() {
        StructuredAiCommandBatch resolved = policyService.applyPreflightPolicies(
                request("내일 일 관련 일정 다 지워줘", context(
                        List.of(event("제품 회의", "WORK", "2026-06-06T01:00:00Z", "2026-06-06T02:00:00Z", "GOOGLE")),
                        List.of(block("MONDAY", "09:00", "10:00", "업무 계획", "WORK")),
                        List.of(),
                        List.of(),
                        List.of()
                )),
                interpretation("delete", "event", null, null, false)
        );

        StructuredAiCommand command = resolved.commands().getFirst();
        assertThat(command.requiresConfirmation()).isFalse();
        assertThat(command.actionType()).isEqualTo(AgentCommandActionType.EXPLAIN_ONLY.wireValue());
        assertThat(command.payload())
                .containsEntry("requestKind", "destructive_bulk")
                .containsEntry("externalMutationAllowed", false)
                .containsEntry("requiresUserConfirmation", true);
        assertThat(command.payload().get("eventCandidates").toString()).contains("외부 원본 보호");
    }

    @Test
    void workloadReliefPolicyUsesAvailabilityWindowAndProtectsExistingWorkItems() {
        StructuredAiCommandBatch resolved = policyService.applyPreflightPolicies(
                request("이번 주 업무가 너무 빡빡한데 숨 좀 트이게 해줘", context(
                        List.of(event("고정 고객 미팅", "WORK", "2026-06-06T05:00:00Z", "2026-06-06T06:00:00Z", "DIRTY_PENDING_WRITE")),
                        List.of(block("SATURDAY", "09:00", "12:00", "업무 집중", "WORK")),
                        List.of(),
                        List.of(),
                        List.of(window("2026-06-06T07:00:00Z", "2026-06-06T07:30:00Z", "토 16:00-16:30"))
                )),
                interpretation("create", "event", "업무 정리 시간", null, true)
        );

        StructuredAiCommand command = resolved.commands().getFirst();
        assertThat(command.actionType()).isEqualTo(AgentCommandActionType.CREATE_EVENT.wireValue());
        assertThat(command.requiresConfirmation()).isTrue();
        assertThat(command.payload())
                .containsEntry("requestKind", "workload_relief")
                .containsEntry("title", "업무 정리 시간")
                .containsEntry("startAt", "2026-06-06T07:00:00Z")
                .containsEntry("endAt", "2026-06-06T07:30:00Z")
                .containsEntry("externalMutationAllowed", false)
                .containsEntry("requiresUserConfirmation", true);
        assertThat(command.payload().get("protectedItems").toString())
                .contains("고정 고객 미팅")
                .contains("외부 원본 보호")
                .contains("업무 집중");
        assertThat(resolved.explanation()).contains("고정 미팅은 보호");
    }

    @Test
    void postflightConflictPolicyBlocksOverlappingCreateBeforeValidation() {
        StructuredAiCommandBatch conflict = policyService.applyPostflightPolicies(
                request("10시에 회의 추가", context(
                        List.of(event("기존 회의", "WORK", "2026-06-05T01:00:00Z", "2026-06-05T02:00:00Z", "LOCAL_ONLY")),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()
                )),
                new StructuredAiCommandBatch("회의 추가", "draft", List.of(new StructuredAiCommand(
                        AgentCommandActionType.CREATE_EVENT.wireValue(),
                        AgentCommandTargetType.EVENT.wireValue(),
                        null,
                        Map.of("title", "새 회의", "startAt", "2026-06-05T01:30:00Z", "endAt", "2026-06-05T02:30:00Z"),
                        "draft",
                        true
                )))
        );

        assertThat(conflict).isNotNull();
        assertThat(conflict.commands().getFirst().payload())
                .containsEntry("requestKind", "conflict")
                .containsEntry("requiresUserConfirmation", true);
        assertThat(conflict.commands().getFirst().payload().get("conflicts").toString()).contains("기존 회의");
    }

    @Test
    void postflightSafetyPolicyBlocksExternalDirectDeletionDraft() {
        StructuredAiCommandBatch resolved = policyService.applyPostflightPolicies(
                request("외부 회의 삭제해줘", context(
                        List.of(event("외부 회의", "WORK", "2026-06-05T01:00:00Z", "2026-06-05T02:00:00Z", "GOOGLE")),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()
                )),
                new StructuredAiCommandBatch("외부 회의 삭제", "draft", List.of(new StructuredAiCommand(
                        AgentCommandActionType.DELETE_EVENT.wireValue(),
                        AgentCommandTargetType.EVENT.wireValue(),
                        "event-1",
                        Map.of(),
                        "draft",
                        true
                )))
        );

        StructuredAiCommand command = resolved.commands().getFirst();
        assertThat(command.requiresConfirmation()).isFalse();
        assertThat(command.actionType()).isEqualTo(AgentCommandActionType.EXPLAIN_ONLY.wireValue());
        assertThat(command.reason()).isEqualTo("external_direct_mutation_blocked");
        assertThat(command.payload())
                .containsEntry("requestKind", "external_calendar_protection")
                .containsEntry("blockedAction", AgentCommandActionType.DELETE_EVENT.wireValue())
                .containsEntry("externalMutationAllowed", false)
                .containsEntry("requiresUserConfirmation", true);
        assertThat(command.payload().get("protectedTargets").toString()).contains("외부 회의", "외부 원본 보호");
    }

    @Test
    void postflightSafetyPolicyTurnsUntargetedDeleteDraftIntoCandidateReview() {
        StructuredAiCommandBatch resolved = policyService.applyPostflightPolicies(
                request("회의 없애줘", context(
                        List.of(event("제품 회의", "WORK", "2026-06-05T01:00:00Z", "2026-06-05T02:00:00Z", "LOCAL_ONLY")),
                        List.of(block("MONDAY", "09:00", "10:00", "업무 계획", "WORK")),
                        List.of(),
                        List.of(),
                        List.of()
                )),
                new StructuredAiCommandBatch("회의 삭제", "draft", List.of(new StructuredAiCommand(
                        AgentCommandActionType.DELETE_EVENT.wireValue(),
                        AgentCommandTargetType.EVENT.wireValue(),
                        null,
                        Map.of(),
                        "draft",
                        true
                )))
        );

        StructuredAiCommand command = resolved.commands().getFirst();
        assertThat(command.requiresConfirmation()).isFalse();
        assertThat(command.actionType()).isEqualTo(AgentCommandActionType.EXPLAIN_ONLY.wireValue());
        assertThat(command.reason()).isEqualTo("postflight_destructive_candidate_confirmation");
        assertThat(command.payload())
                .containsEntry("requestKind", "destructive_bulk")
                .containsEntry("blockedAction", AgentCommandActionType.DELETE_EVENT.wireValue())
                .containsEntry("externalMutationAllowed", false)
                .containsEntry("requiresUserConfirmation", true);
        assertThat(command.payload().get("eventCandidates").toString()).contains("제품 회의");
        assertThat(command.payload().get("scheduleBlockCandidates").toString()).contains("업무 계획");
    }

    @Test
    void postflightSafetyPolicyAllowsExplicitLocalSingleDeleteForValidationAndMatch() {
        StructuredAiCommandBatch resolved = policyService.applyPostflightPolicies(
                request("제품 회의 삭제해줘", context(
                        List.of(event("제품 회의", "WORK", "2026-06-05T01:00:00Z", "2026-06-05T02:00:00Z", "LOCAL_ONLY")),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()
                )),
                new StructuredAiCommandBatch("제품 회의 삭제", "draft", List.of(new StructuredAiCommand(
                        AgentCommandActionType.DELETE_EVENT.wireValue(),
                        AgentCommandTargetType.EVENT.wireValue(),
                        "event-1",
                        Map.of(),
                        "draft",
                        true
                )))
        );

        assertThat(resolved).isNull();
    }

    private AiAgentRequest request(String reason, AiRescheduleClient.RescheduleAiContext context) {
        return new AiAgentRequest(user, reason, context);
    }

    private AiRescheduleClient.RescheduleAiContext context(
            List<AiRescheduleClient.EventContext> events,
            List<AiRescheduleClient.ScheduleBlockContext> blocks,
            List<AiRescheduleClient.TaskContext> tasks,
            List<AiRescheduleClient.MessageHistoryContext> history,
            List<AiRescheduleClient.AvailabilityWindowContext> windows
    ) {
        return new AiRescheduleClient.RescheduleAiContext(
                new AiRescheduleClient.UserContext("user-1", "Asia/Seoul"),
                new AiRescheduleClient.RequestContext("manual_request", "test", null, null, "2026-06-05T00:00:00Z", "2026-06-06T00:00:00Z"),
                blocks,
                events,
                tasks,
                history,
                windows
        );
    }

    private AiAgentInterpretation interpretation(String action, String target, String title, String startTime, boolean executable) {
        return new AiAgentInterpretation(action, target, null, title, null, startTime, null, null, null, null,
                List.of(), List.of(), 0.95, executable, "");
    }

    private AiRescheduleClient.EventContext event(String title, String category, String startAt, String endAt, String syncState) {
        return new AiRescheduleClient.EventContext("event-1", title, null, category, startAt, endAt, "PLANNED", syncState);
    }

    private AiRescheduleClient.ScheduleBlockContext block(String day, String start, String end, String activity, String category) {
        return new AiRescheduleClient.ScheduleBlockContext("block-1", day, start, end, activity, category, null);
    }

    private AiRescheduleClient.AvailabilityWindowContext window(String startAt, String endAt, String label) {
        return new AiRescheduleClient.AvailabilityWindowContext(startAt, endAt, label, 30, "test");
    }
}

