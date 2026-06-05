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
}
