package com.example.temperate.service.user.aiconversation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 验证普通 OpenAI Starter 只装配一个聊天模型，并按项目配置关闭其余 OpenAI 模型能力。
 */
final class OpenAiModelAutoConfigurationContractTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            RestClientAutoConfiguration.class,
                            WebClientAutoConfiguration.class,
                            SpringAiRetryAutoConfiguration.class,
                            ToolCallingAutoConfiguration.class,
                            OpenAiChatAutoConfiguration.class,
                            OpenAiEmbeddingAutoConfiguration.class,
                            OpenAiImageAutoConfiguration.class,
                            OpenAiAudioSpeechAutoConfiguration.class,
                            OpenAiAudioTranscriptionAutoConfiguration.class,
                            OpenAiModerationAutoConfiguration.class))
                    .withPropertyValues(
                            "spring.ai.model.chat=openai",
                            "spring.ai.model.embedding=none",
                            "spring.ai.model.image=none",
                            "spring.ai.model.audio.speech=none",
                            "spring.ai.model.audio.transcription=none",
                            "spring.ai.model.moderation=none",
                            "spring.ai.openai.api-key=test-api-key",
                            "spring.ai.openai.base-url=http://127.0.0.1:8317",
                            "spring.ai.openai.chat.completions-path=/v1/chat/completions",
                            "spring.ai.retry.max-attempts=1");

    @Test
    void createsOnlyTheRegularOpenAiChatModel() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OpenAiApi.class);
            assertThat(context).hasSingleBean(OpenAiChatModel.class);
            assertThat(context.getBeansOfType(ChatModel.class))
                    .containsOnlyKeys("openAiChatModel");
            OpenAiChatModel chatModel = context.getBean(OpenAiChatModel.class);
            RetryTemplate retryTemplate = retryTemplate(chatModel);
            AtomicInteger attempts = new AtomicInteger();
            assertThatThrownBy(() -> retryTemplate.execute(retryContext -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("expected test failure");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(attempts.get()).isEqualTo(1);
            assertThat(context.containsBean("openAiEmbeddingModel")).isFalse();
            assertThat(context.containsBean("openAiImageModel")).isFalse();
            assertThat(context.containsBean("openAiAudioSpeechModel")).isFalse();
            assertThat(context.containsBean("openAiAudioTranscriptionModel"))
                    .isFalse();
            assertThat(context.containsBean("openAiModerationModel")).isFalse();
        });
    }

    private static RetryTemplate retryTemplate(OpenAiChatModel chatModel) {
        try {
            Field field = OpenAiChatModel.class.getDeclaredField(
                    "retryTemplate");
            field.setAccessible(true);
            return (RetryTemplate) field.get(chatModel);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "Could not inspect OpenAiChatModel retry template",
                    exception);
        }
    }
}
