package com.timetable.operator.agent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.ChatCommandLog;
import com.timetable.operator.agent.domain.ChatResultStatus;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.infrastructure.ChatCommandLogRepository;
import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.priority.application.PriorityProposalService;
import com.timetable.operator.priority.domain.PriorityProposalTargetType;
import com.timetable.operator.sync.application.SyncOrchestrationService;
import com.timetable.operator.sync.domain.SyncTargetSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatCommandOrchestrationService {

    private final ChatCommandNormalizationService chatCommandNormalizationService;
    private final RescheduleSuggestionService rescheduleSuggestionService;
    private final PriorityProposalService priorityProposalService;
    private final SyncOrchestrationService syncOrchestrationService;
    private final ChatCommandLogRepository chatCommandLogRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;

    @Transactional
    public ChatCommandResponse handle(ChatCommandRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        ChatCommandNormalizationService.NormalizedChatCommand normalized =
                chatCommandNormalizationService.normalize(request.message());

        List<ChatActionResult> actions = new ArrayList<>();

        List<StructuredAiCommand> syncCommands = normalized.commandBatch().commands().stream()
                .filter(command -> AgentCommandActionType.from(command.actionType()) == AgentCommandActionType.RUN_SYNC)
                .toList();
        for (StructuredAiCommand command : syncCommands) {
            actions.add(triggerSync(command));
        }

        boolean hasRescheduleCommands = normalized.commandBatch().commands().stream()
                .map(command -> AgentCommandActionType.from(command.actionType()))
                .anyMatch(actionType -> actionType == AgentCommandActionType.REQUEST_RESCHEDULE
                        || actionType == AgentCommandActionType.MOVE_EVENT
                        || actionType == AgentCommandActionType.UPDATE_EVENT
                        || actionType == AgentCommandActionType.CREATE_EVENT
                        || actionType == AgentCommandActionType.DELETE_EVENT);
        if (hasRescheduleCommands) {
            RescheduleSuggestionService.RescheduleSuggestionResponse suggestion =
                    rescheduleSuggestionService.createChatSuggestion(normalized);
            actions.add(new ChatActionResult(
                    "reschedule_suggestion",
                    ChatResultStatus.SUGGESTION_CREATED.wireValue(),
                    null,
                    suggestion.id(),
                    null,
                    null,
                    true
            ));
        }

        for (StructuredAiCommand command : normalized.commandBatch().commands()) {
            AgentCommandActionType actionType = AgentCommandActionType.from(command.actionType());
            if (actionType == AgentCommandActionType.PROPOSE_PRIORITY) {
                actions.add(createPriorityProposal(command));
            }
            if (actionType == AgentCommandActionType.REVERT_SUGGESTION) {
                actions.add(revertSuggestion(command));
            }
        }

        if (actions.isEmpty()) {
            actions.add(new ChatActionResult(
                    "normalized",
                    ChatResultStatus.NO_OPERATION.wireValue(),
                    null,
                    null,
                    null,
                    null,
                    false
            ));
        }

        ChatResultStatus overallStatus = determineOverallStatus(actions);
        ChatCommandLog logEntry = new ChatCommandLog();
        logEntry.setUserId(user.getId());
        logEntry.setRawMessage(normalized.rawMessage());
        logEntry.setNormalizedMessage(normalized.normalizedMessage());
        logEntry.setParsedIntent(normalized.intent());
        logEntry.setParsedPayload(writeJson(normalized.commandBatch()));
        logEntry.setExecutionType(normalized.executionType());
        logEntry.setResultStatus(overallStatus);
        logEntry.setExplanation(normalized.commandBatch().explanation());
        logEntry.setResultPayload(writeJson(actions));
        ChatCommandLog savedLog = chatCommandLogRepository.save(logEntry);

        return new ChatCommandResponse(
                normalized.intent(),
                normalized.commandBatch().explanation(),
                normalized.commandBatch(),
                actions,
                savedLog.getId().toString()
        );
    }

    private ChatActionResult triggerSync(StructuredAiCommand command) {
        try {
            String targetSystem = command.payload() == null ? null : String.valueOf(command.payload().get("targetSystem"));
            SyncOrchestrationService.ManualSyncResponse response = syncOrchestrationService.requestManualSync(
                    SyncTargetSystem.from(targetSystem),
                    new SyncOrchestrationService.ManualSyncRequest(
                            "inbound",
                            null,
                            null,
                            "proposal_first"
                    )
            );
            return new ChatActionResult(
                    command.actionType(),
                    ChatResultStatus.SYNC_REQUESTED.wireValue(),
                    null,
                    null,
                    null,
                    response.syncRunId(),
                    false
            );
        } catch (Exception exception) {
            return failedResult(command.actionType(), exception);
        }
    }

    private ChatActionResult createPriorityProposal(StructuredAiCommand command) {
        if (command.targetId() == null || command.targetId().isBlank()) {
            return new ChatActionResult(
                    command.actionType(),
                    ChatResultStatus.NO_OPERATION.wireValue(),
                    null,
                    null,
                    null,
                    null,
                    true
            );
        }

        try {
            short currentPriority = readPriority(command, "currentPriority");
            short proposedPriority = readPriority(command, "proposedPriority");
            PriorityProposalService.PriorityProposalResponse proposal = priorityProposalService.createProposal(
                    new PriorityProposalService.CreatePriorityProposalCommand(
                            PriorityProposalTargetType.from(command.targetType()),
                            UUID.fromString(command.targetId()),
                            currentPriority,
                            proposedPriority,
                            command.reason()
                    )
            );
            return new ChatActionResult(
                    command.actionType(),
                    ChatResultStatus.PRIORITY_PROPOSAL_CREATED.wireValue(),
                    command.targetId(),
                    null,
                    proposal.id(),
                    null,
                    true
            );
        } catch (Exception exception) {
            return failedResult(command.actionType(), exception);
        }
    }

    private ChatActionResult revertSuggestion(StructuredAiCommand command) {
        if (command.targetId() == null || command.targetId().isBlank()) {
            return new ChatActionResult(
                    command.actionType(),
                    ChatResultStatus.NO_OPERATION.wireValue(),
                    null,
                    null,
                    null,
                    null,
                    true
            );
        }
        try {
            RescheduleSuggestionService.RescheduleSuggestionResponse response =
                    rescheduleSuggestionService.revertSuggestion(UUID.fromString(command.targetId()), command.reason());
            return new ChatActionResult(
                    command.actionType(),
                    ChatResultStatus.REVERTED.wireValue(),
                    command.targetId(),
                    response.id(),
                    null,
                    null,
                    true
            );
        } catch (Exception exception) {
            return failedResult(command.actionType(), exception);
        }
    }

    private ChatActionResult failedResult(String type, Exception exception) {
        return new ChatActionResult(
                type,
                ChatResultStatus.FAILED.wireValue(),
                null,
                null,
                null,
                null,
                false
        );
    }

    private ChatResultStatus determineOverallStatus(List<ChatActionResult> actions) {
        if (actions.stream().anyMatch(action -> ChatResultStatus.FAILED.wireValue().equals(action.result()))) {
            return ChatResultStatus.FAILED;
        }
        if (actions.stream().anyMatch(action -> ChatResultStatus.SUGGESTION_CREATED.wireValue().equals(action.result()))) {
            return ChatResultStatus.SUGGESTION_CREATED;
        }
        if (actions.stream().anyMatch(action -> ChatResultStatus.PRIORITY_PROPOSAL_CREATED.wireValue().equals(action.result()))) {
            return ChatResultStatus.PRIORITY_PROPOSAL_CREATED;
        }
        if (actions.stream().anyMatch(action -> ChatResultStatus.SYNC_REQUESTED.wireValue().equals(action.result()))) {
            return ChatResultStatus.SYNC_REQUESTED;
        }
        if (actions.stream().anyMatch(action -> ChatResultStatus.REVERTED.wireValue().equals(action.result()))) {
            return ChatResultStatus.REVERTED;
        }
        return ChatResultStatus.NORMALIZED;
    }

    private short readPriority(StructuredAiCommand command, String key) {
        if (command.payload() == null || !command.payload().containsKey(key)) {
            return 3;
        }
        return Short.parseShort(String.valueOf(command.payload().get(key)));
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("채팅 명령 내용을 저장할 수 없습니다.", exception);
        }
    }

    public record ChatCommandRequest(
            String message
    ) {
    }

    public record ChatCommandResponse(
            String intent,
            String message,
            com.timetable.operator.agent.domain.StructuredAiCommandBatch normalized,
            List<ChatActionResult> actions,
            String logId
    ) {
    }

    public record ChatActionResult(
            String type,
            String result,
            String targetId,
            String suggestionId,
            String priorityProposalId,
            String syncRunId,
            boolean requiresConfirmation
    ) {
    }
}
