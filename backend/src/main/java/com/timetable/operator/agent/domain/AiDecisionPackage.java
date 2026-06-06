package com.timetable.operator.agent.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record AiDecisionPackage(
        String requestKind,
        String trustLevel,
        Scope scope,
        Understanding understanding,
        Analysis analysis,
        Proposal proposal,
        UserEffort userEffort,
        Privacy privacy,
        Map<String, String> trustUxSections,
        List<DisplaySection> displaySections,
        List<String> affectedItems,
        List<String> protectedItems,
        List<String> externalBlockedItems,
        List<ProposedChange> proposedChanges,
        boolean requiresConfirmation,
        String confirmationReason,
        String clarificationQuestion,
        String riskLevel
) {

    private static final String DEFAULT_TIMEZONE = "Asia/Seoul";
    private static final int MAX_ITEM_LENGTH = 160;
    private static final int MAX_SECTION_BODY_LENGTH = 500;
    private static final List<String> FORBIDDEN_TEXT_PATTERNS = List.of(
            "(?i)rawPrompt",
            "(?i)raw_prompt",
            "(?i)fullPrompt",
            "(?i)full_prompt",
            "(?i)providerMetadata",
            "(?i)provider_metadata",
            "(?i)rawResponse",
            "(?i)raw_response",
            "(?i)promptText",
            "(?i)prompt_text",
            "(?i)reasoningTrace",
            "(?i)reasoning_trace",
            "(?i)systemPrompt",
            "(?i)system_prompt",
            "(?i)Authorization",
            "(?i)x-goog-api-key",
            "(?i)api[_-]?key",
            "AIza[0-9A-Za-z_-]+",
            "(?i)Bearer\\s+\\S+"
    );

    public static AiDecisionPackage from(String requestKind, StructuredAiCommandBatch commandBatch) {
        return from(commandBatch, DEFAULT_TIMEZONE, null, null, requestKind);
    }

    public static AiDecisionPackage from(
            StructuredAiCommandBatch batch,
            String timezone,
            String scopeStart,
            String scopeEnd
    ) {
        return from(batch, timezone, scopeStart, scopeEnd, null);
    }

    public static AiDecisionPackage from(
            StructuredAiCommandBatch batch,
            String timezone,
            String scopeStart,
            String scopeEnd,
            String fallbackRequestKind
    ) {
        List<StructuredAiCommand> commands = safeCommands(batch);
        Map<String, Object> firstPayload = commands.isEmpty() ? Map.of() : safePayload(commands.getFirst().payload());
        String requestKind = firstNonBlank(
                stringValue(firstPayload.get("requestKind")),
                fallbackRequestKind,
                "general_schedule_adjustment"
        );
        String resolutionType = stringValue(firstPayload.get("resolutionType"));
        boolean needsClarification = "clarification_required".equals(resolutionType);
        boolean providerUnavailable = "provider_unavailable".equals(resolutionType)
                || commands.stream().anyMatch(command -> "provider_unavailable".equals(readString(command.payload(), "resolutionType")));
        boolean requiresConfirmation = commands.stream().anyMatch(StructuredAiCommand::requiresConfirmation)
                || commands.stream().anyMatch(command -> Boolean.TRUE.equals(read(command.payload(), "requiresUserConfirmation")));
        boolean externalMutationAllowed = commands.stream()
                .anyMatch(command -> Boolean.TRUE.equals(read(command.payload(), "externalMutationAllowed")));
        List<String> affectedItems = collectAffectedItems(commands);
        List<String> externalItems = collectExternalItems(commands);
        List<String> protectedItems = collectProtectedItems(commands, externalItems);
        List<String> conflicts = collectList(commands, "conflicts");
        List<String> risks = collectRisks(commands, needsClarification, requiresConfirmation, externalItems, conflicts);
        List<ProposedChange> proposedChanges = commands.stream()
                .map(AiDecisionPackage::toProposedChange)
                .toList();
        String summary = batch == null ? "" : nullToEmpty(batch.summary());
        String explanation = batch == null ? "" : nullToEmpty(batch.explanation());
        String question = firstNonBlank(
                commands.stream()
                        .map(command -> readString(command.payload(), "clarificationQuestion"))
                        .filter(value -> value != null && !value.isBlank())
                        .findFirst()
                        .orElse(null),
                needsClarification ? explanation : null
        );
        String confirmationReason = buildConfirmationReason(commands, risks, explanation, needsClarification, providerUnavailable);
        String riskLevel = determineRiskLevel(requestKind, requiresConfirmation, needsClarification, providerUnavailable, externalItems, conflicts);
        String trustLevel = determineTrustLevel(needsClarification, providerUnavailable, requiresConfirmation, commands);
        Scope scope = new Scope(
                firstNonBlank(scopeStart, stringValue(firstPayload.get("scopeStart")), deriveScopeStart(commands)),
                firstNonBlank(scopeEnd, stringValue(firstPayload.get("scopeEnd")), deriveScopeEnd(commands)),
                normalizeTimezone(firstNonBlank(timezone, stringValue(firstPayload.get("timezone")), DEFAULT_TIMEZONE))
        );
        Understanding understanding = new Understanding(
                compact(summary, MAX_SECTION_BODY_LENGTH),
                compact(explanation, MAX_SECTION_BODY_LENGTH),
                userFacingRequestKind(requestKind),
                firstNonBlank(stringValue(firstPayload.get("mode")), null)
        );
        Analysis analysis = new Analysis(
                affectedItems,
                protectedItems,
                externalItems,
                conflicts,
                risks
        );
        Proposal proposal = new Proposal(
                commands,
                proposedChanges,
                requiresConfirmation,
                externalMutationAllowed
        );
        UserEffort userEffort = new UserEffort(
                needsClarification,
                question,
                reviewComplexity(commands, risks, affectedItems, externalItems)
        );
        Privacy privacy = new Privacy(
                true,
                List.of("LLM 원문", "공급자 메타데이터", "추론 추적", "인증 비밀"),
                estimatePrivacyExposure(commands, externalItems, affectedItems),
                estimateCharacters(batch)
        );
        List<DisplaySection> displaySections = buildDisplaySections(
                understanding,
                affectedItems,
                protectedItems,
                externalItems,
                proposedChanges,
                confirmationReason,
                riskLevel
        );
        Map<String, String> trustUxSections = toLegacySections(displaySections);
        return new AiDecisionPackage(
                requestKind,
                trustLevel,
                scope,
                understanding,
                analysis,
                proposal,
                userEffort,
                privacy,
                trustUxSections,
                displaySections,
                affectedItems,
                protectedItems,
                externalItems,
                proposedChanges,
                requiresConfirmation,
                confirmationReason,
                question,
                riskLevel
        );
    }

    private static List<StructuredAiCommand> safeCommands(StructuredAiCommandBatch batch) {
        return batch == null || batch.commands() == null ? List.of() : batch.commands();
    }

    private static Object read(Map<String, Object> payload, String key) {
        return payload == null ? null : payload.get(key);
    }

    private static String readString(Map<String, Object> payload, String key) {
        Object value = read(payload, key);
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> collectAffectedItems(List<StructuredAiCommand> commands) {
        return distinctCompact(combine(
                collectList(commands, "affectedItems"),
                collectList(commands, "workEvents"),
                collectList(commands, "workBlocks"),
                collectList(commands, "workTasks"),
                collectList(commands, "eventCandidates"),
                collectList(commands, "scheduleBlockCandidates"),
                collectList(commands, "taskCandidates"),
                collectList(commands, "candidateWindows"),
                collectList(commands, "conflicts")
        ));
    }

    private static List<String> collectExternalItems(List<StructuredAiCommand> commands) {
        List<String> explicit = collectList(commands, "externalItems", "externalBlockedItems");
        List<String> inferredFromAllowedKeys = combine(
                        collectList(commands, "eventCandidates"),
                        collectList(commands, "workEvents")
                ).stream()
                .filter(AiDecisionPackage::looksExternal)
                .toList();
        return distinctCompact(combine(explicit, inferredFromAllowedKeys));
    }

    private static List<String> collectProtectedItems(List<StructuredAiCommand> commands, List<String> externalItems) {
        List<String> explicit = collectList(commands, "protectedItems", "protectedPersonalItems");
        if (!explicit.isEmpty()) {
            return distinctCompact(explicit);
        }
        if (!externalItems.isEmpty()) {
            return distinctCompact(combine(List.of("외부 원본 일정은 직접 변경하지 않음"), externalItems));
        }
        return List.of("개인/보호 일정은 명시적 확인 전 변경하지 않음");
    }

    private static List<String> collectRisks(
            List<StructuredAiCommand> commands,
            boolean needsClarification,
            boolean requiresConfirmation,
            List<String> externalItems,
            List<String> conflicts
    ) {
        List<String> risks = new ArrayList<>();
        if (needsClarification) {
            risks.add("추가 정보가 없으면 잘못된 일정을 바꿀 수 있음");
        }
        if (requiresConfirmation) {
            risks.add("사용자 확인 전에는 변경을 적용하지 않음");
        }
        if (!externalItems.isEmpty()) {
            risks.add("외부 원본 일정은 직접 수정/삭제하지 않음");
        }
        if (!conflicts.isEmpty()) {
            risks.add("기존 일정과 시간 충돌 가능");
        }
        commands.stream()
                .map(StructuredAiCommand::reason)
                .filter(value -> value != null && !value.isBlank())
                .forEach(risks::add);
        return distinctCompact(risks);
    }

    private static ProposedChange toProposedChange(StructuredAiCommand command) {
        Map<String, Object> payload = safePayload(command.payload());
        return new ProposedChange(
                nullToDefault(command.actionType(), "explain_only"),
                nullToDefault(command.targetType(), "none"),
                command.targetId(),
                compact(firstNonBlank(
                        stringValue(payload.get("title")),
                        stringValue(payload.get("activity")),
                        stringValue(payload.get("summary")),
                        actionLabel(command.actionType())
                ), MAX_ITEM_LENGTH),
                compact(firstNonBlank(
                        stringValue(payload.get("message")),
                        buildTimeDetail(payload),
                        command.reason()
                ), MAX_SECTION_BODY_LENGTH),
                compact(command.reason(), MAX_ITEM_LENGTH),
                command.requiresConfirmation()
        );
    }

    private static String buildTimeDetail(Map<String, Object> payload) {
        String startAt = stringValue(payload.get("startAt"));
        String endAt = stringValue(payload.get("endAt"));
        if (startAt != null && endAt != null) {
            return startAt + " - " + endAt;
        }
        String startTime = firstNonBlank(stringValue(payload.get("startTime")), stringValue(payload.get("start_time")));
        String endTime = firstNonBlank(stringValue(payload.get("endTime")), stringValue(payload.get("end_time")));
        if (startTime != null && endTime != null) {
            return startTime + " - " + endTime;
        }
        return null;
    }

    private static String buildConfirmationReason(
            List<StructuredAiCommand> commands,
            List<String> risks,
            String explanation,
            boolean needsClarification,
            boolean providerUnavailable
    ) {
        if (providerUnavailable) {
            return "AI 공급자 응답을 안정적으로 받지 못해 다시 시도해야 합니다.";
        }
        if (needsClarification) {
            return firstNonBlank(
                    commands.stream()
                            .map(command -> readString(command.payload(), "clarificationQuestion"))
                            .filter(value -> value != null && !value.isBlank())
                            .findFirst()
                            .orElse(null),
                    "추가 정보가 필요합니다."
            );
        }
        if (!risks.isEmpty()) {
            return String.join(" · ", risks.stream().limit(3).toList());
        }
        return firstNonBlank(explanation, "적용 전 변경 요약을 확인해야 합니다.");
    }

    private static String determineRiskLevel(
            String requestKind,
            boolean requiresConfirmation,
            boolean needsClarification,
            boolean providerUnavailable,
            List<String> externalItems,
            List<String> conflicts
    ) {
        String normalizedKind = requestKind == null ? "" : requestKind.toLowerCase(Locale.ROOT);
        if (providerUnavailable || normalizedKind.contains("destructive") || !externalItems.isEmpty()) {
            return "high";
        }
        if (needsClarification || requiresConfirmation || !conflicts.isEmpty() || normalizedKind.contains("status")) {
            return "medium";
        }
        return "low";
    }

    private static String determineTrustLevel(
            boolean needsClarification,
            boolean providerUnavailable,
            boolean requiresConfirmation,
            List<StructuredAiCommand> commands
    ) {
        if (providerUnavailable) {
            return "provider_unavailable";
        }
        if (needsClarification) {
            return "clarification_required";
        }
        if (requiresConfirmation) {
            return "review_required";
        }
        return commands.isEmpty() ? "informational" : "manual_review";
    }

    private static List<DisplaySection> buildDisplaySections(
            Understanding understanding,
            List<String> affectedItems,
            List<String> protectedItems,
            List<String> externalItems,
            List<ProposedChange> proposedChanges,
            String confirmationReason,
            String riskLevel
    ) {
        return List.of(
                new DisplaySection("understanding", "이렇게 이해했습니다", understanding.summary(), List.of(), "neutral"),
                new DisplaySection("affected_items", "바꾸려는 항목", listBody(affectedItems, "아직 확정된 변경 항목은 없습니다."), affectedItems, "neutral"),
                new DisplaySection("protected_items", "건드리지 않는 항목", listBody(protectedItems, "개인/보호 일정은 명시적 확인 전 변경하지 않습니다."), protectedItems, "safe"),
                new DisplaySection("external_blocked", "외부 일정이라 직접 바꾸지 않는 항목", listBody(externalItems, "없음"), externalItems, externalItems.isEmpty() ? "neutral" : "warning"),
                new DisplaySection("confirmation_reason", "확인이 필요한 이유", compact(confirmationReason, MAX_SECTION_BODY_LENGTH), List.of(), "high".equals(riskLevel) ? "warning" : "neutral"),
                new DisplaySection("proposed_changes", "적용 전 변경 요약", proposedChanges.size() + "개 후보 명령", proposedChanges.stream().map(ProposedChange::title).toList(), "neutral")
        );
    }

    private static Map<String, String> toLegacySections(List<DisplaySection> sections) {
        Map<String, String> legacy = new LinkedHashMap<>();
        for (DisplaySection section : sections) {
            legacy.put(section.label(), firstNonBlank(section.body(), String.join(", ", section.items())));
        }
        return Map.copyOf(legacy);
    }

    private static int estimatePrivacyExposure(List<StructuredAiCommand> commands, List<String> externalItems, List<String> affectedItems) {
        int score = commands.size() + Math.min(20, affectedItems.size() * 2) + Math.min(30, externalItems.size() * 5);
        return Math.min(100, score);
    }

    private static int estimateCharacters(StructuredAiCommandBatch batch) {
        return batch == null ? 0 : String.valueOf(batch).length();
    }

    private static List<String> collectList(List<StructuredAiCommand> commands, String... keys) {
        List<String> values = new ArrayList<>();
        for (StructuredAiCommand command : commands) {
            Map<String, Object> payload = safePayload(command.payload());
            for (String key : keys) {
                Object value = payload.get(key);
                if (value instanceof List<?> list) {
                    list.stream().map(String::valueOf).forEach(values::add);
                } else if (value instanceof String text && !text.isBlank()) {
                    values.add(text);
                }
            }
        }
        return distinctCompact(values);
    }

    @SafeVarargs
    private static List<String> combine(List<String>... lists) {
        List<String> combined = new ArrayList<>();
        for (List<String> list : lists) {
            combined.addAll(list);
        }
        return combined;
    }

    private static List<String> distinctCompact(List<String> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .map(AiDecisionPackage::sanitizeUserFacingText)
                .map(value -> compact(value, MAX_ITEM_LENGTH))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static Map<String, Object> safePayload(Map<String, Object> payload) {
        return payload == null ? Map.of() : payload;
    }

    private static String deriveScopeStart(List<StructuredAiCommand> commands) {
        return commands.stream()
                .map(StructuredAiCommand::payload)
                .map(AiDecisionPackage::safePayload)
                .map(payload -> firstNonBlank(stringValue(payload.get("startAt")), stringValue(payload.get("start_at"))))
                .filter(value -> value != null && isInstant(value))
                .sorted()
                .findFirst()
                .orElse(null);
    }

    private static String deriveScopeEnd(List<StructuredAiCommand> commands) {
        return commands.stream()
                .map(StructuredAiCommand::payload)
                .map(AiDecisionPackage::safePayload)
                .map(payload -> firstNonBlank(stringValue(payload.get("endAt")), stringValue(payload.get("end_at"))))
                .filter(value -> value != null && isInstant(value))
                .sorted((left, right) -> right.compareTo(left))
                .findFirst()
                .orElse(null);
    }

    private static boolean isInstant(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean looksExternal(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return value.contains("외부") || normalized.contains("external") || normalized.contains("provider") || value.contains("원본 보호");
    }

    private static String normalizeTimezone(String timezone) {
        return timezone == null || timezone.isBlank() ? DEFAULT_TIMEZONE : timezone.trim();
    }

    private static String userFacingRequestKind(String requestKind) {
        return switch (requestKind == null ? "" : requestKind) {
            case "destructive_bulk" -> "삭제/취소 후보 분류";
            case "status_declaration" -> "상태 변화에 따른 일정 영향 검토";
            case "availability_candidate" -> "가능한 시간 후보 탐색";
            case "recurring_routine" -> "반복 루틴 범위 확인";
            case "conflict" -> "시간 충돌 확인";
            default -> "일정 조율 요청";
        };
    }

    private static String reviewComplexity(List<StructuredAiCommand> commands, List<String> risks, List<String> affectedItems, List<String> externalItems) {
        if (!externalItems.isEmpty() || affectedItems.size() > 6 || risks.size() > 3) {
            return "high";
        }
        if (commands.size() > 1 || !risks.isEmpty() || affectedItems.size() > 2) {
            return "medium";
        }
        return "low";
    }

    private static String actionLabel(String actionType) {
        return switch (actionType == null ? "" : actionType) {
            case "create_event" -> "새 일정";
            case "move_event" -> "일정 이동";
            case "update_event" -> "일정 수정";
            case "delete_event" -> "일정 삭제";
            case "request_reschedule" -> "조정 요청";
            case "recommend_task" -> "추천 할 일";
            default -> "안내";
        };
    }

    private static String listBody(List<String> values, String fallback) {
        return values == null || values.isEmpty() ? fallback : String.join(", ", values.stream().limit(5).toList());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String nullToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String compact(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = sanitizeUserFacingText(value).trim().replaceAll("\\s+", " ");
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
    }

    private static String sanitizeUserFacingText(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value;
        for (String pattern : FORBIDDEN_TEXT_PATTERNS) {
            sanitized = sanitized.replaceAll(pattern, "비공개 정보");
        }
        return sanitized;
    }

    public record Scope(String start, String end, String timezone) {
    }

    public record Understanding(
            String summary,
            String explanation,
            String interpretedAs,
            String mode
    ) {
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
            @JsonIgnore List<StructuredAiCommand> commands,
            List<ProposedChange> proposedChanges,
            boolean requiresConfirmation,
            boolean externalMutationAllowed
    ) {
    }

    public record ProposedChange(
            String actionType,
            String targetType,
            String targetId,
            String title,
            String detail,
            String reason,
            boolean executable
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

    public record DisplaySection(
            String key,
            String label,
            String body,
            List<String> items,
            String severity
    ) {
    }
}
