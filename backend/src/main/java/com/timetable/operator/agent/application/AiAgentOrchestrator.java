package com.timetable.operator.agent.application;

import com.timetable.operator.agent.application.policy.AssistantPolicyService;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.AgentCommandTargetType;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final AssistantPolicyService assistantPolicyService;

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
        StructuredAiCommandBatch policyBatch = assistantPolicyService.preflight(request, interpretation);
        if (policyBatch != null) {
            return policyBatch;
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
        StructuredAiCommandBatch conflict = assistantPolicyService.postflightConflictGuard(request, draft);
        if (conflict != null) {
            return conflict;
        }
        ResolutionAttempt firstAttempt = evaluate(request, interpretation, draft);
        if (firstAttempt.accepted()) {
            return firstAttempt.batch();
        }
        StructuredAiCommandBatch deterministicRepair = deterministicRepair(request, interpretation, firstAttempt.failure());
        if (deterministicRepair != null) {
            ResolutionAttempt deterministicAttempt = evaluate(request, interpretation, deterministicRepair);
            if (deterministicAttempt.accepted()) {
                return deterministicAttempt.batch();
            }
        }
        if (!firstAttempt.failure().repairable()) {
            return clarification(firstAttempt.failure());
        }

        StructuredAiCommandBatch repaired = stageClient.repair(request, interpretation, firstAttempt.batch(), firstAttempt.failure());
        ResolutionAttempt repairedAttempt = evaluate(request, interpretation, repaired);
        if (repairedAttempt.accepted()) {
            return repairedAttempt.batch();
        }
        deterministicRepair = deterministicRepair(request, interpretation, repairedAttempt.failure());
        if (deterministicRepair != null) {
            ResolutionAttempt deterministicAttempt = evaluate(request, interpretation, deterministicRepair);
            if (deterministicAttempt.accepted()) {
                return deterministicAttempt.batch();
            }
        }
        return clarification(repairedAttempt.failure());
    }

    private StructuredAiCommandBatch deterministicRepair(
            AiAgentRequest request,
            AiAgentInterpretation interpretation,
            AiRequestProposalMatchService.MatchResult failure
    ) {
        if (failure == null || !isDefaultableCreateValidationFailure(failure.reason())) {
            return null;
        }
        return defaultableCreateFallback(request, interpretation);
    }

    private boolean isDefaultableCreateValidationFailure(String reason) {
        if (reason == null) {
            return false;
        }
        return reason.equals("missing_schedule_create_fields")
                || reason.equals("invalid_event_time_range")
                || reason.equals("invalid_schedule_time_range");
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
                || (isBlank(interpretation.startAt()) && isBlank(interpretation.startTime()))) {
            return null;
        }
        FallbackTimeWindow timeWindow = resolveFallbackTimeWindow(request, interpretation);
        if (timeWindow == null) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", interpretation.title().trim());
        payload.put("startAt", timeWindow.startAt());
        payload.put("endAt", timeWindow.endAt());
        payload.put("category", inferCategory(request.reason()).name());
        String reason = "요청을 이해했지만 자동 생성 응답이 불안정해, 확인 가능한 시간 정보만 바탕으로 변경안을 준비했습니다. 반영 전 한 번만 확인해 주세요.";
        return new StructuredAiCommandBatch(
                interpretation.title().trim() + " 추가",
                "요청 의도와 시간이 명확해 확인용 변경안을 준비했습니다.",
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

    private FallbackTimeWindow resolveFallbackTimeWindow(AiAgentRequest request, AiAgentInterpretation interpretation) {
        if (!isBlank(interpretation.startAt()) && !isBlank(interpretation.endAt())) {
            return new FallbackTimeWindow(interpretation.startAt().trim(), interpretation.endAt().trim());
        }
        LocalTime startTime = parseLocalTime(interpretation.startTime());
        if (startTime == null) {
            return null;
        }
        ZoneId userZone = AiLocalDateTimeParser.resolveUserZone(request.user().getTimezone());
        ZonedDateTime planningStart = planningStart(request, userZone);
        LocalDate date = inferFallbackDate(request.reason(), planningStart, startTime);
        ZonedDateTime startAt = date.atTime(startTime).atZone(userZone);
        ZonedDateTime endAt = fallbackEndAt(request, interpretation, startAt);
        if (!endAt.isAfter(startAt)) {
            return null;
        }
        return new FallbackTimeWindow(startAt.toInstant().toString(), endAt.toInstant().toString());
    }

    private ZonedDateTime planningStart(AiAgentRequest request, ZoneId userZone) {
        String resolvedRangeStart = request.context() == null || request.context().request() == null
                ? null
                : request.context().request().resolvedRangeStart();
        if (!isBlank(resolvedRangeStart)) {
            try {
                return Instant.parse(resolvedRangeStart.trim()).atZone(userZone);
            } catch (DateTimeParseException ignored) {
                // Fall through to current time when context carries a malformed value.
            }
        }
        return ZonedDateTime.now(userZone);
    }

    private LocalDate inferFallbackDate(String reason, ZonedDateTime planningStart, LocalTime startTime) {
        String text = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (text.contains("모레")) {
            return planningStart.toLocalDate().plusDays(2);
        }
        if (text.contains("내일") || text.contains("tomorrow")) {
            return planningStart.toLocalDate().plusDays(1);
        }
        if (text.contains("오늘") || text.contains("today")) {
            return planningStart.toLocalDate();
        }
        LocalDate date = planningStart.toLocalDate();
        return startTime.isAfter(planningStart.toLocalTime()) ? date : date.plusDays(1);
    }

    private ZonedDateTime fallbackEndAt(AiAgentRequest request, AiAgentInterpretation interpretation, ZonedDateTime startAt) {
        if (!isBlank(interpretation.endAt())) {
            try {
                return Instant.parse(interpretation.endAt().trim()).atZone(startAt.getZone());
            } catch (DateTimeParseException ignored) {
                // Fall through to endTime/default duration.
            }
        }
        LocalTime endTime = parseLocalTime(interpretation.endTime());
        if (endTime != null) {
            ZonedDateTime endAt = startAt.toLocalDate().atTime(endTime).atZone(startAt.getZone());
            return endAt.isAfter(startAt) ? endAt : endAt.plusDays(1);
        }
        return startAt.plusMinutes(inferDurationMinutes(request.reason(), interpretation.title()));
    }

    private LocalTime parseLocalTime(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private long inferDurationMinutes(String reason, String title) {
        String text = ((reason == null ? "" : reason) + " " + (title == null ? "" : title)).toLowerCase(Locale.ROOT);
        if (text.contains("잠깐") || text.contains("짧게") || text.contains("10분") || text.contains("15분")
                || text.contains("brief") || text.contains("quick")) {
            return 15;
        }
        if (text.contains("점심") || text.contains("밥") || text.contains("식사") || text.contains("lunch")
                || text.contains("meal") || text.contains("회의") || text.contains("미팅") || text.contains("meeting")
                || text.contains("운동") || text.contains("exercise")) {
            return 60;
        }
        if (text.contains("출근") || text.contains("퇴근") || text.contains("commute")) {
            return 45;
        }
        return 60;
    }

    private ScheduleCategory inferCategory(String reason) {
        String text = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (text.contains("운동") || text.contains("헬스") || text.contains("병원") || text.contains("health")) {
            return ScheduleCategory.HEALTH;
        }
        if (text.contains("근무") || text.contains("업무") || text.contains("회의") || text.contains("미팅")
                || text.contains("출근") || text.contains("퇴근") || text.contains("work") || text.contains("meeting")) {
            return ScheduleCategory.WORK;
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

    private String readString(Map<String, Object> payload, String... keys) {
        if (payload == null) {
            return null;
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString().trim();
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
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

    private record FallbackTimeWindow(String startAt, String endAt) {
    }
}
