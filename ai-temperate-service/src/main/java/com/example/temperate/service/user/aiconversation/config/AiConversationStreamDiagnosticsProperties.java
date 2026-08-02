package com.example.temperate.service.user.aiconversation.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定 AI 流式时序诊断的采样、汇总窗口和集中爆发判定边界，默认关闭且不承载任何正文数据。
 */
@Validated
@ConfigurationProperties(prefix = "app.ai-conversation.stream-diagnostics")
public record AiConversationStreamDiagnosticsProperties(
        boolean enabled,
        @DecimalMin("0.0") @DecimalMax("1.0") double sampleRate,
        @NotNull Duration window,
        @Min(1) @Max(10000) int logEveryChunks,
        @NotNull Duration silenceThreshold,
        @NotNull Duration burstWindow,
        @Min(1) @Max(100000) int burstChunks) {

    public AiConversationStreamDiagnosticsProperties {
        if (!Double.isFinite(sampleRate)
                || sampleRate < 0.0d
                || sampleRate > 1.0d) {
            throw new IllegalArgumentException(
                    "AI stream diagnostic sample rate must be between zero and one.");
        }
        if (!positive(window)
                || !positive(silenceThreshold)
                || !positive(burstWindow)) {
            throw new IllegalArgumentException(
                    "AI stream diagnostic durations must be positive.");
        }
        if (logEveryChunks <= 0 || burstChunks <= 0) {
            throw new IllegalArgumentException(
                    "AI stream diagnostic chunk thresholds must be positive.");
        }
    }

    /**
     * 使用 Usage 公共 ID 的 SHA-256 稳定分桶决定是否采样，使同一请求跨边界始终使用同一决定。
     *
     * @param usagePublicId Usage 公共 ID
     * @return 是否为本次订阅创建诊断状态
     */
    public boolean shouldSample(String usagePublicId) {
        if (!enabled || sampleRate <= 0.0d
                || usagePublicId == null || usagePublicId.isBlank()) {
            return false;
        }
        if (sampleRate >= 1.0d) {
            return true;
        }
        byte[] digest = sha256(usagePublicId);
        long bucket = 0L;
        // 仅使用前七字节形成精确可表示的五十六位正整数，避免有符号 long 和浮点边界造成不稳定分桶。
        for (int index = 0; index < 7; index++) {
            bucket = (bucket << 8) | (digest[index] & 0xffL);
        }
        double normalized = bucket / (double) (1L << 56);
        return normalized < sampleRate;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
