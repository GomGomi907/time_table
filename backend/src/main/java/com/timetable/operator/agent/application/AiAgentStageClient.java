package com.timetable.operator.agent.application;

import com.timetable.operator.agent.domain.StructuredAiCommandBatch;

public interface AiAgentStageClient {

    AiAgentInterpretation interpret(AiAgentRequest request);

    StructuredAiCommandBatch draft(AiAgentRequest request, AiAgentInterpretation interpretation);

    StructuredAiCommandBatch repair(
            AiAgentRequest request,
            AiAgentInterpretation interpretation,
            StructuredAiCommandBatch failedBatch,
            AiRequestProposalMatchService.MatchResult failure
    );
}
