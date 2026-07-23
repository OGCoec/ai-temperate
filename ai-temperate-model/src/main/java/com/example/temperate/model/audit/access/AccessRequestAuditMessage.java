package com.example.temperate.model.audit.access;

import java.time.Instant;
import java.util.UUID;

/**
 * 定义 RabbitMQ 中访问请求完成事件的稳定版本化信封，用消息 ID 支持消费者幂等写入。
 */
public record AccessRequestAuditMessage(
        UUID messageId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        UUID traceId,
        AccessRequestAuditPayload payload) {

    public static final String EVENT_TYPE = "ACCESS_REQUEST_COMPLETED";
    public static final int SCHEMA_VERSION = 1;
}
