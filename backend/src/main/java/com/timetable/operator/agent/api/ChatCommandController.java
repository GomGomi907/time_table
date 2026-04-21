package com.timetable.operator.agent.api;

import com.timetable.operator.agent.application.ChatCommandOrchestrationService;
import com.timetable.operator.common.api.ApiEnvelope;
import com.timetable.operator.common.api.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ChatCommandController {

    private final ChatCommandOrchestrationService chatCommandOrchestrationService;

    @PostMapping("/api/chat/command")
    public ResponseEntity<ApiEnvelope<ChatCommandOrchestrationService.ChatCommandResponse>> handleCommand(
            @Valid @RequestBody ChatCommandOrchestrationService.ChatCommandRequest request
    ) {
        return ApiResponses.ok(chatCommandOrchestrationService.handle(request));
    }
}
