package com.example.temperate.web.admin.mailinspection.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定管理员邮件检查 SSE 的心跳、重连判定、连接超时和单会话连接上限。
 */
@Validated
@ConfigurationProperties(prefix = "app.admin.mail-inspection.sse")
public record AdminMailInspectionSseProperties(
        @NotNull Duration heartbeatInterval,
        @NotNull Duration idleReconnectThreshold,
        @NotNull Duration connectionTimeout,
        @Min(1) @Max(16) int maxConnectionsPerAdmin) {

    public AdminMailInspectionSseProperties {
        requirePositive(heartbeatInterval, "sse.heartbeatInterval");
        requirePositive(
                idleReconnectThreshold,
                "sse.idleReconnectThreshold");
        requirePositive(connectionTimeout, "sse.connectionTimeout");
        if (idleReconnectThreshold.compareTo(heartbeatInterval) <= 0) {
            throw new IllegalArgumentException(
                    "SSE idle threshold must exceed heartbeat interval");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
