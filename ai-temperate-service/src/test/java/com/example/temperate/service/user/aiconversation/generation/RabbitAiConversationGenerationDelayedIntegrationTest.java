package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationRabbitNames;
import com.example.temperate.service.user.aiconversation.generation.rabbit.impl.RabbitAiConversationGenerationEventPublisherImpl;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * 使用显式提供的 RabbitMQ delayed-message 镜像验证真实 Confirm、Quorum Queue、持久消息和延迟投递。
 *
 * <p>测试没有安全默认镜像，只有第二阶段提供 AIT_TEST_RABBIT_DELAYED_IMAGE 时才允许启动隔离容器。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "AIT_TEST_RABBIT_DELAYED_IMAGE", matches = ".+")
final class RabbitAiConversationGenerationDelayedIntegrationTest {

    private static final String USER = "generation-test";
    private static final String PASSWORD = "generation-test-password";

    @Container
    private static final GenericContainer<?> RABBIT = new GenericContainer<>(
            DockerImageName.parse(System.getenv().getOrDefault(
                    "AIT_TEST_RABBIT_DELAYED_IMAGE", "rabbitmq:4.1-management")))
            .withEnv("RABBITMQ_DEFAULT_USER", USER)
            .withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
            .withExposedPorts(5672)
            // AMQP 端口可能早于默认用户和插件完成初始化，必须等待 Broker 完整启动日志。
            .waitingFor(Wait.forLogMessage(".*Server startup complete;.*\\n", 1))
            .withStartupTimeout(Duration.ofMinutes(1));

    @Test
    void delayedDetachMessageIsPersistentConfirmedAndNotDeliveredEarly() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(
                RABBIT.getHost(), RABBIT.getMappedPort(5672));
        connectionFactory.setUsername(USER);
        connectionFactory.setPassword(PASSWORD);
        connectionFactory.setPublisherConfirmType(
                CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter(
                JsonMapper.builder().findAndAddModules().build()));
        template.setMandatoryExpressionString(
                "messageProperties.headers['x-delay'] == null");
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        declareDelayedTopology(admin);

        try {
            RabbitAiConversationGenerationEventPublisherImpl publisher =
                    new RabbitAiConversationGenerationEventPublisherImpl(
                            template,
                            properties(),
                            mock(AiConversationMetrics.class),
                            Clock.systemUTC());
            publisher.publishDetachCheck(
                    "AAAAAAAAAAAAAAAAAAAAAA",
                    7L,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    "trace-test");

            assertThat(template.receive(
                    AiConversationGenerationRabbitNames.DETACH_QUEUE, 50)).isNull();
            Message delivered = template.receive(
                    AiConversationGenerationRabbitNames.DETACH_QUEUE, 2_000);
            assertThat(delivered).isNotNull();
            assertThat(delivered.getMessageProperties().getReceivedDeliveryMode())
                    .isEqualTo(MessageDeliveryMode.PERSISTENT);
        } finally {
            connectionFactory.destroy();
        }
    }

    private static void declareDelayedTopology(RabbitAdmin admin) {
        CustomExchange exchange = new CustomExchange(
                AiConversationGenerationRabbitNames.DETACH_EXCHANGE,
                "x-delayed-message",
                true,
                false,
                Map.of("x-delayed-type", "direct"));
        Queue queue = QueueBuilder.durable(AiConversationGenerationRabbitNames.DETACH_QUEUE)
                .quorum()
                .build();
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue)
                .to(exchange)
                .with(AiConversationGenerationRabbitNames.DETACH_ROUTING_KEY)
                .noargs());
    }

    private static AiConversationAsyncGenerationProperties properties() {
        return new AiConversationAsyncGenerationProperties(
                true,
                "instance-test",
                Duration.ofMillis(200),
                Duration.ofMillis(50),
                Duration.ofHours(24),
                1,
                Duration.ofMinutes(15));
    }
}
