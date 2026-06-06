package com.timetable.operator.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiDecisionPackageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void wrapsLegacyCommandBatchWithTrustUxSectionsAndNoExternalMutation() {
        StructuredAiCommandBatch batch = new StructuredAiCommandBatch(
                "삭제 전 확인이 필요합니다",
                "삭제 후보를 먼저 분류했습니다.",
                List.of(new StructuredAiCommand(
                        AgentCommandActionType.EXPLAIN_ONLY.wireValue(),
                        AgentCommandTargetType.NONE.wireValue(),
                        null,
                        Map.of(
                                "requestKind", "destructive_bulk",
                                "eventCandidates", List.of("외부 회의 (외부 원본 보호)"),
                                "externalMutationAllowed", false,
                                "requiresUserConfirmation", true,
                                "scopeStart", "2026-06-05T00:00:00Z",
                                "scopeEnd", "2026-06-07T00:00:00Z",
                                "timezone", "Asia/Seoul"
                        ),
                        "destructive_candidate_confirmation",
                        false
                ))
        );

        AiDecisionPackage decisionPackage = AiDecisionPackage.from("destructive_bulk", batch);

        assertThat(decisionPackage.requestKind()).isEqualTo("destructive_bulk");
        assertThat(decisionPackage.trustLevel()).isEqualTo("review_required");
        assertThat(decisionPackage.scope().start()).isEqualTo("2026-06-05T00:00:00Z");
        assertThat(decisionPackage.scope().end()).isEqualTo("2026-06-07T00:00:00Z");
        assertThat(decisionPackage.proposal().externalMutationAllowed()).isFalse();
        assertThat(decisionPackage.analysis().externalItems()).contains("외부 회의 (외부 원본 보호)");
        assertThat(decisionPackage.externalBlockedItems()).contains("외부 회의 (외부 원본 보호)");
        assertThat(decisionPackage.requiresConfirmation()).isTrue();
        assertThat(decisionPackage.confirmationReason()).contains("사용자 확인");
        assertThat(decisionPackage.riskLevel()).isEqualTo("high");
        assertThat(decisionPackage.privacy().contextMinimized()).isTrue();
        assertThat(decisionPackage.displaySections())
                .extracting(AiDecisionPackage.DisplaySection::label)
                .containsExactly(
                        "이렇게 이해했습니다",
                        "바꾸려는 항목",
                        "건드리지 않는 항목",
                        "외부 일정이라 직접 바꾸지 않는 항목",
                        "확인이 필요한 이유",
                        "적용 전 변경 요약"
                );
        assertThat(decisionPackage.trustUxSections())
                .containsKeys(
                        "이렇게 이해했습니다",
                        "바꾸려는 항목",
                        "건드리지 않는 항목",
                        "외부 일정이라 직접 바꾸지 않는 항목",
                        "확인이 필요한 이유",
                        "적용 전 변경 요약"
                );
    }

    @Test
    void clarificationPackageExposesQuestionWithoutExecutableMutation() {
        StructuredAiCommandBatch batch = new StructuredAiCommandBatch(
                "확인이 필요합니다",
                "출장 날짜와 시간대를 알려주세요.",
                List.of(new StructuredAiCommand(
                        AgentCommandActionType.EXPLAIN_ONLY.wireValue(),
                        AgentCommandTargetType.NONE.wireValue(),
                        null,
                        Map.of(
                                "requestKind", "status_declaration",
                                "resolutionType", "clarification_required",
                                "clarificationQuestion", "출장 날짜와 대략적인 시간대, 장소를 알려주세요.",
                                "requiresUserConfirmation", false
                        ),
                        "travel_range_required",
                        false
                ))
        );

        AiDecisionPackage decisionPackage = AiDecisionPackage.from(batch, "Asia/Seoul", null, null);

        assertThat(decisionPackage.trustLevel()).isEqualTo("clarification_required");
        assertThat(decisionPackage.userEffort().needsClarification()).isTrue();
        assertThat(decisionPackage.clarificationQuestion()).contains("출장 날짜");
        assertThat(decisionPackage.proposedChanges()).allMatch(change -> !change.executable());
    }

    @Test
    void serializedPackageDoesNotExposeRawPromptProviderMetadataOrSecrets() throws Exception {
        StructuredAiCommandBatch batch = new StructuredAiCommandBatch(
                "일정 조정안",
                "사용자에게 보여줄 요약만 포함합니다.",
                List.of(new StructuredAiCommand(
                        AgentCommandActionType.EXPLAIN_ONLY.wireValue(),
                        AgentCommandTargetType.NONE.wireValue(),
                        null,
                        Map.of(
                                "requestKind", "status_declaration",
                                "requiresUserConfirmation", true,
                                "rawPrompt", "secret prompt should never leak",
                                "providerMetadata", "provider internals",
                                "provider_metadata", List.of("external provider_metadata leak"),
                                "raw_response", Map.of("prompt_text", "hidden prompt text", "x-goog-api-key", "secret"),
                                "externalItems", List.of("외부 회의", "AIza-secret-in-allowed-list"),
                                "message", "Bearer secret-token should be redacted",
                                "Authorization", "Bearer secret",
                                "apiKey", "AIza-secret"
                        ),
                        "status_declaration_impact_analysis",
                        false
                ))
        );

        String serialized = objectMapper.writeValueAsString(AiDecisionPackage.from(batch, "Asia/Seoul", null, null));

        assertThat(serialized)
                .doesNotContain(
                        "rawPrompt",
                        "raw_prompt",
                        "providerMetadata",
                        "provider_metadata",
                        "raw_response",
                        "prompt_text",
                        "x-goog-api-key",
                        "reasoningTrace",
                        "apiKey",
                        "Authorization",
                        "Bearer secret",
                        "AIza-secret"
                );
    }
}
