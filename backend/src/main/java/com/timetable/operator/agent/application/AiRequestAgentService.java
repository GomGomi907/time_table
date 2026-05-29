package com.timetable.operator.agent.application;

import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.config.AppProperties;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiRequestAgentService {

    private final AppProperties appProperties;
    private final AiRescheduleClient aiRescheduleClient;
    private final AiCommandValidationService aiCommandValidationService;

    public StructuredAiCommandBatch resolveManualRequest(
            AppUser user,
            String reason,
            AiRescheduleClient.RescheduleAiContext context
    ) {
        if (!aiEnabled()) {
            return aiCommandValidationService.aiDisabledBatch(reason);
        }

        try {
            StructuredAiCommandBatch batch = aiRescheduleClient.suggest(context);
            return aiCommandValidationService.requireExecutableOrClarification(user.getId(), batch);
        } catch (RuntimeException exception) {
            log.warn(
                    "AI request agent provider unavailable; recording provider-unavailable suggestion for user {}.",
                    user.getId(),
                    exception
            );
            return aiCommandValidationService.providerUnavailableBatch(reason);
        }
    }

    public StructuredAiCommandBatch resolvePrebuiltCommandBatch(UUID userId, StructuredAiCommandBatch batch) {
        return aiCommandValidationService.requireExecutableOrClarification(userId, batch);
    }

    private boolean aiEnabled() {
        return appProperties.ai() != null && appProperties.ai().enabled();
    }
}
