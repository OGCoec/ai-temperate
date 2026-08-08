package com.example.temperate.service.user.aiconversation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定上下文压缩等待、按需 SSE 和 Redis 任务状态的有限生命周期。
 */
@Validated
@ConfigurationProperties(prefix = "app.ai-conversation.context-usage")
public record AiConversationContextUsageProperties(
        @NotNull Duration hardLimitWaitTimeout,
        @NotNull Duration eventHeartbeat,
        @NotNull Duration eventTimeout,
        @NotNull Duration operationTtl,
        @NotNull Duration terminalRetention) {

    @AssertTrue(message = "AI context usage durations are invalid")
    public boolean areDurationsValid() {
        return positive(hardLimitWaitTimeout)
                && positive(eventHeartbeat)
                && positive(eventTimeout)
                && positive(operationTtl)
                && positive(terminalRetention)
                && eventTimeout.compareTo(hardLimitWaitTimeout) > 0
                && operationTtl.compareTo(hardLimitWaitTimeout) > 0;
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
