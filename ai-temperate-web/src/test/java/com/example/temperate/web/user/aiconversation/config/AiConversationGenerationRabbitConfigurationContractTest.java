package com.example.temperate.web.user.aiconversation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import java.time.Duration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 验证异步 Generation Rabbit 拓扑保持持久、Quorum、手动 ACK，并隔离专用模板配置。
 */
class AiConversationGenerationRabbitConfigurationContractTest {

    @Test
    void directMvcSseIsDefaultUnlessEnvironmentEnablesAsyncGeneration() throws IOException {
        String yaml = Files.readString(findProjectRoot().resolve(
                "ai-temperate-web/src/main/resources/application.yml"),
                StandardCharsets.UTF_8);
        String testYaml = Files.readString(findProjectRoot().resolve(
                "ai-temperate-web/src/test/resources/application-test.yml"),
                StandardCharsets.UTF_8);

        assertThat(yaml)
                .contains("enabled: ${AI_CONVERSATION_ASYNC_GENERATION_ENABLED:false}")
                .contains("rabbit-enabled: ${AI_CONVERSATION_DIRECT_RESPONSE_RABBIT_ENABLED:true}")
                .doesNotContain("enabled: ${AI_CONVERSATION_ASYNC_GENERATION_ENABLED:true}");
        assertThat(testYaml).contains("rabbit-enabled: false");
    }

    @Test
    void directResponseCancellationUsesPerInstanceQuorumControlQueues() {
        AiConversationDirectResponseRabbitConfiguration configuration =
                new AiConversationDirectResponseRabbitConfiguration();
        Queue first = configuration.aiConversationDirectResponseControlQueue(
                properties("instance-a"));
        Queue second = configuration.aiConversationDirectResponseControlQueue(
                properties("instance-b"));

        assertThat(first.isDurable()).isTrue();
        assertThat(first.getArguments())
                .containsEntry("x-queue-type", "quorum")
                .containsKey("x-dead-letter-exchange");
        assertThat(first.getName()).isNotEqualTo(second.getName());
    }

    @Test
    void topologyUsesReliableQueuesAndDoesNotMutateSharedRabbitTemplate() throws IOException {
        String source = Files.readString(findProjectRoot().resolve(
                "ai-temperate-web/src/main/java/com/example/temperate/web/user/"
                        + "aiconversation/config/AiConversationGenerationRabbitConfiguration.java"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .contains(".quorum()")
                .contains("AcknowledgeMode.MANUAL")
                .contains("setPrefetchCount(1)")
                .contains("@EnableConfigurationProperties(AiConversationAsyncGenerationProperties.class)")
                .contains("aiConversationGenerationRabbitTemplate")
                .contains("setMandatoryExpressionString")
                .doesNotContain("RabbitTemplateCustomizer");
    }

    @Test
    void allBusinessQueuesAreDurableQuorumAndDeadLettered() {
        AiConversationGenerationRabbitConfiguration configuration =
                new AiConversationGenerationRabbitConfiguration();
        Queue[] queues = {
                configuration.aiConversationGenerationQueue(),
                configuration.aiConversationGenerationControlQueue(properties()),
                configuration.aiConversationGenerationDetachQueue(),
                configuration.aiConversationGenerationTerminalQueue()
        };

        for (Queue queue : queues) {
            assertThat(queue.isDurable()).isTrue();
            assertThat(queue.getArguments())
                    .containsEntry("x-queue-type", "quorum")
                    .containsKey("x-dead-letter-exchange");
        }
    }

    @Test
    void dedicatedTemplateUsesMandatoryOnlyForImmediateMessages() {
        AiConversationGenerationRabbitConfiguration configuration =
                new AiConversationGenerationRabbitConfiguration();
        RabbitTemplate template = configuration.aiConversationGenerationRabbitTemplate(
                mock(ConnectionFactory.class),
                configuration.aiConversationGenerationMessageConverter());
        Message immediate = new Message(new byte[0], new MessageProperties());
        MessageProperties delayedProperties = new MessageProperties();
        delayedProperties.setHeader("x-delay", 30_000L);

        assertThat(template.isMandatoryFor(immediate)).isTrue();
        assertThat(template.isMandatoryFor(
                new Message(new byte[0], delayedProperties))).isFalse();
    }

    @Test
    void ownerInstancesReceiveDifferentControlQueues() {
        AiConversationGenerationRabbitConfiguration configuration =
                new AiConversationGenerationRabbitConfiguration();

        assertThat(configuration.aiConversationGenerationControlQueue(
                        properties("instance-a")).getName())
                .isNotEqualTo(configuration.aiConversationGenerationControlQueue(
                        properties("instance-b")).getName());
    }

    private static AiConversationAsyncGenerationProperties properties() {
        return properties("instance-a");
    }

    private static AiConversationAsyncGenerationProperties properties(String instanceId) {
        return new AiConversationAsyncGenerationProperties(
                true,
                instanceId,
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofHours(24),
                4,
                Duration.ofMinutes(15));
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("ai-temperate-web"))
                    && Files.isDirectory(current.resolve("sql"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
