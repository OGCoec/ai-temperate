package com.example.temperate.service.user.aiconversation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定 Responses 联网搜索总开关与相对路径，防止配置把专用客户端导向任意外部地址。
 */
@Validated
@ConfigurationProperties(prefix = "app.ai-conversation.web-search")
public record AiConversationWebSearchProperties(
        boolean enabled,
        @NotBlank String responsesPath) {

    @AssertTrue(message = "AI web search Responses path must be relative and safe")
    public boolean isResponsesPathSafe() {
        if (responsesPath == null || responsesPath.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(responsesPath);
            return !uri.isAbsolute()
                    && responsesPath.startsWith("/")
                    && uri.getHost() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && !responsesPath.contains("..")
                    && !responsesPath.contains("\\");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
