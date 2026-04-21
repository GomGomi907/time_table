package com.timetable.operator.agent.domain;

import com.timetable.operator.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "chat_command_logs")
public class ChatCommandLog extends AuditableEntity {

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, columnDefinition = "text")
    private String rawMessage;

    @Column(columnDefinition = "text")
    private String normalizedMessage;

    @Column(nullable = false)
    private String parsedIntent;

    @Column(columnDefinition = "text")
    private String parsedPayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChatExecutionType executionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChatResultStatus resultStatus;

    @Column(columnDefinition = "text")
    private String explanation;

    @Column(columnDefinition = "text")
    private String resultPayload;
}
