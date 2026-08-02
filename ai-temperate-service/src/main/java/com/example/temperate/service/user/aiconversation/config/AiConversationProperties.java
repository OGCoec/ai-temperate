package com.example.temperate.service.user.aiconversation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定 AI 会话上下文、流片合并、并发租约和受控图片来源的安全边界。
 */
@Validated
@ConfigurationProperties(prefix = "app.ai-conversation")
public record AiConversationProperties(
        @NotNull Duration contextTtl,
        @NotNull Duration inflightLeaseTtl,
        @NotNull Duration compactionLeaseTtl,
        @NotNull Duration streamFlushInterval,
        @Min(1024) @Max(8192) int streamFlushBytes,
        @Min(100) @Max(1000) int contextPageSize,
        @Min(100) @Max(5000) int maxHashFields,
        @Min(100) @Max(4999) int compactionHashFieldThreshold,
        @Min(1) @Max(64) int maxConcurrentPerUser,
        @Min(1) @Max(1024) int maxConcurrentGlobal,
        @Min(1) @Max(100000) int imageEstimatedTokens,
        @Min(50) @Max(95) int preCompactionPercent,
        @NotNull Duration reservationSafetyBuffer,
        @NotNull Duration reconciliationScanInterval,
        @Min(1) @Max(500) int reconciliationBatchSize,
        boolean historicalSystemFailureAutoRefundEnabled,
        String systemPrompt) {

    @AssertTrue(message = "AI conversation context TTL must be exactly 72 hours")
    public boolean isContextTtlFixed() {
        return Duration.ofHours(72).equals(contextTtl);
    }

    @AssertTrue(message = "AI conversation lease and flush durations are invalid")
    public boolean areDurationsValid() {
        return inflightLeaseTtl != null
                && !inflightLeaseTtl.isNegative()
                && !inflightLeaseTtl.isZero()
                && compactionLeaseTtl != null
                && !compactionLeaseTtl.isNegative()
                && !compactionLeaseTtl.isZero()
                && streamFlushInterval != null
                && !streamFlushInterval.isNegative()
                && !streamFlushInterval.isZero()
                && streamFlushInterval.compareTo(Duration.ofSeconds(1)) <= 0
                && reservationSafetyBuffer != null
                && !reservationSafetyBuffer.isNegative()
                && reconciliationScanInterval != null
                && !reconciliationScanInterval.isNegative()
                && !reconciliationScanInterval.isZero();
    }

    public AiConversationProperties {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException(
                    "AI conversation system prompt must not be blank.");
        }
        if (compactionHashFieldThreshold >= maxHashFields) {
            throw new IllegalArgumentException(
                    "AI conversation compaction threshold must be below the hard field limit.");
        }
    }
}
