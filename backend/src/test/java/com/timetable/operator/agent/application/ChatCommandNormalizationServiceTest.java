package com.timetable.operator.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.ChatExecutionType;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatCommandNormalizationServiceTest {

    private final ChatCommandNormalizationService service = new ChatCommandNormalizationService();

    @Test
    void englishSubstringsDoNotTriggerScheduleCommands() {
        ChatCommandNormalizationService.NormalizedChatCommand normalized =
                service.normalize("movie night movement memo만 남겨줘");

        assertThat(normalized.executionType()).isEqualTo(ChatExecutionType.QUERY);
        assertThat(actionTypes(normalized)).containsExactly(AgentCommandActionType.EXPLAIN_ONLY.wireValue());
    }

    @Test
    void removeWithoutScheduleIntentDoesNotTriggerMove() {
        ChatCommandNormalizationService.NormalizedChatCommand normalized =
                service.normalize("remove the old note from my memo");

        assertThat(normalized.executionType()).isEqualTo(ChatExecutionType.QUERY);
        assertThat(actionTypes(normalized)).containsExactly(AgentCommandActionType.EXPLAIN_ONLY.wireValue());
    }

    @Test
    void standaloneMoveStillCreatesRescheduleRequest() {
        ChatCommandNormalizationService.NormalizedChatCommand normalized =
                service.normalize("move meeting by 45 minutes");

        assertThat(normalized.executionType()).isEqualTo(ChatExecutionType.RESCHEDULE);
        assertThat(normalized.requiresAiPlanning()).isTrue();
        assertThat(actionTypes(normalized)).containsExactly(AgentCommandActionType.REQUEST_RESCHEDULE.wireValue());
        assertThat(normalized.commandBatch().commands().getFirst().payload()).containsEntry("suggestedShiftMinutes", 45);
    }

    @Test
    void annualLeaveScheduleRequestEscalatesToAiPlanning() {
        ChatCommandNormalizationService.NormalizedChatCommand normalized =
                service.normalize("오늘 내일 연차를 썼다. 일정을 수정해라.");

        assertThat(normalized.executionType()).isEqualTo(ChatExecutionType.RESCHEDULE);
        assertThat(normalized.requiresAiPlanning()).isTrue();
        assertThat(actionTypes(normalized)).containsExactly(AgentCommandActionType.REQUEST_RESCHEDULE.wireValue());
    }

    @Test
    void explicitUuidMoveKeepsPrebuiltFastPath() {
        ChatCommandNormalizationService.NormalizedChatCommand normalized =
                service.normalize("11111111-1111-1111-1111-111111111111 일정 30분 미뤄줘");

        assertThat(normalized.executionType()).isEqualTo(ChatExecutionType.RESCHEDULE);
        assertThat(normalized.requiresAiPlanning()).isFalse();
        assertThat(actionTypes(normalized)).containsExactly(
                AgentCommandActionType.REQUEST_RESCHEDULE.wireValue(),
                AgentCommandActionType.MOVE_EVENT.wireValue()
        );
    }

    @Test
    void calendarOnlySyncDoesNotTriggerTasks() {
        ChatCommandNormalizationService.NormalizedChatCommand normalized =
                service.normalize("calendar sync 해줘");

        assertThat(normalized.executionType()).isEqualTo(ChatExecutionType.SYNC);
        assertThat(normalized.commandBatch().commands())
                .singleElement()
                .satisfies(command -> assertThat(command.payload()).containsEntry("targetSystem", "googleCalendar"));
    }

    @Test
    void taskOnlySyncDoesNotTriggerCalendar() {
        ChatCommandNormalizationService.NormalizedChatCommand normalized =
                service.normalize("tasks sync 해줘");

        assertThat(normalized.executionType()).isEqualTo(ChatExecutionType.SYNC);
        assertThat(normalized.commandBatch().commands())
                .singleElement()
                .satisfies(command -> assertThat(command.payload()).containsEntry("targetSystem", "googleTasks"));
    }

    @Test
    void mixedSyncAndKoreanRescheduleKeepsBothActions() {
        ChatCommandNormalizationService.NormalizedChatCommand normalized =
                service.normalize("캘린더 동기화하고 회의를 30분 미뤄줘");

        assertThat(normalized.executionType()).isEqualTo(ChatExecutionType.RESCHEDULE);
        assertThat(actionTypes(normalized)).containsExactly(
                AgentCommandActionType.RUN_SYNC.wireValue(),
                AgentCommandActionType.REQUEST_RESCHEDULE.wireValue()
        );
    }

    @Test
    void blankMessageIsRejected() {
        assertThatThrownBy(() -> service.normalize("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message");
    }

    private static List<String> actionTypes(ChatCommandNormalizationService.NormalizedChatCommand normalized) {
        return normalized.commandBatch().commands().stream()
                .map(StructuredAiCommand::actionType)
                .toList();
    }
}
