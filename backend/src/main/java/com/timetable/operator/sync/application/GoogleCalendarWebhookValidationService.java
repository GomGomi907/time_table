package com.timetable.operator.sync.application;

import com.timetable.operator.sync.domain.GoogleCalendarNotificationChannel;
import com.timetable.operator.sync.domain.GoogleNotificationChannelStatus;
import com.timetable.operator.sync.infrastructure.GoogleCalendarNotificationChannelRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GoogleCalendarWebhookValidationService {

    private static final List<GoogleNotificationChannelStatus> ACCEPTABLE_STATUSES = List.of(
            GoogleNotificationChannelStatus.ACTIVE,
            GoogleNotificationChannelStatus.REPLACED
    );

    private final GoogleCalendarNotificationChannelRepository channelRepository;

    @Transactional(readOnly = true)
    public ValidationResult validate(WebhookHeaders headers) {
        String channelId = blankToNull(headers.channelId());
        String resourceId = blankToNull(headers.resourceId());
        if (channelId == null || resourceId == null) {
            return ValidationResult.noop("missing_channel_or_resource", "Missing Google webhook channel or resource header.");
        }

        GoogleCalendarNotificationChannel channel = channelRepository
                .findByChannelIdAndResourceIdAndStatusIn(channelId, resourceId, ACCEPTABLE_STATUSES)
                .orElse(null);
        if (channel == null) {
            return ValidationResult.noop("unknown_channel", "Unknown or inactive Google Calendar notification channel.");
        }

        if (channel.getExpirationAt() != null && channel.getExpirationAt().isBefore(Instant.now())) {
            return ValidationResult.noop("expired_channel", "Expired Google Calendar notification channel.");
        }

        if (!matchesToken(channel.getChannelTokenHash(), headers.channelToken())) {
            return ValidationResult.noop("token_mismatch", "Google Calendar notification channel token did not match.");
        }

        Long messageNumber = parseMessageNumber(headers.messageNumber());
        if (messageNumber == null) {
            return ValidationResult.noop("missing_or_invalid_message_number", "Missing or invalid Google webhook message number.");
        }

        Long lastMessageNumber = channel.getLastMessageNumber();
        if (lastMessageNumber != null && messageNumber <= lastMessageNumber) {
            return ValidationResult.noop("duplicate_message", "Duplicate or out-of-order Google webhook message number.");
        }

        String resourceState = blankToNull(headers.resourceState());
        if (resourceState == null || !List.of("sync", "exists", "not_exists").contains(resourceState.toLowerCase(Locale.ROOT))) {
            return ValidationResult.noop("invalid_resource_state", "Invalid Google webhook resource state.");
        }

        return ValidationResult.accepted(
                channel.getId(),
                channel.getUserId(),
                channel.getCalendarId(),
                channel.getChannelId(),
                channel.getResourceId(),
                resourceState,
                messageNumber,
                blankToNull(headers.resourceUri())
        );
    }

    @Transactional
    public void recordAccepted(UUID channelRowId, Long messageNumber, String resourceUri) {
        GoogleCalendarNotificationChannel channel = channelRepository.findById(channelRowId)
                .orElseThrow(() -> new IllegalStateException("Google Calendar notification channel disappeared."));
        channel.setLastMessageNumber(messageNumber);
        channel.setLastNotificationAt(Instant.now());
        if (blankToNull(resourceUri) != null) {
            channel.setResourceUri(resourceUri.trim());
        }
        channelRepository.save(channel);
    }

    public static String hashToken(String token) {
        if (token == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for webhook token hashing.", exception);
        }
    }

    private boolean matchesToken(String expectedHash, String incomingToken) {
        String normalizedExpected = blankToNull(expectedHash);
        if (normalizedExpected == null) {
            return false;
        }
        byte[] expected = normalizedExpected.getBytes(StandardCharsets.UTF_8);
        byte[] actual = hashToken(incomingToken).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private Long parseMessageNumber(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            long messageNumber = Long.parseLong(normalized);
            return messageNumber > 0 ? messageNumber : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record WebhookHeaders(
            String channelId,
            String resourceId,
            String channelToken,
            String resourceState,
            String messageNumber,
            String channelExpiration,
            String resourceUri
    ) {
    }

    public record ValidationResult(
            boolean shouldSync,
            String code,
            String detail,
            UUID channelRowId,
            UUID userId,
            String calendarId,
            String channelId,
            String resourceId,
            String resourceState,
            Long messageNumber,
            String resourceUri
    ) {
        static ValidationResult noop(String code, String detail) {
            return new ValidationResult(false, code, detail, null, null, null, null, null, null, null, null);
        }

        static ValidationResult accepted(
                UUID channelRowId,
                UUID userId,
                String calendarId,
                String channelId,
                String resourceId,
                String resourceState,
                Long messageNumber,
                String resourceUri
        ) {
            return new ValidationResult(
                    true,
                    "accepted",
                    "Google Calendar webhook accepted.",
                    channelRowId,
                    userId,
                    calendarId == null || calendarId.isBlank() ? "primary" : calendarId.trim(),
                    channelId,
                    resourceId,
                    resourceState,
                    messageNumber,
                    resourceUri
            );
        }
    }
}
