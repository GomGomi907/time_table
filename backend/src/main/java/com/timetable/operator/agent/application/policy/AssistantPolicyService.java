package com.timetable.operator.agent.application.policy;

import com.timetable.operator.agent.application.AiAgentInterpretation;
import com.timetable.operator.agent.application.AiAgentRequest;
import com.timetable.operator.agent.application.AiRescheduleClient;
import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.AgentCommandTargetType;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class AssistantPolicyService {

    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Seoul");
    private static final Pattern WEEKDAY_RANGE_PATTERN = Pattern.compile("([월화수목금토일])(?:요일)?[~\\-–—부터]+([월화수목금토일])(?:요일)?");
    private static final Pattern TIME_RANGE_PATTERN = Pattern.compile("(오전|오후)?(\\d{1,2})(?::(\\d{2})|시(?:(\\d{1,2})분?)?)?[~\\-–—]+(오전|오후)?(\\d{1,2})(?::(\\d{2})|시(?:(\\d{1,2})분?)?)?");

    public StructuredAiCommandBatch preflight(AiAgentRequest request, AiAgentInterpretation interpretation) {
        return applyPreflightPolicies(request, interpretation);
    }

    public StructuredAiCommandBatch postflightConflictGuard(AiAgentRequest request, StructuredAiCommandBatch draft) {
        return applyPostflightPolicies(request, draft);
    }

    public StructuredAiCommandBatch applyPreflightPolicies(AiAgentRequest request, AiAgentInterpretation interpretation) {
        String text = normalize(request.reason());
        StructuredAiCommandBatch explicitWeekdayCreate = explicitWeekdayRangeCreateBatch(request, interpretation, text);
        if (explicitWeekdayCreate != null) {
            return explicitWeekdayCreate;
        }
        if (isRecurringRoutineRequest(text)) {
            return clarificationBatch(
                    request,
                    "반복 일정으로 바꿀 범위를 확인해야 합니다. 요일, 시간, 기간이 이번만인지 앞으로 계속인지 알려주세요.",
                    "recurring_routine_scope_required",
                    Map.of("requestKind", "recurring_routine")
            );
        }
        if (text.contains("출장") && !hasDateCue(text)) {
            return clarificationBatch(
                    request,
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
                    request,
                    "어떤 제안을 말하는지 하나로 좁혀지지 않습니다. 바꿀 일정이나 직전 제안을 다시 찍어 주세요.",
                    "ambiguous_follow_up_target",
                    Map.of("requestKind", "follow_up")
            );
        }
        return null;
    }

    private StructuredAiCommandBatch explicitWeekdayRangeCreateBatch(
            AiAgentRequest request,
            AiAgentInterpretation interpretation,
            String normalizedText
    ) {
        if (interpretation == null) {
            return null;
        }
        if ((!isCreateAction(interpretation.action()) && !containsExplicitCreateVerb(normalizedText))
                || !isEventTarget(interpretation.targetType())) {
            return null;
        }
        List<DayOfWeek> days = parseWeekdayRange(normalizedText);
        TimeRange timeRange = parseTimeRange(normalizedText);
        if (days.isEmpty() || timeRange == null) {
            return null;
        }
        String activity = inferExplicitActivity(request.reason(), normalizedText, timeRange.endIndex(), interpretation.title());
        if (isBlank(activity)) {
            return null;
        }
        ScheduleCategory category = inferCategory(activity + " " + request.reason());
        List<StructuredAiCommand> commands = new ArrayList<>();
        for (DayOfWeek day : days) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("dayOfWeek", day.name());
            payload.put("startTime", timeRange.start().toString());
            payload.put("endTime", timeRange.end().toString());
            payload.put("activity", activity);
            payload.put("category", category.name());
            commands.add(new StructuredAiCommand(
                    AgentCommandActionType.CREATE_EVENT.wireValue(),
                    AgentCommandTargetType.EVENT.wireValue(),
                    null,
                    Map.copyOf(payload),
                    "요일과 시간이 명확해 반복 주간 일정으로 준비했습니다.",
                    true
            ));
        }
        return new StructuredAiCommandBatch(
                activity + " 추가",
                "확인이 필요합니다.",
                commands
        );
    }

    public StructuredAiCommandBatch applyPostflightPolicies(AiAgentRequest request, StructuredAiCommandBatch draft) {
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
                        request,
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
        ZoneId userZone = resolveUserZone(request.user().getTimezone());
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
        payload.putAll(contextMetadata(request));
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
                request,
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
                request,
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
                request,
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
            AiAgentRequest request,
            String summary,
            String explanation,
            String reason,
            Map<String, Object> payload
    ) {
        Map<String, Object> copy = new LinkedHashMap<>(payload);
        copy.putAll(contextMetadata(request));
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

    private StructuredAiCommandBatch clarificationBatch(AiAgentRequest request, String question, String reason, Map<String, Object> extraPayload) {
        Map<String, Object> payload = new LinkedHashMap<>(extraPayload);
        payload.putAll(contextMetadata(request));
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


    private Map<String, Object> contextMetadata(AiAgentRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request == null) {
            return metadata;
        }
        if (request.user() != null && request.user().getTimezone() != null && !request.user().getTimezone().isBlank()) {
            metadata.put("timezone", request.user().getTimezone());
        }
        AiRescheduleClient.RescheduleAiContext context = request.context();
        if (context != null && context.request() != null) {
            if (!isBlank(context.request().resolvedRangeStart())) {
                metadata.put("scopeStart", context.request().resolvedRangeStart());
            }
            if (!isBlank(context.request().resolvedRangeEnd())) {
                metadata.put("scopeEnd", context.request().resolvedRangeEnd());
            }
        }
        return metadata;
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

    private List<DayOfWeek> parseWeekdayRange(String normalizedText) {
        Matcher matcher = WEEKDAY_RANGE_PATTERN.matcher(normalizedText);
        if (!matcher.find()) {
            return List.of();
        }
        DayOfWeek start = koreanDayOfWeek(matcher.group(1));
        DayOfWeek end = koreanDayOfWeek(matcher.group(2));
        if (start == null || end == null) {
            return List.of();
        }
        List<DayOfWeek> days = new ArrayList<>();
        int current = start.getValue();
        int target = end.getValue();
        while (true) {
            days.add(DayOfWeek.of(current));
            if (current == target) {
                return days;
            }
            current = current == 7 ? 1 : current + 1;
            if (days.size() > 7) {
                return List.of();
            }
        }
    }

    private TimeRange parseTimeRange(String normalizedText) {
        Matcher matcher = TIME_RANGE_PATTERN.matcher(normalizedText);
        if (!matcher.find()) {
            return null;
        }
        LocalTime start = parseKoreanTime(matcher.group(1), matcher.group(2), firstNonBlank(matcher.group(3), matcher.group(4)));
        String endMeridiem = matcher.group(5) == null ? matcher.group(1) : matcher.group(5);
        LocalTime end = parseKoreanTime(endMeridiem, matcher.group(6), firstNonBlank(matcher.group(7), matcher.group(8)));
        if (start == null || end == null || !end.isAfter(start)) {
            return null;
        }
        return new TimeRange(start, end, matcher.end());
    }

    private LocalTime parseKoreanTime(String meridiem, String hourValue, String minuteValue) {
        if (isBlank(hourValue)) {
            return null;
        }
        try {
            int hour = Integer.parseInt(hourValue);
            int minute = isBlank(minuteValue) ? 0 : Integer.parseInt(minuteValue);
            if ("오후".equals(meridiem) && hour < 12) {
                hour += 12;
            }
            if ("오전".equals(meridiem) && hour == 12) {
                hour = 0;
            }
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return null;
            }
            return LocalTime.of(hour, minute);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String inferExplicitActivity(String originalText, String normalizedText, int titleStartIndex, String interpretedTitle) {
        if (!isBlank(interpretedTitle) && !"일정".equals(interpretedTitle.trim())) {
            return interpretedTitle.trim();
        }
        if (titleStartIndex < 0 || titleStartIndex >= normalizedText.length()) {
            return inferTitleFromText(originalText);
        }
        String title = normalizedText.substring(titleStartIndex);
        String[] removableTokens = {
                "일정추가하라고", "일정추가해줘", "일정넣어줘", "일정등록해줘",
                "추가하라고", "추가해줘", "넣어줘", "등록해줘",
                "일정추가", "추가", "넣어", "등록", "하라고", "해줘", "해"
        };
        boolean changed;
        do {
            changed = false;
            for (String token : removableTokens) {
                if (title.endsWith(token)) {
                    title = title.substring(0, title.length() - token.length());
                    changed = true;
                }
            }
        } while (changed);
        title = title.replaceAll("^[\\s,./:;]+|[\\s,./:;]+$", "");
        if (!title.isBlank()) {
            return title;
        }
        return inferTitleFromText(originalText);
    }

    private DayOfWeek koreanDayOfWeek(String value) {
        return switch (value) {
            case "월" -> DayOfWeek.MONDAY;
            case "화" -> DayOfWeek.TUESDAY;
            case "수" -> DayOfWeek.WEDNESDAY;
            case "목" -> DayOfWeek.THURSDAY;
            case "금" -> DayOfWeek.FRIDAY;
            case "토" -> DayOfWeek.SATURDAY;
            case "일" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    private boolean isCreateAction(String action) {
        if (action == null) {
            return false;
        }
        String normalized = action.trim().replace('-', '_').replace(' ', '_').toLowerCase(Locale.ROOT);
        return normalized.equals("create")
                || normalized.equals("add")
                || normalized.equals("schedule")
                || normalized.equals("create_event");
    }

    private boolean containsExplicitCreateVerb(String normalizedText) {
        return normalizedText.contains("넣어")
                || normalizedText.contains("추가")
                || normalizedText.contains("등록")
                || normalizedText.contains("만들")
                || normalizedText.contains("잡아");
    }

    private boolean isEventTarget(String targetType) {
        if (targetType == null) {
            return true;
        }
        String normalized = targetType.trim().replace('-', '_').replace(' ', '_').toLowerCase(Locale.ROOT);
        return normalized.equals("event")
                || normalized.equals("schedule")
                || normalized.equals("calendar")
                || normalized.equals("meeting");
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

    private ZoneId resolveUserZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return FALLBACK_ZONE;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (RuntimeException exception) {
            return FALLBACK_ZONE;
        }
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

    private String firstNonBlank(String first, String second) {
        return isBlank(first) ? second : first;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private record TimeRange(LocalTime start, LocalTime end, int endIndex) {
    }
}
