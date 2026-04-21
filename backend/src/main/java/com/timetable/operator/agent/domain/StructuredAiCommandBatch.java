package com.timetable.operator.agent.domain;

import java.util.List;

public record StructuredAiCommandBatch(
        String summary,
        String explanation,
        List<StructuredAiCommand> commands
) {
}
