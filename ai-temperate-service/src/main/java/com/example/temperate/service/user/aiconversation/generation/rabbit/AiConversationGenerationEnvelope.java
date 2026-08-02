package com.example.temperate.service.user.aiconversation.generation.rabbit;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 定义所有 AI Generation RabbitMQ 消息共享的可靠信封和安全追踪字段。
 */
public record AiConversationGenerationEnvelope<T>(
        UUID messageId,
        String eventType,
        int schemaVersion,
        OffsetDateTime occurredAt,
        String traceId,
        T payload) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AiConversationGenerationEnvelope {
        Objects.requireNonNull(messageId);
        requireSafeToken(eventType, "eventType");
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported generation message schema version.");
        }
        Objects.requireNonNull(occurredAt);
        requireSafeToken(traceId, "traceId");
        Objects.requireNonNull(payload);
    }

    public static <T> AiConversationGenerationEnvelope<T> of(
            UUID messageId,
            String eventType,
            OffsetDateTime occurredAt,
            String traceId,
            T payload) {
        return new AiConversationGenerationEnvelope<>(
                messageId,
                eventType,
                CURRENT_SCHEMA_VERSION,
                occurredAt,
                traceId,
                payload);
    }

    private static void requireSafeToken(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " is invalid.");
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!Character.isLetterOrDigit(current)
                    && current != '_'
                    && current != '-') {
                throw new IllegalArgumentException(name + " contains unsafe characters.");
            }
        }
    }
}
