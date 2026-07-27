package com.example.temperate.service.admin.aimodel.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定启用 AI 模型加密快照的独立密钥、随机 TTL 边界和聚合数量上限。
 *
 * <p>密钥只允许从外部配置注入且必须恰好为 32 字节；配置对象不会在日志或异常中回显密钥内容。</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.ai-model-cache")
public record AiModelCacheProperties(
        String encryptionKeyBase64,
        @NotNull Duration minimumTtl,
        @NotNull Duration maximumTtl,
        @Min(1) @Max(1000) int maxModels) {

    private static final Duration LOWEST_TTL = Duration.ofMinutes(5);
    private static final Duration HIGHEST_TTL = Duration.ofMinutes(15);

    @AssertTrue(message = "AI model cache encryption key must be canonical Base64 containing exactly 32 bytes")
    public boolean isEncryptionKeyValid() {
        if (encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()) {
            return false;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptionKeyBase64);
            return decoded.length == 32
                    && Base64.getEncoder().encodeToString(decoded).equals(encryptionKeyBase64);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @AssertTrue(message = "AI model cache TTL range must stay between five and fifteen minutes")
    public boolean isTtlRangeValid() {
        return minimumTtl != null
                && maximumTtl != null
                && minimumTtl.compareTo(LOWEST_TTL) >= 0
                && maximumTtl.compareTo(HIGHEST_TTL) <= 0
                && minimumTtl.compareTo(maximumTtl) <= 0;
    }

    @Override
    public String toString() {
        return "AiModelCacheProperties[encryptionKeyBase64=redacted, minimumTtl="
                + minimumTtl
                + ", maximumTtl="
                + maximumTtl
                + ", maxModels="
                + maxModels
                + "]";
    }
}
