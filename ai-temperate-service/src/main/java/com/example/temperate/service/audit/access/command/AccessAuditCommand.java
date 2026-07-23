package com.example.temperate.service.audit.access.command;

import java.time.Instant;
import java.util.UUID;

/**
 * 表示 Web 过滤器交给审计服务的请求完成事实，原始 IP 只能在该同步调用边界内短暂存在。
 */
public record AccessAuditCommand(
        Instant occurredAt,
        UUID traceId,
        Long userId,
        String method,
        String routeTemplate,
        int statusCode,
        long durationMillis,
        String clientPlatform,
        String canonicalClientIp) {
}
