package com.timetable.operator.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.AgentCommandTargetType;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiRequestProposalMatchServiceTest {

    private final AiRequestProposalMatchService matchService = new AiRequestProposalMatchService();

    @Test
    void createBlocksActionTargetTimeAndIntentMismatches() {
        AiAgentInterpretation interpretation = createEvent();
        assertThat(match(interpretation, createEventCommand("15:00", "16:00", "회의")).matched()).isTrue();
        assertThat(match(interpretation, command(AgentCommandActionType.DELETE_EVENT, AgentCommandTargetType.EVENT, null, Map.of())).reason()).isEqualTo("action_mismatch");
        assertThat(match(interpretation, command(AgentCommandActionType.RECOMMEND_TASK, AgentCommandTargetType.TASK, null, Map.of("title", "회의"))).reason()).isEqualTo("target_type_mismatch");
        assertThat(match(interpretation, createEventCommand("17:00", "18:00", "회의")).reason()).isEqualTo("time_mismatch");
        assertThat(match(interpretation, createEventCommand("15:00", "16:00", "운동")).reason()).isEqualTo("title_mismatch");
    }

    @Test
    void createCanMatchAssistantDefaultedDateAndEndTimeWhenTitleAndStartAreClear() {
        AiAgentInterpretation interpretation = new AiAgentInterpretation(
                "create", "event", null, "점심약속", null, "12:00", null, null, null, null,
                List.of("date", "endTime"), List.of(), 0.72, true, "점심 약속은 몇 시까지인가요?"
        );

        AiRequestProposalMatchService.MatchResult result = matchService.requireMatch(
                "오늘 12시에 점심약속",
                interpretation,
                batch(command(
                        AgentCommandActionType.CREATE_EVENT,
                        AgentCommandTargetType.EVENT,
                        null,
                        Map.of(
                                "title", "점심약속",
                                "startAt", "2026-06-05T03:00:00Z",
                                "endAt", "2026-06-05T04:00:00Z",
                                "category", "LIFE"
                        )
                )),
                "Asia/Seoul"
        );

        assertThat(result.matched()).isTrue();
    }

    @Test
    void createBlocksDefaultedDatedStartWhenLocalClockTimeDiffers() {
        AiAgentInterpretation interpretation = new AiAgentInterpretation(
                "create", "event", null, "점심약속", null, "12:00", null, null, null, null,
                List.of("date", "endTime"), List.of(), 0.72, true, "점심 약속은 몇 시까지인가요?"
        );

        AiRequestProposalMatchService.MatchResult result = matchService.requireMatch(
                "오늘 12시에 점심약속",
                interpretation,
                batch(command(
                        AgentCommandActionType.CREATE_EVENT,
                        AgentCommandTargetType.EVENT,
                        null,
                        Map.of(
                                "title", "점심약속",
                                "startAt", "2026-06-05T04:00:00Z",
                                "endAt", "2026-06-05T05:00:00Z",
                                "category", "LIFE"
                        )
                )),
                "Asia/Seoul"
        );

        assertThat(result.reason()).isEqualTo("time_mismatch");
    }

    @Test
    void updateRequiresActionTargetAndIntentMatch() {
        String targetId = UUID.randomUUID().toString();
        AiAgentInterpretation interpretation = new AiAgentInterpretation("update", "event", targetId, "주간회의", null, null, null, null, null, null, List.of(), List.of(), 0.9, true, "");
        assertThat(matchService.requireMatch("기존 회의 제목을 주간회의로 바꿔", interpretation, batch(command(AgentCommandActionType.UPDATE_EVENT, AgentCommandTargetType.EVENT, targetId, Map.of("title", "주간회의")))).matched()).isTrue();
        assertThat(match(interpretation, command(AgentCommandActionType.DELETE_EVENT, AgentCommandTargetType.EVENT, targetId, Map.of())).reason()).isEqualTo("action_mismatch");
        assertThat(match(interpretation, command(AgentCommandActionType.UPDATE_EVENT, AgentCommandTargetType.EVENT, UUID.randomUUID().toString(), Map.of("title", "주간회의"))).reason()).isEqualTo("target_mismatch");
        assertThat(match(interpretation, command(AgentCommandActionType.UPDATE_EVENT, AgentCommandTargetType.TASK, targetId, Map.of("title", "주간회의"))).reason()).isEqualTo("target_type_mismatch");
        assertThat(matchService.requireMatch("기존 회의 제목을 주간회의로 바꿔", interpretation, batch(command(AgentCommandActionType.UPDATE_EVENT, AgentCommandTargetType.EVENT, targetId, Map.of("priority", 5)))).reason()).isEqualTo("intent_mismatch");
        AiAgentInterpretation timeChange = new AiAgentInterpretation("update", "event", targetId, null, null, "10:00", "11:00", null, null, null, List.of(), List.of(), 0.9, true, "");
        assertThat(match(timeChange, command(AgentCommandActionType.UPDATE_EVENT, AgentCommandTargetType.EVENT, targetId, Map.of("startTime", "11:00", "endTime", "12:00"))).reason()).isEqualTo("time_mismatch");
    }

    @Test
    void updatePriorityRequestCanUseTitleAsExactTargetName() {
        String targetId = UUID.randomUUID().toString();
        AiAgentInterpretation interpretation = new AiAgentInterpretation("update", "task", targetId, "세금 신고", null, null, null, null, null, null, List.of(), List.of(), 0.9, true, "");

        assertThat(matchService.requireMatch(
                "세금 신고 우선순위 올려줘",
                interpretation,
                batch(command(AgentCommandActionType.UPDATE_EVENT, AgentCommandTargetType.TASK, targetId, Map.of("priority", 5)))
        ).matched()).isTrue();
        assertThat(matchService.requireMatch(
                "세금 신고 우선순위 올려줘",
                interpretation,
                batch(command(AgentCommandActionType.UPDATE_EVENT, AgentCommandTargetType.TASK, targetId, Map.of("title", "세금 신고")))
        ).reason()).isEqualTo("intent_mismatch");
    }

    @Test
    void moveRequiresShiftAndTargetMatch() {
        String targetId = UUID.randomUUID().toString();
        AiAgentInterpretation interpretation = new AiAgentInterpretation("move", "event", targetId, "회의", null, null, null, null, null, 30L, List.of(), List.of(), 0.9, true, "");
        assertThat(match(interpretation, command(AgentCommandActionType.MOVE_EVENT, AgentCommandTargetType.EVENT, targetId, Map.of("suggestedShiftMinutes", 30))).matched()).isTrue();
        assertThat(match(interpretation, command(AgentCommandActionType.UPDATE_EVENT, AgentCommandTargetType.EVENT, targetId, Map.of("suggestedShiftMinutes", 30))).reason()).isEqualTo("action_mismatch");
        assertThat(match(interpretation, command(AgentCommandActionType.MOVE_EVENT, AgentCommandTargetType.EVENT, UUID.randomUUID().toString(), Map.of("suggestedShiftMinutes", 30))).reason()).isEqualTo("target_mismatch");
        assertThat(match(interpretation, command(AgentCommandActionType.MOVE_EVENT, AgentCommandTargetType.EVENT, targetId, Map.of("suggestedShiftMinutes", 120))).reason()).isEqualTo("time_mismatch");
        assertThat(match(interpretation, command(AgentCommandActionType.MOVE_EVENT, AgentCommandTargetType.TASK, targetId, Map.of("suggestedShiftMinutes", 30))).reason()).isEqualTo("target_type_mismatch");
    }

    @Test
    void deleteRequiresExplicitTargetAndDeleteIntent() {
        String targetId = UUID.randomUUID().toString();
        AiAgentInterpretation interpretation = new AiAgentInterpretation("delete", "event", targetId, "회의", null, null, null, null, null, null, List.of(), List.of(), 0.9, true, "");
        assertThat(matchService.requireMatch(targetId + " 일정 삭제", interpretation, batch(command(AgentCommandActionType.DELETE_EVENT, AgentCommandTargetType.EVENT, targetId, Map.of()))).matched()).isTrue();
        assertThat(match(interpretation, command(AgentCommandActionType.UPDATE_EVENT, AgentCommandTargetType.EVENT, targetId, Map.of("title", "회의"))).reason()).isEqualTo("action_mismatch");
        assertThat(match(new AiAgentInterpretation("delete", "event", null, "회의", null, null, null, null, null, null, List.of(), List.of(), 0.9, true, ""), command(AgentCommandActionType.DELETE_EVENT, AgentCommandTargetType.EVENT, targetId, Map.of())).reason()).isEqualTo("missing_target_match");
        assertThat(matchService.requireMatch("회의 처리해줘", interpretation, batch(command(AgentCommandActionType.DELETE_EVENT, AgentCommandTargetType.EVENT, targetId, Map.of()))).reason())
                .isEqualTo("delete_intent_not_explicit");
    }

    @Test
    void broadSyncOrExplainCannotBecomeMutation() {
        AiAgentInterpretation broad = new AiAgentInterpretation("request_reschedule", "event", null, null, null, null, null, null, null, null, List.of(), List.of(), 0.9, false, "");
        assertThat(match(broad, createEventCommand("15:00", "16:00", "회의")).reason()).isEqualTo("broad_or_non_mutation_intent");
    }

    private AiRequestProposalMatchService.MatchResult match(AiAgentInterpretation interpretation, StructuredAiCommand command) {
        return matchService.requireMatch("요청", interpretation, batch(command));
    }

    private AiAgentInterpretation createEvent() {
        return new AiAgentInterpretation("create", "event", null, "회의", "WEDNESDAY", "15:00", "16:00", null, null, null, List.of(), List.of(), 0.9, true, "");
    }

    private StructuredAiCommand createEventCommand(String start, String end, String title) {
        return command(AgentCommandActionType.CREATE_EVENT, AgentCommandTargetType.EVENT, null, Map.of(
                "dayOfWeek", "WEDNESDAY",
                "startTime", start,
                "endTime", end,
                "activity", title,
                "category", "WORK"
        ));
    }

    private StructuredAiCommand command(AgentCommandActionType action, AgentCommandTargetType target, String targetId, Map<String, Object> payload) {
        return new StructuredAiCommand(action.wireValue(), target.wireValue(), targetId, payload, "test", true);
    }

    private StructuredAiCommandBatch batch(StructuredAiCommand command) {
        return new StructuredAiCommandBatch("summary", "explanation", List.of(command));
    }
}
