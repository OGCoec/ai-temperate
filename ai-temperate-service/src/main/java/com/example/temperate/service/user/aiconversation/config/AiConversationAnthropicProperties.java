package com.example.temperate.service.user.aiconversation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定 Anthropic Messages 的受控相对路径和协议版本，避免供应商配置覆盖共享上游主机或注入额外请求组件。
 */
@Validated
@ConfigurationProperties(prefix = "app.ai-conversation.anthropic")
public record AiConversationAnthropicProperties(
        @NotBlank String messagesPath,
        @NotBlank String apiVersion) {

    @AssertTrue(message = "Anthropic Messages path must be relative and safe")
    public boolean isMessagesPathSafe() {
        return safeRelativePath(messagesPath);
    }

    @AssertTrue(message = "Anthropic API version must use an ISO date")
    public boolean isApiVersionSafe() {
        return apiVersion != null && apiVersion.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    private static boolean safeRelativePath(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return !uri.isAbsolute()
                    && value.startsWith("/")
                    && !value.startsWith("//")
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && !value.contains("..")
                    && !value.contains("\\");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
