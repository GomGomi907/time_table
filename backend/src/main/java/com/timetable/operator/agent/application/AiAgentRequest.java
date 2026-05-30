package com.timetable.operator.agent.application;

import com.timetable.operator.auth.domain.AppUser;

public record AiAgentRequest(
        AppUser user,
        String reason,
        AiRescheduleClient.RescheduleAiContext context
) {
}
