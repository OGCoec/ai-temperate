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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 在隔离 RabbitMQ 上按真实三十秒宽限期验证失联检查不会提前投递，并能在三十一秒窗口内到达。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "AIT_TEST_RABBIT_DELAYED_IMAGE", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AIT_TEST_REAL_DETACH_GRACE", matches = "true")
final class RabbitAiConversationGenerationThirtySecondDetachIntegrationTest {

    private static final String USER = "generation-thirty-second-test";
    private static final String PASSWORD = "generation-thirty-second-password";

    @Container
    private static final GenericContainer<?> RABBIT = new GenericContainer<>(
            DockerImageName.parse(System.getenv("AIT_TEST_RABBIT_DELAYED_IMAGE")))
            .withEnv("RABBITMQ_DEFAULT_USER", USER)
            .withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete;.*\\n", 1))
            .withStartupTimeout(Duration.ofMinutes(1));

    @Test
    void detachCheckIsNotVisibleAtTwentyNineSecondsAndArrivesByThirtyOneSeconds() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(
                RABBIT.getHost(), RABBIT.getMappedPort(5672));
        connectionFactory.setUsername(USER);
        connectionFactory.setPassword(PASSWORD);
        connectionFactory.setPublisherConfirmType(
                CachingConnectionFactory.ConfirmType.CORRELATED);
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter(
                JsonMapper.builder().findAndAddModules().build()));
        template.setMandatoryExpressionString(
                "messageProperties.headers['x-delay'] == null");
        declareTopology(new RabbitAdmin(connectionFactory));

        try {
            RabbitAiConversationGenerationEventPublisherImpl publisher =
                    new RabbitAiConversationGenerationEventPublisherImpl(
                            template,
                            properties(),
                            mock(AiConversationMetrics.class),
                            Clock.systemUTC());
            long started = System.nanoTime();
            publisher.publishDetachCheck(
                    "AAAAAAAAAAAAAAAAAAAAAA",
                    11L,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    "trace-test");

            Message early = template.receive(
                    AiConversationGenerationRabbitNames.DETACH_QUEUE,
                    29_000L);
            assertThat(early).isNull();
            Message delivered = template.receive(
                    AiConversationGenerationRabbitNames.DETACH_QUEUE,
                    3_000L);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started);

            assertThat(delivered).isNotNull();
            assertThat(elapsedMillis).isBetween(29_500L, 31_500L);
        } finally {
            connectionFactory.destroy();
        }
    }

    private static void declareTopology(RabbitAdmin admin) {
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
                "instance-release-test",
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofHours(24),
                1,
                Duration.ofMinutes(15));
    }
}
