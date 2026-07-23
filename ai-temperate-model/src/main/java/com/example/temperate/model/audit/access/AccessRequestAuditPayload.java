package com.example.temperate.model.audit.access;

/**
 * 表示访问请求完成消息的业务载荷，以受控路由模板和脱敏 IP 信息跨越 RabbitMQ 边界。
 */
public record AccessRequestAuditPayload(
        Long userId,
        String method,
        String routeTemplate,
        int statusCode,
        long durationMillis,
        String clientPlatform,
        int ipFamily,
        String ipPrefix,
        String ipHmac) {
}
