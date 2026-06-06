package com.timetable.operator.agent.application;

import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.AgentCommandTargetType;
import com.timetable.operator.agent.domain.ChatExecutionType;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ChatCommandNormalizationService {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"
    );
    private static final Pattern PRIORITY_PATTERN = Pattern.compile("(?:priority|우선순위)\\D*([1-5])");
    private static final Pattern HOURS_PATTERN = Pattern.compile("(\\d+)\\s*(?:hour|hours|시간)");
    private static final Pattern MINUTES_PATTERN = Pattern.compile("(\\d+)\\s*(?:minute|minutes|분)");

    public NormalizedChatCommand normalize(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new IllegalArgumentException("message는 비어 있을 수 없습니다.");
        }

        String normalizedMessage = rawMessage.trim().replaceAll("\\s+", " ");
        String lowerCaseMessage = normalizedMessage.toLowerCase(Locale.ROOT);

        String targetId = extractUuid(normalizedMessage).orElse(null);
        boolean syncRequested = containsAny(lowerCaseMessage, "동기화", "sync");
        boolean priorityRequested = containsAny(lowerCaseMessage, "우선순위", "priority");
        boolean revertRequested = containsAny(lowerCaseMessage, "되돌", "revert", "undo");
        boolean explicitRescheduleRequested = containsAny(lowerCaseMessage, "미뤄", "옮겨", "조정", "reschedule", "move");
        boolean assistantPlanningCandidate = isAssistantPlanningCandidate(lowerCaseMessage);
        boolean systemCommandOnly = syncRequested || priorityRequested || revertRequested;
        boolean requiresAiPlanning = assistantPlanningCandidate
                && !(targetId != null && explicitRescheduleRequested)
                && (explicitRescheduleRequested || !systemCommandOnly);
        boolean rescheduleRequested = explicitRescheduleRequested || requiresAiPlanning;

        Integer proposedPriority = extractPriority(normalizedMessage).orElse(null);
        int suggestedShiftMinutes = extractDurationMinutes(normalizedMessage).orElse(30);
        String targetType = detectTargetType(lowerCaseMessage);

        List<StructuredAiCommand> commands = new ArrayList<>();
        if (syncRequested) {
            for (String targetSystem : detectSyncTargets(lowerCaseMessage)) {
                commands.add(new StructuredAiCommand(
                        AgentCommandActionType.RUN_SYNC.wireValue(),
                        AgentCommandTargetType.SYNC.wireValue(),
                        null,
                        Map.of(
                                "targetSystem", targetSystem,
                                "mode", "inbound",
                                "resolvePolicy", "proposal_first"
                        ),
                        "사용자 채팅 기반 동기화 요청",
                        false
                ));
            }
        }

        if (priorityRequested) {
            commands.add(new StructuredAiCommand(
                    AgentCommandActionType.PROPOSE_PRIORITY.wireValue(),
                    targetType,
                    targetId,
                    Map.of(
                            "currentPriority", 3,
                            "proposedPriority", proposedPriority == null ? 2 : proposedPriority
                    ),
                    "우선순위 충돌은 제안 기반으로만 반영합니다.",
                    true
            ));
        }

        if (revertRequested) {
            commands.add(new StructuredAiCommand(
                    AgentCommandActionType.REVERT_SUGGESTION.wireValue(),
                    AgentCommandTargetType.SUGGESTION.wireValue(),
                    targetId,
                    Map.of(),
                    "적용 이력을 되돌리기 위한 요청입니다.",
                    true
            ));
        }

        if (rescheduleRequested) {
            commands.add(new StructuredAiCommand(
                    AgentCommandActionType.REQUEST_RESCHEDULE.wireValue(),
                    targetType,
                    targetId,
                    Map.of("suggestedShiftMinutes", suggestedShiftMinutes),
                    "자연어 재조율 요청을 suggestion 후보로 정규화했습니다.",
                    true
            ));
            if (targetId != null) {
                commands.add(new StructuredAiCommand(
                        AgentCommandActionType.MOVE_EVENT.wireValue(),
                        targetType,
                        targetId,
                        Map.of("suggestedShiftMinutes", suggestedShiftMinutes),
                        "대상 타입에 맞춰 canonical Event/Task 또는 legacy schedule block 이동안으로 해석합니다.",
                        true
                ));
            }
        }

        if (commands.isEmpty()) {
            commands.add(new StructuredAiCommand(
                    AgentCommandActionType.EXPLAIN_ONLY.wireValue(),
                    AgentCommandTargetType.NONE.wireValue(),
                    null,
                    Map.of(
                            "summary", "허용된 작업 범위 안에서 실행할 변경으로 정리하지 못했습니다.",
                            "nextActions", List.of("더 구체적인 일정/동기화/우선순위 요청으로 다시 시도해 주세요.")
                    ),
                    "범용 잡담보다 기능 중심 UX를 우선합니다.",
                    false
            ));
        }

        String intent = resolveIntent(syncRequested, priorityRequested, revertRequested, rescheduleRequested, commands.size());
        ChatExecutionType executionType = resolveExecutionType(syncRequested, rescheduleRequested, priorityRequested, revertRequested);
        String explanation = buildExplanation(syncRequested, priorityRequested, revertRequested, rescheduleRequested);

        return new NormalizedChatCommand(
                rawMessage,
                normalizedMessage,
                intent,
                executionType,
                requiresAiPlanning,
                new StructuredAiCommandBatch(intent, explanation, List.copyOf(commands))
        );
    }

    private boolean isAssistantPlanningCandidate(String lowerCaseMessage) {
        boolean scheduleTopic = containsAny(
                lowerCaseMessage,
                "일정", "스케줄", "캘린더", "계획", "할 일", "할일", "태스크", "task",
                "업무", "근무", "출근", "퇴근", "회의", "미팅", "약속", "수업", "운동", "병원", "예약",
                "연차", "휴가", "휴무", "쉬", "오늘", "내일", "모레", "이번", "다음",
                "schedule", "calendar", "meeting", "appointment", "vacation", "leave", "work"
        ) || lowerCaseMessage.matches(".*\\d{1,2}\\s*(시|:|분).*");

        boolean assistantDirective = containsAny(
                lowerCaseMessage,
                "추가", "삭제", "지워", "취소", "수정", "변경", "바꿔", "옮겨", "미뤄", "앞당", "조정",
                "정리", "관리", "잡", "넣", "빼", "비워", "도와", "봐", "검토", "알려", "추천", "제안",
                "연차", "휴가", "휴무", "쉬", "썼", "쓸", "해줘", "해라", "해",
                "add", "delete", "remove", "cancel", "update", "change", "move", "plan", "help", "suggest"
        );

        return scheduleTopic && assistantDirective;
    }

    private List<String> detectSyncTargets(String lowerCaseMessage) {
        boolean calendar = containsAny(lowerCaseMessage, "calendar", "캘린더");
        boolean tasks = containsAny(lowerCaseMessage, "tasks", "task", "할 일", "할일");
        if (calendar && tasks) {
            return List.of("googleCalendar", "googleTasks");
        }
        if (calendar) {
            return List.of("googleCalendar");
        }
        if (tasks) {
            return List.of("googleTasks");
        }
        return List.of("googleCalendar", "googleTasks");
    }

    private String detectTargetType(String lowerCaseMessage) {
        if (containsAny(lowerCaseMessage, "goal", "목표")) {
            return AgentCommandTargetType.GOAL.wireValue();
        }
        if (containsAny(lowerCaseMessage, "task", "tasks", "할 일", "할일")) {
            return AgentCommandTargetType.TASK.wireValue();
        }
        return AgentCommandTargetType.EVENT.wireValue();
    }

    private String resolveIntent(
            boolean syncRequested,
            boolean priorityRequested,
            boolean revertRequested,
            boolean rescheduleRequested,
            int commandCount
    ) {
        if (rescheduleRequested && commandCount > 1) {
            return "multi_command_reschedule";
        }
        if (syncRequested) {
            return "sync_request";
        }
        if (priorityRequested) {
            return "priority_adjustment";
        }
        if (revertRequested) {
            return "revert_suggestion";
        }
        if (rescheduleRequested) {
            return "reschedule_request";
        }
        return "explain_only";
    }

    private ChatExecutionType resolveExecutionType(
            boolean syncRequested,
            boolean rescheduleRequested,
            boolean priorityRequested,
            boolean revertRequested
    ) {
        if (rescheduleRequested || revertRequested) {
            return ChatExecutionType.RESCHEDULE;
        }
        if (syncRequested) {
            return ChatExecutionType.SYNC;
        }
        if (priorityRequested) {
            return ChatExecutionType.COMMAND;
        }
        return ChatExecutionType.QUERY;
    }

    private String buildExplanation(
            boolean syncRequested,
            boolean priorityRequested,
            boolean revertRequested,
            boolean rescheduleRequested
    ) {
        List<String> segments = new ArrayList<>();
        if (syncRequested) {
            segments.add("동기화 요청");
        }
        if (priorityRequested) {
            segments.add("우선순위 제안 요청");
        }
        if (revertRequested) {
            segments.add("되돌리기 요청");
        }
        if (rescheduleRequested) {
            segments.add("재조율 요청");
        }
        if (segments.isEmpty()) {
            return "허용된 작업형 요청으로 해석되지 않아 설명 응답으로 축소했습니다.";
        }
        return String.join(" + ", segments) + "으로 정규화했습니다.";
    }

    private Optional<String> extractUuid(String message) {
        Matcher matcher = UUID_PATTERN.matcher(message);
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }

    private Optional<Integer> extractPriority(String message) {
        Matcher matcher = PRIORITY_PATTERN.matcher(message.toLowerCase(Locale.ROOT));
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(matcher.group(1)));
    }

    private Optional<Integer> extractDurationMinutes(String message) {
        Matcher hoursMatcher = HOURS_PATTERN.matcher(message.toLowerCase(Locale.ROOT));
        if (hoursMatcher.find()) {
            return Optional.of(Integer.parseInt(hoursMatcher.group(1)) * 60);
        }
        Matcher minutesMatcher = MINUTES_PATTERN.matcher(message.toLowerCase(Locale.ROOT));
        if (minutesMatcher.find()) {
            return Optional.of(Integer.parseInt(minutesMatcher.group(1)));
        }
        return Optional.empty();
    }

    private boolean containsAny(String source, String... tokens) {
        for (String token : tokens) {
            if (containsToken(source, token)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsToken(String source, String token) {
        String normalizedToken = token.toLowerCase(Locale.ROOT);
        if (normalizedToken.chars().allMatch(character -> character < 128)) {
            return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(normalizedToken) + "(?![a-z0-9])")
                    .matcher(source)
                    .find();
        }
        return source.contains(normalizedToken);
    }

    public record NormalizedChatCommand(
            String rawMessage,
            String normalizedMessage,
            String intent,
            ChatExecutionType executionType,
            boolean requiresAiPlanning,
            StructuredAiCommandBatch commandBatch
    ) {
    }
}
