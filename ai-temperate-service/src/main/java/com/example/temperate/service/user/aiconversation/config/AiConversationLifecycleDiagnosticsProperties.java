package com.example.temperate.service.user.aiconversation.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定 AI 会话全生命周期诊断的开关与稳定采样比例，默认关闭且不承载任何业务状态。
 */
@Validated
@ConfigurationProperties(prefix = "app.ai-conversation.lifecycle-diagnostics")
public record AiConversationLifecycleDiagnosticsProperties(
        boolean enabled,
        @DecimalMin("0.0") @DecimalMax("1.0") double sampleRate) {

    public AiConversationLifecycleDiagnosticsProperties {
        if (!Double.isFinite(sampleRate)
                || sampleRate < 0.0d
                || sampleRate > 1.0d) {
            throw new IllegalArgumentException(
                    "AI lifecycle diagnostic sample rate must be between zero and one.");
        }
    }

    /**
     * 使用客户端请求 ID，缺失时使用调用方提供的服务端 Trace 或 Usage 公共 ID 做稳定采样，保证同一请求的所有阶段采用同一个决定。
     *
     * @param clientRequestId 前端生成的诊断关联 ID
     * @param fallbackCorrelation 服务端 Trace 或后台任务可用的 Usage 公共 ID
     * @return 本次请求是否允许输出生命周期日志
     */
    public boolean shouldSample(
            String clientRequestId,
            String fallbackCorrelation) {
        if (!enabled || sampleRate <= 0.0d) {
            return false;
        }
        if (sampleRate >= 1.0d) {
            return true;
        }
        String correlation = available(clientRequestId)
                ? clientRequestId : fallbackCorrelation;
        if (!available(correlation)) {
            return false;
        }
        byte[] digest = sha256(correlation);
        long bucket = 0L;
        // 只取七字节形成可精确表示的正整数，避免有符号 long 和浮点边界造成不稳定分桶。
        for (int index = 0; index < 7; index++) {
            bucket = (bucket << 8) | (digest[index] & 0xffL);
        }
        return bucket / (double) (1L << 56) < sampleRate;
    }

    private static boolean available(String value) {
        return value != null
                && !value.isBlank()
                && !"unavailable".equals(value);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
