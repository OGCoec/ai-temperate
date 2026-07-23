package com.example.temperate.model.audit.access;

import java.time.Instant;
import java.util.UUID;

/**
 * 表示写入 PostgreSQL 的单条访问请求审计记录，只包含脱敏 IP 前缀和 HMAC，不承载完整原始 IP。
 */
public record AccessRequestAuditRecord(
        UUID messageId,
        Long userId,
        UUID traceId,
        Instant occurredAt,
        String httpMethod,
        String routeTemplate,
        int statusCode,
        long durationMillis,
        String clientPlatform,
        int ipFamily,
        String ipPrefix,
        String ipHmac) {
}
