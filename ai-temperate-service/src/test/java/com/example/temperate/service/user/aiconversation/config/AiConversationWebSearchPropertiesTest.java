package com.example.temperate.service.user.aiconversation.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 验证联网搜索客户端只能使用既有 CLIProxyAPI 基础地址下的安全相对路径。
 */
final class AiConversationWebSearchPropertiesTest {

    @Test
    void acceptsFixedRelativeResponsesPath() {
        AiConversationWebSearchProperties properties =
                new AiConversationWebSearchProperties(
                        false, "/v1/responses");

        assertThat(properties.isResponsesPathSafe()).isTrue();
    }

    @Test
    void rejectsAbsoluteTraversalAndQueryPaths() {
        assertThat(new AiConversationWebSearchProperties(
                true, "https://example.test/v1/responses")
                .isResponsesPathSafe()).isFalse();
        assertThat(new AiConversationWebSearchProperties(
                true, "/v1/../admin")
                .isResponsesPathSafe()).isFalse();
        assertThat(new AiConversationWebSearchProperties(
                true, "/v1/responses?target=other")
                .isResponsesPathSafe()).isFalse();
    }
}
