package com.example.temperate.service.user.aiconversation.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 验证 xAI 视频能力、官方整数价格和 FC 边界可以从强类型配置完整绑定。
 */
final class AiConversationVideoGenerationPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestConfiguration.class)
                    .withPropertyValues(
                            "app.ai-conversation.video-generation.enabled=true",
                            "app.ai-conversation.video-generation.poll-interval=5s",
                            "app.ai-conversation.video-generation.maximum-polling-duration=15m",
                            "app.ai-conversation.video-generation.maximum-response-json-bytes=1048576",
                            "app.ai-conversation.video-generation.endpoints.generations=/v1/videos/generations",
                            "app.ai-conversation.video-generation.endpoints.edits=/v1/videos/edits",
                            "app.ai-conversation.video-generation.endpoints.extensions=/v1/videos/extensions",
                            "app.ai-conversation.video-generation.endpoints.poll=/v1/videos/{requestId}",
                            "app.ai-conversation.video-generation.version15.model-name=grok-imagine-video-1.5",
                            "app.ai-conversation.video-generation.version15.p480-output-ticks-per-second=800000000",
                            "app.ai-conversation.video-generation.version15.p720-output-ticks-per-second=1400000000",
                            "app.ai-conversation.video-generation.version15.p1080-output-ticks-per-second=2500000000",
                            "app.ai-conversation.video-generation.version15.image-input-ticks-each=100000000",
                            "app.ai-conversation.video-generation.legacy.model-name=grok-imagine-video",
                            "app.ai-conversation.video-generation.legacy.p480-output-ticks-per-second=500000000",
                            "app.ai-conversation.video-generation.legacy.p720-output-ticks-per-second=700000000",
                            "app.ai-conversation.video-generation.legacy.image-input-ticks-each=20000000",
                            "app.ai-conversation.video-generation.legacy.video-input-ticks-per-second=100000000",
                            "app.ai-conversation.video-generation.function-compute.invocation-url=https://fc.example/invoke",
                            "app.ai-conversation.video-generation.function-compute.hmac-secret=test-only-secret-with-sufficient-length",
                            "app.ai-conversation.video-generation.function-compute.timeout=15m",
                            "app.ai-conversation.video-generation.function-compute.object-prefix=ai/video/",
                            "app.ai-conversation.video-generation.function-compute.maximum-video-bytes=2147483648",
                            "app.ai-conversation.video-generation.function-compute.allowed-source-hosts[0]=vidgen.x.ai",
                            "app.ai-conversation.video-generation.function-compute.allowed-source-hosts[1]=bucket.oss.example");

    @Test
    void bindsOfficialTicksAndFunctionComputeBoundary() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AiConversationVideoGenerationProperties properties = context.getBean(
                    AiConversationVideoGenerationProperties.class);
            assertThat(properties.version15().p1080OutputTicksPerSecond())
                    .isEqualTo(2_500_000_000L);
            assertThat(properties.legacy().videoInputTicksPerSecond())
                    .isEqualTo(100_000_000L);
            assertThat(properties.functionCompute().configured()).isTrue();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiConversationVideoGenerationProperties.class)
    static class TestConfiguration {
    }
}
