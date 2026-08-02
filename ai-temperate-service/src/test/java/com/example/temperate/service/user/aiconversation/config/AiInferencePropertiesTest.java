package com.example.temperate.service.user.aiconversation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 验证推理关闭态允许空密钥，启用态只要求完整上游连接配置且不会在字符串表示中泄露密钥。
 */
final class AiInferencePropertiesTest {

    @Test
    void disabledInferenceAllowsEmptyCredentials() {
        AiInferenceProperties properties = new AiInferenceProperties(
                false,
                "http://127.0.0.1:8317/v1",
                "",
                Duration.ofMinutes(15));

        assertThat(properties.isUpstreamConfiguredWhenEnabled())
                .isTrue();
        assertThat(properties.areTimeoutsValid()).isTrue();
    }

    @Test
    void enabledInferenceRequiresOnlyConnectionCredentialsAndRedactsKey() {
        AiInferenceProperties properties = new AiInferenceProperties(
                true,
                "http://127.0.0.1:8317/v1",
                "test-secret-value",
                Duration.ofMinutes(15));

        assertThat(properties.isUpstreamConfiguredWhenEnabled())
                .isTrue();
        assertThat(properties.toString())
                .contains("apiKey=<redacted>")
                .doesNotContain("test-secret-value");
    }

    @Test
    void enabledInferenceRejectsMissingApiKeyOrInvalidBaseUrl() {
        AiInferenceProperties missingKey = new AiInferenceProperties(
                true,
                "http://127.0.0.1:8317/v1",
                "",
                Duration.ofMinutes(15));
        AiInferenceProperties invalidBaseUrl = new AiInferenceProperties(
                true,
                "not-a-url",
                "test-secret-value",
                Duration.ofMinutes(15));

        assertThat(missingKey.isUpstreamConfiguredWhenEnabled()).isFalse();
        assertThat(invalidBaseUrl.isUpstreamConfiguredWhenEnabled()).isFalse();
    }
}
