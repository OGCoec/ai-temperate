package com.example.temperate.service.user.aiconversation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定 Google Interactions 的受控相对路径和 API 修订版本，保证原生协议只能发送到既有 CLIProxyAPI 边界。
 */
@Validated
@ConfigurationProperties(prefix = "app.ai-conversation.google")
public record AiConversationGeminiProperties(
        @NotBlank String interactionsPath,
        @NotBlank String apiRevision) {

    @AssertTrue(message = "Google Interactions path must be relative and safe")
    public boolean isInteractionsPathSafe() {
        return safeRelativePath(interactionsPath);
    }

    @AssertTrue(message = "Google API revision must use an ISO date")
    public boolean isApiRevisionSafe() {
        return apiRevision != null && apiRevision.matches("\\d{4}-\\d{2}-\\d{2}");
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
