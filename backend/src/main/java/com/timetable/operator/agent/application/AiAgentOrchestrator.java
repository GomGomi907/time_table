package com.timetable.operator.agent.application;

import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.AgentCommandTargetType;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
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
        if ((interpretation.lowConfidence() || interpretation.hasMissingOrAmbiguousFields())
                && !interpretation.canDraftWithAssistantDefaults()) {
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

        StructuredAiCommandBatch draft = draftWithFallback(request, interpretation);
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

    private StructuredAiCommandBatch draftWithFallback(AiAgentRequest request, AiAgentInterpretation interpretation) {
        try {
            return stageClient.draft(request, interpretation);
        } catch (RuntimeException exception) {
            StructuredAiCommandBatch fallback = defaultableCreateFallback(request, interpretation);
            if (fallback == null) {
                throw exception;
            }
            log.warn(
                    "AI draft provider failed; using deterministic defaultable create fallback for user {}.",
                    request.user().getId(),
                    exception
            );
            return fallback;
        }
    }

    private StructuredAiCommandBatch defaultableCreateFallback(AiAgentRequest request, AiAgentInterpretation interpretation) {
        if (!interpretation.canDraftWithAssistantDefaults()
                || isBlank(interpretation.title())
                || isBlank(interpretation.startAt())
                || isBlank(interpretation.endAt())) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", interpretation.title().trim());
        payload.put("startAt", interpretation.startAt().trim());
        payload.put("endAt", interpretation.endAt().trim());
        payload.put("category", inferCategory(request.reason()).name());
        String reason = "AI 제공자 draft가 실패해 해석 단계의 날짜/시간과 안전 기본값으로 만든 임시 제안입니다. 적용 전 반드시 확인하세요.";
        return new StructuredAiCommandBatch(
                interpretation.title().trim() + " 추가",
                "요청 의도는 명확하지만 AI draft 응답이 실패해, 해석된 시작/종료 시각을 기준으로 확인용 제안을 만들었습니다.",
                List.of(new StructuredAiCommand(
                        AgentCommandActionType.CREATE_EVENT.wireValue(),
                        AgentCommandTargetType.EVENT.wireValue(),
                        null,
                        payload,
                        reason,
                        true
                ))
        );
    }

    private ScheduleCategory inferCategory(String reason) {
        String text = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (text.contains("근무") || text.contains("업무") || text.contains("회의") || text.contains("미팅")
                || text.contains("출근") || text.contains("퇴근") || text.contains("work") || text.contains("meeting")) {
            return ScheduleCategory.WORK;
        }
        if (text.contains("운동") || text.contains("헬스") || text.contains("병원") || text.contains("health")) {
            return ScheduleCategory.HEALTH;
        }
        return ScheduleCategory.LIFE;
    }

    private ResolutionAttempt evaluate(
            AiAgentRequest request,
            AiAgentInterpretation interpretation,
            StructuredAiCommandBatch draft
    ) {
        StructuredAiCommandBatch validated = validationService.requireExecutableOrClarification(
                request.user().getId(),
                request.user().getTimezone(),
                draft
        );
        AiRequestProposalMatchService.MatchResult validationRepair = repairableValidationFailure(validated, draft);
        if (!validationRepair.matched()) {
            return new ResolutionAttempt(false, draft, validationRepair);
        }
        AiRequestProposalMatchService.MatchResult matchResult = matchService.requireMatch(
                request.reason(),
                interpretation,
                validated,
                request.user().getTimezone()
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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
