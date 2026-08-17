package com.example.temperate.web.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束 API Key 安全链只保护明确公开的 v1 路径，禁止尾斜杠或未来子路径意外继承无状态认证语义。
 */
final class ApiKeyV1PathsTest {

    @Test
    void matchesOnlyChatCompletionsAndModelsEndpoints() {
        assertThat(ApiKeyV1Paths.isApiKeyEndpoint("POST", "/v1/chat/completions")).isTrue();
        assertThat(ApiKeyV1Paths.isApiKeyEndpoint("GET", "/v1/models")).isTrue();

        assertThat(ApiKeyV1Paths.isApiKeyEndpoint("POST", "/v1/models")).isFalse();
        assertThat(ApiKeyV1Paths.isApiKeyEndpoint("GET", "/v1/chat/completions")).isFalse();
        assertThat(ApiKeyV1Paths.isApiKeyEndpoint("GET", "/v1/models/")).isFalse();
        assertThat(ApiKeyV1Paths.isApiKeyEndpoint("GET", "/v1/models/gpt-test")).isFalse();
        assertThat(ApiKeyV1Paths.isApiKeyEndpoint("GET", "/v1/responses")).isFalse();
        assertThat(ApiKeyV1Paths.isApiKeyEndpoint("POST", "/v1/responses")).isFalse();
    }
}
