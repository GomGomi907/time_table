package com.timetable.operator.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiDecisionPackageTest {

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
                                "requiresUserConfirmation", true
                        ),
                        "destructive_candidate_confirmation",
                        false
                ))
        );

        AiDecisionPackage decisionPackage = AiDecisionPackage.from("destructive_bulk", batch);

        assertThat(decisionPackage.requestKind()).isEqualTo("destructive_bulk");
        assertThat(decisionPackage.trustLevel()).isEqualTo("review_required");
        assertThat(decisionPackage.proposal().externalMutationAllowed()).isFalse();
        assertThat(decisionPackage.analysis().externalItems()).contains("외부 회의 (외부 원본 보호)");
        assertThat(decisionPackage.privacy().contextMinimized()).isTrue();
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
}
