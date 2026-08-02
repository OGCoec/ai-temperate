package com.example.temperate.service.user.aiconversation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定后台生成 Worker、30 秒失联宽限和终态保留期；部署可通过总开关紧急回退到同步 SSE 链路。
 */
@Validated
@ConfigurationProperties(prefix = "app.ai-conversation.async-generation")
public record AiConversationAsyncGenerationProperties(
        boolean enabled,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9_-]{1,128}")
        String instanceId,
        @NotNull Duration detachGrace,
        @NotNull Duration observerHeartbeat,
        @NotNull Duration terminalRetention,
        @Min(1) @Max(32) int workerConsumers,
        @NotNull Duration maxWorkerDuration) {

    @AssertTrue(message = "AI asynchronous generation durations are invalid")
    public boolean durationsAreValid() {
        return detachGrace != null
                && !detachGrace.isNegative()
                && !detachGrace.isZero()
                && observerHeartbeat != null
                && !observerHeartbeat.isNegative()
                && !observerHeartbeat.isZero()
                && observerHeartbeat.compareTo(detachGrace) < 0
                && terminalRetention != null
                && !terminalRetention.isNegative()
                && !terminalRetention.isZero()
                && maxWorkerDuration != null
                && maxWorkerDuration.compareTo(Duration.ofMinutes(1)) >= 0;
    }
}
