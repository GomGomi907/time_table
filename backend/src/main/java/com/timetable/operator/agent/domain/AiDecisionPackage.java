package com.timetable.operator.agent.domain;

import java.util.List;
import java.util.Map;

public record AiDecisionPackage(
        String requestKind,
        String trustLevel,
        Scope scope,
        Analysis analysis,
        Proposal proposal,
        UserEffort userEffort,
        Privacy privacy,
        Map<String, String> trustUxSections
) {

    public static AiDecisionPackage from(String requestKind, StructuredAiCommandBatch commandBatch) {
        List<StructuredAiCommand> commands = commandBatch == null || commandBatch.commands() == null
                ? List.of()
                : commandBatch.commands();
        boolean requiresConfirmation = commands.stream().anyMatch(StructuredAiCommand::requiresConfirmation)
                || commands.stream().anyMatch(command -> Boolean.TRUE.equals(read(command.payload(), "requiresUserConfirmation")));
        boolean externalMutationAllowed = commands.stream()
                .anyMatch(command -> Boolean.TRUE.equals(read(command.payload(), "externalMutationAllowed")));
        List<String> externalItems = commands.stream()
                .flatMap(command -> readList(command.payload(), "externalItems", "eventCandidates", "workEvents").stream())
                .filter(value -> value.contains("외부") || value.toLowerCase().contains("external"))
                .distinct()
                .toList();
        List<String> risks = commands.stream()
                .map(StructuredAiCommand::reason)
                .filter(reason -> reason != null && !reason.isBlank())
                .distinct()
                .toList();
        boolean needsClarification = commands.stream()
                .anyMatch(command -> "clarification_required".equals(readString(command.payload(), "resolutionType")));
        String question = commands.stream()
                .map(command -> readString(command.payload(), "clarificationQuestion"))
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
        String summary = commandBatch == null ? "" : nullToEmpty(commandBatch.summary());
        String explanation = commandBatch == null ? "" : nullToEmpty(commandBatch.explanation());
        return new AiDecisionPackage(
                nullToDefault(requestKind, "manual_request"),
                requiresConfirmation ? "review_required" : "informational",
                new Scope(null, null, "Asia/Seoul"),
                new Analysis(List.of(), List.of(), externalItems, List.of(), risks),
                new Proposal(commands, requiresConfirmation, externalMutationAllowed),
                new UserEffort(needsClarification, question, commands.size() > 3 ? "high" : commands.isEmpty() ? "low" : "medium"),
                new Privacy(true, List.of(), estimatePrivacyExposure(commandBatch), commandBatch == null ? 0 : String.valueOf(commandBatch).length()),
                Map.of(
                        "이렇게 이해했습니다", summary,
                        "바꾸려는 항목", explanation,
                        "건드리지 않는 항목", "개인/보호 일정은 명시적 확인 전 변경하지 않습니다.",
                        "외부 일정이라 직접 바꾸지 않는 항목", externalItems.isEmpty() ? "없음" : String.join(", ", externalItems),
                        "확인이 필요한 이유", risks.isEmpty() ? "적용 전 변경 요약을 확인해야 합니다." : String.join(", ", risks),
                        "적용 전 변경 요약", commands.size() + "개 후보 명령"
                )
        );
    }

    private static Object read(Map<String, Object> payload, String key) {
        return payload == null ? null : payload.get(key);
    }

    private static String readString(Map<String, Object> payload, String key) {
        Object value = read(payload, key);
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> readList(Map<String, Object> payload, String... keys) {
        if (payload == null) {
            return List.of();
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value instanceof List<?> values) {
                return values.stream().map(String::valueOf).toList();
            }
        }
        return List.of();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String nullToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public record Scope(String start, String end, String timezone) {
    }

    public record Analysis(
            List<String> affectedWorkItems,
            List<String> protectedPersonalItems,
            List<String> externalItems,
            List<String> conflicts,
            List<String> risks
    ) {
    }

    public record Proposal(
            List<StructuredAiCommand> commands,
            boolean requiresConfirmation,
            boolean externalMutationAllowed
    ) {
    }

    public record UserEffort(
            boolean needsClarification,
            String question,
            String reviewComplexity
    ) {
    }

    public record Privacy(
            boolean contextMinimized,
            List<String> sensitiveFieldsRedacted,
            int privacyExposureScore,
            int estimatedCharacters
    ) {
    }

    public static AiDecisionPackage from(
            StructuredAiCommandBatch batch,
            String timezone,
            String scopeStart,
            String scopeEnd
    ) {
        StructuredAiCommand first = batch == null || batch.commands() == null || batch.commands().isEmpty()
                ? null
                : batch.commands().getFirst();
        Map<String, Object> payload = first == null ? Map.of() : safePayload(first.payload());
        boolean executable = batch != null
                && batch.commands() != null
                && batch.commands().stream().anyMatch(StructuredAiCommand::requiresConfirmation);
        String resolutionType = stringValue(payload.get("resolutionType"));
        boolean needsClarification = "clarification_required".equals(resolutionType);
        List<String> externalItems = valuesContaining(payload, "외부 원본 보호");
        List<String> conflicts = readStringList(payload, "conflicts");
        List<String> risks = needsClarification
                ? List.of("사용자 확인 없이는 실행하지 않습니다.")
                : List.of();
        return new AiDecisionPackage(
                stringValueOrDefault(payload.get("requestKind"), "general_schedule_adjustment"),
                executable ? "review_required" : "manual_review",
                new Scope(scopeStart, scopeEnd, timezone == null || timezone.isBlank() ? "Asia/Seoul" : timezone),
                new Analysis(
                        combine(readStringList(payload, "workEvents"), readStringList(payload, "workBlocks"), readStringList(payload, "workTasks")),
                        List.of(),
                        externalItems,
                        conflicts,
                        risks
                ),
                new Proposal(
                        batch == null || batch.commands() == null ? List.of() : batch.commands(),
                        executable || Boolean.parseBoolean(stringValueOrDefault(payload.get("requiresUserConfirmation"), "false")),
                        Boolean.parseBoolean(stringValueOrDefault(payload.get("externalMutationAllowed"), "false"))
                ),
                new UserEffort(
                        needsClarification,
                        stringValue(payload.getOrDefault("clarificationQuestion", null)),
                        executable ? "medium" : "low"
                ),
                new Privacy(
                        true,
                        List.of(),
                        estimatePrivacyExposure(batch),
                        batch == null ? 0 : String.valueOf(batch).length()
                ),
                Map.of(
                        "이렇게 이해했습니다", batch == null ? "" : nullToEmpty(batch.summary()),
                        "바꾸려는 항목", batch == null ? "" : nullToEmpty(batch.explanation()),
                        "건드리지 않는 항목", "개인/보호 일정은 명시적 확인 전 변경하지 않습니다.",
                        "외부 일정이라 직접 바꾸지 않는 항목", externalItems.isEmpty() ? "없음" : String.join(", ", externalItems),
                        "확인이 필요한 이유", risks.isEmpty() ? "적용 전 변경 요약을 확인해야 합니다." : String.join(", ", risks),
                        "적용 전 변경 요약", (batch == null || batch.commands() == null ? 0 : batch.commands().size()) + "개 후보 명령"
                )
        );
    }

    private static int estimatePrivacyExposure(StructuredAiCommandBatch batch) {
        if (batch == null || batch.commands() == null) {
            return 0;
        }
        int score = batch.commands().size();
        if (batch.commands().stream().anyMatch(command -> valuesContaining(safePayload(command.payload()), "외부 원본 보호").size() > 0)) {
            score += 5;
        }
        return Math.min(100, score);
    }

    private static Map<String, Object> safePayload(Map<String, Object> payload) {
        return payload == null ? Map.of() : payload;
    }

    private static List<String> combine(List<String> first, List<String> second, List<String> third) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        values.addAll(first);
        values.addAll(second);
        values.addAll(third);
        return List.copyOf(values);
    }

    private static List<String> readStringList(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static List<String> valuesContaining(Map<String, Object> payload, String needle) {
        return payload.values().stream()
                .filter(List.class::isInstance)
                .map(value -> (List<?>) value)
                .flatMap(List::stream)
                .map(String::valueOf)
                .filter(value -> value.contains(needle))
                .toList();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String stringValueOrDefault(Object value, String fallback) {
        String string = stringValue(value);
        return string == null || string.isBlank() ? fallback : string;
    }
}
