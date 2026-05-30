package com.timetable.operator.agent.application;

import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiAgentOrchestrator {

    private final AiAgentStageClient stageClient;
    private final AiCommandValidationService validationService;
    private final AiRequestProposalMatchService matchService;

    public StructuredAiCommandBatch resolve(AiAgentRequest request) {
        AiAgentInterpretation interpretation = stageClient.interpret(request);
        if (interpretation == null) {
            return validationService.clarificationRequiredBatch(
                    "요청을 어떻게 처리할지 확인이 필요합니다.",
                    List.of("intent"),
                    List.of(),
                    "missing_interpretation"
            );
        }
        if (interpretation.lowConfidence() || interpretation.hasMissingOrAmbiguousFields()) {
            return validationService.clarificationRequiredBatch(
                    interpretation.safeClarificationQuestion(),
                    interpretation.missingFields(),
                    interpretation.ambiguousFields(),
                    interpretation.lowConfidence() ? "low_confidence" : "missing_or_ambiguous_interpretation"
            );
        }
        if (!interpretation.executableMutationIntent()) {
            return validationService.clarificationRequiredBatch(
                    interpretation.safeClarificationQuestion(),
                    List.of("action"),
                    List.of(),
                    "unsupported_or_non_mutation_intent"
            );
        }

        StructuredAiCommandBatch draft = stageClient.draft(request, interpretation);
        ResolutionAttempt firstAttempt = evaluate(request, interpretation, draft);
        if (firstAttempt.accepted()) {
            return firstAttempt.batch();
        }
        if (!firstAttempt.failure().repairable()) {
            return clarification(firstAttempt.failure());
        }

        StructuredAiCommandBatch repaired = stageClient.repair(request, interpretation, firstAttempt.batch(), firstAttempt.failure());
        ResolutionAttempt repairedAttempt = evaluate(request, interpretation, repaired);
        if (repairedAttempt.accepted()) {
            return repairedAttempt.batch();
        }
        return clarification(repairedAttempt.failure());
    }

    private ResolutionAttempt evaluate(
            AiAgentRequest request,
            AiAgentInterpretation interpretation,
            StructuredAiCommandBatch draft
    ) {
        StructuredAiCommandBatch validated = validationService.requireExecutableOrClarification(request.user().getId(), draft);
        AiRequestProposalMatchService.MatchResult validationRepair = repairableValidationFailure(validated, draft);
        if (!validationRepair.matched()) {
            return new ResolutionAttempt(false, draft, validationRepair);
        }
        AiRequestProposalMatchService.MatchResult matchResult = matchService.requireMatch(
                request.reason(),
                interpretation,
                validated
        );
        return new ResolutionAttempt(matchResult.matched(), validated, matchResult);
    }

    private AiRequestProposalMatchService.MatchResult repairableValidationFailure(
            StructuredAiCommandBatch validated,
            StructuredAiCommandBatch originalDraft
    ) {
        if (hasExecutableCommands(validated) || !hasExecutableCommands(originalDraft)) {
            return AiRequestProposalMatchService.MatchResult.ok();
        }
        StructuredAiCommand clarification = validated.commands().getFirst();
        Map<String, Object> payload = clarification.payload();
        String reason = payload == null || payload.get("reason") == null ? "validation_failed" : String.valueOf(payload.get("reason"));
        boolean repairable = reason.startsWith("invalid_") || reason.contains("time_range") || reason.contains("update_fields");
        if (!repairable) {
            return AiRequestProposalMatchService.MatchResult.blocked(
                    reason,
                    validated.explanation(),
                    readStringList(payload, "missingFields"),
                    false
            );
        }
        return AiRequestProposalMatchService.MatchResult.blocked(
                reason,
                validated.explanation(),
                readStringList(payload, "missingFields"),
                true
        );
    }

    private boolean hasExecutableCommands(StructuredAiCommandBatch batch) {
        return batch != null
                && batch.commands() != null
                && batch.commands().stream().anyMatch(StructuredAiCommand::requiresConfirmation);
    }

    private List<String> readStringList(Map<String, Object> payload, String key) {
        if (payload == null || !(payload.get(key) instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    private StructuredAiCommandBatch clarification(AiRequestProposalMatchService.MatchResult failure) {
        return validationService.clarificationRequiredBatch(
                failure.question(),
                failure.missingFields(),
                List.of(),
                failure.reason()
        );
    }

    private record ResolutionAttempt(
            boolean accepted,
            StructuredAiCommandBatch batch,
            AiRequestProposalMatchService.MatchResult failure
    ) {
    }
}
