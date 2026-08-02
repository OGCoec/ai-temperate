package com.example.temperate.service.user.aiconversation.config;

import jakarta.validation.constraints.AssertTrue;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定 AI 会话发送动作幂等摘要使用的独立 HMAC-SHA256 密钥，并保证密钥不会通过字符串表示泄漏。
 */
@Validated
@ConfigurationProperties(prefix = "app.ai-conversation.security")
public record AiConversationSecurityProperties(
        String idempotencyHmacKeyBase64) {

    @AssertTrue(message = "AI conversation idempotency HMAC key must be canonical Base64 with at least 32 bytes")
    public boolean isHmacKeyValid() {
        if (idempotencyHmacKeyBase64 == null
                || idempotencyHmacKeyBase64.isBlank()) {
            return false;
        }
        try {
            byte[] decoded =
                    Base64.getDecoder().decode(idempotencyHmacKeyBase64);
            return decoded.length >= 32
                    && Base64.getEncoder().encodeToString(decoded)
                    .equals(idempotencyHmacKeyBase64);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "AiConversationSecurityProperties[idempotencyHmacKeyBase64=redacted]";
    }
}
