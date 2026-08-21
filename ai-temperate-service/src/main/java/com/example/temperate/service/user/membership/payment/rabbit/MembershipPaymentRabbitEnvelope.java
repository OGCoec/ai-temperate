package com.example.temperate.service.user.membership.payment.rabbit;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该信封是来为每条会员支付 RabbitMQ 消息携带消息 ID、事件类型、Schema、发生时间、Trace 和有界载荷。
 */
public record MembershipPaymentRabbitEnvelope<T>(
        String messageId,
        String eventType,
        int schemaVersion,
        OffsetDateTime occurredAt,
        String traceId,
        T payload) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final HybridBase64UrlCodec ID_CODEC = new HybridBase64UrlCodec();

    public MembershipPaymentRabbitEnvelope {
        if (messageId == null
                || !ID_CODEC.encode(ID_CODEC.decode(messageId)).equals(messageId)) {
            throw new IllegalArgumentException("Membership payment message ID is invalid.");
        }
        if (eventType == null || eventType.isBlank() || eventType.length() > 64) {
            throw new IllegalArgumentException("Membership payment event type is invalid.");
        }
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Membership payment message schema is unsupported.");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        // Trace 会进入异步诊断日志，只允许有限 ASCII 标识字符，避免伪造日志行或制造无界标签。
        if (traceId == null || !traceId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Membership payment trace ID is invalid.");
        }
        payload = Objects.requireNonNull(payload, "payload must not be null");
    }
}
