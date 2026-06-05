package com.timetable.operator.agent.application;

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
        StructuredAiCommandBatch policyBatch = assistantPolicyBatch(request, interpretation);
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
        StructuredAiCommandBatch conflict = conflictGuardBatch(request, draft);
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

    private StructuredAiCommandBatch assistantPolicyBatch(AiAgentRequest request, AiAgentInterpretation interpretation) {
        String text = normalize(request.reason());
        if (isRecurringRoutineRequest(text)) {
            return clarificationBatch(
                    "반복 일정으로 바꿀 범위를 확인해야 합니다. 요일, 시간, 기간이 이번만인지 앞으로 계속인지 알려주세요.",
                    "recurring_routine_scope_required",
                    Map.of("requestKind", "recurring_routine")
            );
        }
        if (text.contains("출장") && !hasDateCue(text)) {
            return clarificationBatch(
                    "출장 날짜와 대략적인 시간대, 장소를 알려주면 해당 기간의 근무/출퇴근/루틴 영향을 정리해 드릴게요.",
                    "travel_range_required",
                    Map.of("requestKind", "status_declaration", "mode", "출장")
            );
        }
        if (isStatusDeclaration(text)) {
            return statusDeclarationBatch(request, text);
        }
        if (isBulkDestructiveRequest(text)) {
            return bulkDestructiveBatch(request, text);
        }
        if (text.contains("퇴근후")) {
            StructuredAiCommandBatch afterWork = afterWorkCreateBatch(request, interpretation);
            if (afterWork != null) {
                return afterWork;
            }
        }
        if (isAvailabilitySeekingCreate(text, interpretation)) {
            return availabilityCandidateBatch(request, interpretation, text);
        }
        if (isFollowUpReference(text) && !hasSingleHistoryAnchor(request)) {
            return clarificationBatch(
                    "어떤 제안을 말하는지 하나로 좁혀지지 않습니다. 바꿀 일정이나 직전 제안을 다시 찍어 주세요.",
                    "ambiguous_follow_up_target",
                    Map.of("requestKind", "follow_up")
            );
        }
        return null;
    }

    private StructuredAiCommandBatch conflictGuardBatch(AiAgentRequest request, StructuredAiCommandBatch draft) {
        if (draft == null || draft.commands() == null || request.context() == null || request.context().events() == null) {
            return null;
        }
        for (StructuredAiCommand command : draft.commands()) {
            if (!AgentCommandActionType.CREATE_EVENT.wireValue().equals(command.actionType())) {
                continue;
            }
            Instant startAt = parseInstant(readString(command.payload(), "startAt", "start_at"));
            Instant endAt = parseInstant(readString(command.payload(), "endAt", "end_at"));
            if (startAt == null || endAt == null) {
                continue;
            }
            List<String> conflicts = request.context().events().stream()
                    .filter(event -> overlaps(startAt, endAt, parseInstant(event.startAt()), parseInstant(event.endAt())))
                    .limit(5)
                    .map(AiRescheduleClient.EventContext::title)
                    .toList();
            if (!conflicts.isEmpty()) {
                return assistantProposalBatch(
                        "시간 충돌이 있습니다",
                        "요청한 시간에 이미 일정이 있어 바로 추가하지 않았습니다. 겹친 일정을 확인하고 다른 시간을 고르거나 조정 여부를 알려주세요.",
                        "event_time_conflict",
                        Map.of(
                                "requestKind", "conflict",
                                "conflicts", conflicts,
                                "requiresUserConfirmation", true
                        )
                );
            }
        }
        return null;
    }

    private boolean overlaps(Instant startAt, Instant endAt, Instant otherStartAt, Instant otherEndAt) {
        return startAt != null && endAt != null && otherStartAt != null && otherEndAt != null
                && startAt.isBefore(otherEndAt) && endAt.isAfter(otherStartAt);
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private StructuredAiCommandBatch afterWorkCreateBatch(AiAgentRequest request, AiAgentInterpretation interpretation) {
        AiRescheduleClient.RescheduleAiContext context = request.context();
        if (context == null || context.weeklyBlocks() == null) {
            return null;
        }
        AiRescheduleClient.ScheduleBlockContext workBlock = context.weeklyBlocks().stream()
                .filter(block -> isWorkLike(block.activity(), block.note(), block.category()))
                .findFirst()
                .orElse(null);
        if (workBlock == null) {
            return null;
        }
        LocalTime endTime = parseLocalTime(workBlock.endTime());
        if (endTime == null) {
            return null;
        }
        ZoneId userZone = AiLocalDateTimeParser.resolveUserZone(request.user().getTimezone());
        ZonedDateTime planningStart = planningStart(request, userZone);
        LocalDate date = planningStart.toLocalDate();
        if (endTime.isBefore(planningStart.toLocalTime())) {
            date = date.plusDays(1);
        }
        String title = interpretation.title() == null || interpretation.title().isBlank()
                ? inferTitleFromText(request.reason())
                : interpretation.title();
        ZonedDateTime startAt = date.atTime(endTime).atZone(userZone);
        ZonedDateTime endAt = startAt.plusMinutes(inferDurationMinutes(request.reason(), title));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("startAt", startAt.toInstant().toString());
        payload.put("endAt", endAt.toInstant().toString());
        payload.put("category", inferCategory(request.reason()).name());
        return new StructuredAiCommandBatch(
                title + " 추가",
                "퇴근 이후 빈 시간대로 해석해 확인용 일정을 만들었습니다.",
                List.of(new StructuredAiCommand(
                        AgentCommandActionType.CREATE_EVENT.wireValue(),
                        AgentCommandTargetType.EVENT.wireValue(),
                        null,
                        Map.copyOf(payload),
                        "퇴근 후 요청을 근무 종료 시각 기준으로 보정했습니다.",
                        true
                ))
        );
    }

    private StructuredAiCommandBatch statusDeclarationBatch(AiAgentRequest request, String normalizedText) {
        AiRescheduleClient.RescheduleAiContext context = request.context();
        List<String> impactedEvents = context == null ? List.of() : context.events().stream()
                .filter(event -> isWorkLike(event.title(), event.description(), event.category()))
                .limit(8)
                .map(event -> event.title() + externalSuffix(event.syncState()))
                .toList();
        List<String> impactedBlocks = context == null ? List.of() : context.weeklyBlocks().stream()
                .filter(block -> isWorkLike(block.activity(), block.note(), block.category()))
                .limit(8)
                .map(block -> block.dayOfWeek() + " " + block.startTime() + "-" + block.endTime() + " " + block.activity())
                .toList();
        List<String> impactedTasks = context == null ? List.of() : context.tasks().stream()
                .filter(task -> isWorkLike(task.title(), task.description(), task.category()))
                .limit(8)
                .map(AiRescheduleClient.TaskContext::title)
                .toList();
        String mode = normalizedText.contains("반차") ? "반차" :
                normalizedText.contains("출장") ? "출장" :
                        (normalizedText.contains("아파") || normalizedText.contains("몸이안좋") || normalizedText.contains("병가")) ? "저에너지/병가" : "휴가";
        String message = "%s 모드로 보고 근무/회의/출퇴근/업무 태스크 영향을 검토했습니다. 개인 일정은 보존하고 외부 일정은 직접 삭제하지 않습니다.".formatted(mode);
        return assistantProposalBatch(
                mode + " 일정 조정안",
                message,
                "status_declaration_impact_analysis",
                Map.of(
                        "requestKind", "status_declaration",
                        "mode", mode,
                        "workEvents", impactedEvents,
                        "workBlocks", impactedBlocks,
                        "workTasks", impactedTasks,
                        "externalMutationAllowed", false,
                        "requiresUserConfirmation", true
                )
        );
    }

    private StructuredAiCommandBatch bulkDestructiveBatch(AiAgentRequest request, String normalizedText) {
        AiRescheduleClient.RescheduleAiContext context = request.context();
        List<String> eventCandidates = context == null ? List.of() : context.events().stream()
                .filter(event -> !normalizedText.contains("일관련") || isWorkLike(event.title(), event.description(), event.category()))
                .limit(12)
                .map(event -> event.title() + externalSuffix(event.syncState()))
                .toList();
        List<String> blockCandidates = context == null ? List.of() : context.weeklyBlocks().stream()
                .filter(block -> !normalizedText.contains("일관련") || isWorkLike(block.activity(), block.note(), block.category()))
                .limit(12)
                .map(block -> block.dayOfWeek() + " " + block.startTime() + "-" + block.endTime() + " " + block.activity())
                .toList();
        String message = "삭제/취소 후보를 먼저 분류했습니다. 외부 일정은 직접 삭제하지 않고, 실제 적용 전 이 후보들이 맞는지 확인해야 합니다.";
        return assistantProposalBatch(
                "삭제 전 확인이 필요합니다",
                message,
                "destructive_candidate_confirmation",
                Map.of(
                        "requestKind", "destructive_bulk",
                        "eventCandidates", eventCandidates,
                        "scheduleBlockCandidates", blockCandidates,
                        "externalMutationAllowed", false,
                        "requiresUserConfirmation", true
                )
        );
    }

    private StructuredAiCommandBatch availabilityCandidateBatch(
            AiAgentRequest request,
            AiAgentInterpretation interpretation,
            String normalizedText
    ) {
        AiRescheduleClient.RescheduleAiContext context = request.context();
        List<String> windows = context == null ? List.of() : context.availabilityWindows().stream()
                .limit(3)
                .map(AiRescheduleClient.AvailabilityWindowContext::localLabel)
                .toList();
        if (windows.isEmpty() && !normalizedText.contains("아무때나") && !normalizedText.contains("이번주안에")) {
            return null;
        }
        String title = interpretation.title() == null || interpretation.title().isBlank()
                ? inferTitleFromText(request.reason())
                : interpretation.title();
        return assistantProposalBatch(
                title + " 후보 시간",
                "정확한 시간이 없어 가능한 시간 후보를 먼저 골랐습니다. 원하는 후보를 고르면 확인용 일정으로 만들 수 있습니다.",
                "availability_candidates",
                Map.of(
                        "requestKind", "availability_candidate",
                        "title", title,
                        "candidateWindows", windows,
                        "requiresUserConfirmation", true
                )
        );
    }

    private StructuredAiCommandBatch assistantProposalBatch(
            String summary,
            String explanation,
            String reason,
            Map<String, Object> payload
    ) {
        Map<String, Object> copy = new LinkedHashMap<>(payload);
        copy.put("resolutionType", "assistant_confirmation_required");
        copy.put("message", explanation);
        return new StructuredAiCommandBatch(
                summary,
                explanation,
                List.of(new StructuredAiCommand(
                        AgentCommandActionType.EXPLAIN_ONLY.wireValue(),
                        AgentCommandTargetType.NONE.wireValue(),
                        null,
                        Map.copyOf(copy),
                        reason,
                        false
                ))
        );
    }

    private StructuredAiCommandBatch clarificationBatch(String question, String reason, Map<String, Object> extraPayload) {
        Map<String, Object> payload = new LinkedHashMap<>(extraPayload);
        payload.put("resolutionType", "clarification_required");
        payload.put("clarificationQuestion", question);
        payload.put("message", question);
        return new StructuredAiCommandBatch(
                "확인이 필요합니다",
                question,
                List.of(new StructuredAiCommand(
                        AgentCommandActionType.EXPLAIN_ONLY.wireValue(),
                        AgentCommandTargetType.NONE.wireValue(),
                        null,
                        Map.copyOf(payload),
                        reason,
                        false
                ))
        );
    }

    private boolean isStatusDeclaration(String text) {
        return text.contains("연차") || text.contains("휴가") || text.contains("반차") || text.contains("출장")
                || text.contains("병가") || text.contains("몸이안좋") || text.contains("아파");
    }

    private boolean hasDateCue(String text) {
        return text.contains("오늘") || text.contains("내일") || text.contains("모레") || text.contains("이번주")
                || text.contains("다음주") || text.matches(".*\\d{1,2}(일|월|/|-).*");
    }

    private boolean isBulkDestructiveRequest(String text) {
        return (text.contains("다지워") || text.contains("다없애") || text.contains("싹비워") || text.contains("전부지워")
                || text.contains("모두지워") || text.contains("clearall") || text.contains("deleteall"))
                && (text.contains("일정") || text.contains("회의") || text.contains("일관련") || text.contains("오늘") || text.contains("내일") || text.contains("이번주"));
    }

    private boolean isRecurringRoutineRequest(String text) {
        return text.contains("매주") || text.contains("매일") || text.contains("반복")
                || (text.contains("앞으로") && (text.contains("출근") || text.contains("루틴") || text.contains("시간바")));
    }

    private boolean isAvailabilitySeekingCreate(String text, AiAgentInterpretation interpretation) {
        if (interpretation == null || !interpretation.executableMutationIntent()) {
            return false;
        }
        return text.contains("아무때나") || text.contains("이번주안에")
                || (text.contains("오전") && (text.contains("병원") || text.contains("회의") || text.contains("약속"))
                && (interpretation.startTime() == null || interpretation.startTime().isBlank()));
    }

    private boolean isFollowUpReference(String text) {
        return text.contains("그거") || text.contains("그건") || text.contains("아니") || text.equals("12시");
    }

    private boolean hasSingleHistoryAnchor(AiAgentRequest request) {
        AiRescheduleClient.RescheduleAiContext context = request.context();
        return context != null && context.messageHistory() != null && context.messageHistory().size() == 1;
    }

    private boolean isWorkLike(String title, String description, String category) {
        String text = normalize((title == null ? "" : title) + " " + (description == null ? "" : description) + " " + (category == null ? "" : category));
        return text.contains("work") || text.contains("업무") || text.contains("근무") || text.contains("회의") || text.contains("미팅")
                || text.contains("출근") || text.contains("퇴근") || text.contains("focus") || text.contains("집중");
    }

    private String externalSuffix(String syncState) {
        if (syncState == null || syncState.equalsIgnoreCase("LOCAL_ONLY")) {
            return "";
        }
        return " (외부 원본 보호)";
    }

    private String inferTitleFromText(String reason) {
        String text = reason == null ? "일정" : reason;
        if (text.contains("운동")) {
            return "운동";
        }
        if (text.contains("병원")) {
            return "병원";
        }
        if (text.contains("머리")) {
            return "미용실";
        }
        if (text.contains("산책")) {
            return "산책";
        }
        return "일정";
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
