package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingConsumer;
import com.example.temperate.service.user.aiconversation.generation.cancellation.AiConversationGenerationCancellationService;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationCancelRequested;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationEnvelope;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationRabbitNames;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationRequested;
import com.example.temperate.service.user.aiconversation.generation.rabbit.impl.AiConversationGenerationRabbitListeners;
import com.example.temperate.service.user.aiconversation.generation.recovery.AiConversationGenerationRecoveryService;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationActiveRegistry;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationControlService;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationWorkItem;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationWorker;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 使用真实 RabbitMQ 验证 Generation 消息确认、无法路由返回、手动拒绝进入 DLQ 和未确认重投。
 *
 * <p>该测试只连接 Testcontainers Broker，不声明 DLQ 转移具备 At Least Once 保证。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "AIT_TEST_RABBIT_DELAYED_IMAGE", matches = ".+")
final class RabbitAiConversationGenerationReliabilityIntegrationTest {

    private static final String USER = "generation-reliability-test";
    private static final String PASSWORD = "generation-reliability-password";

    @Container
    private static final GenericContainer<?> RABBIT = new GenericContainer<>(
            DockerImageName.parse(System.getenv().getOrDefault(
                    "AIT_TEST_RABBIT_DELAYED_IMAGE", "rabbitmq:4.1-management")))
            .withEnv("RABBITMQ_DEFAULT_USER", USER)
            .withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete;.*\\n", 1))
            .withStartupTimeout(Duration.ofMinutes(1));

    private String exchange;
    private String queue;
    private String deadLetterExchange;
    private String deadLetterQueue;

    @BeforeEach
    void names() {
        String suffix = UUID.randomUUID().toString();
        exchange = "ait.test.generation." + suffix;
        queue = exchange + ".queue";
        deadLetterExchange = exchange + ".dlx";
        deadLetterQueue = exchange + ".dlq";
    }

    @Test
    void persistentPublishIsConfirmedAndUnroutablePublishIsReturned() throws Exception {
        try (Connection connection = connection(); Channel channel = connection.createChannel()) {
            declareTopology(channel);
            channel.confirmSelect();
            CountDownLatch returned = new CountDownLatch(1);
            channel.addReturnListener(returnedMessage -> returned.countDown());

            channel.basicPublish(
                    exchange,
                    "generation.requested",
                    true,
                    persistentProperties(),
                    "confirmed".getBytes(StandardCharsets.UTF_8));
            channel.waitForConfirmsOrDie(5_000L);

            GetResponse confirmed = waitForMessage(channel, queue);
            assertThat(confirmed).isNotNull();
            assertThat(confirmed.getProps().getDeliveryMode()).isEqualTo(2);
            channel.basicAck(confirmed.getEnvelope().getDeliveryTag(), false);

            channel.basicPublish(
                    exchange,
                    "missing.route",
                    true,
                    persistentProperties(),
                    "returned".getBytes(StandardCharsets.UTF_8));
            channel.waitForConfirmsOrDie(5_000L);
            assertThat(returned.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void workerFailureIsRejectedWithoutRequeueAndRoutedToDeadLetterQueue()
            throws Exception {
        AiConversationGenerationWorker worker = mock(AiConversationGenerationWorker.class);
        doThrow(new IllegalStateException("controlled-worker-failure"))
                .when(worker)
                .execute("AAAAAAAAAAAAAAAAAAAAAA", "trace-test");
        AiConversationGenerationRabbitListeners listeners = listeners(worker);

        try (Connection connection = connection(); Channel channel = connection.createChannel()) {
            declareTopology(channel);
            channel.basicPublish(
                    exchange,
                    "generation.requested",
                    persistentProperties(),
                    "failure".getBytes(StandardCharsets.UTF_8));
            GetResponse delivery = waitForMessage(channel, queue);
            assertThat(delivery).isNotNull();

            listeners.generation(
                    envelope(), springMessage(delivery), channel);

            GetResponse deadLetter = waitForMessage(channel, deadLetterQueue);
            assertThat(deadLetter).isNotNull();
            assertThat(deadLetter.getProps().getHeaders()).containsKey("x-death");
            assertThat(channel.queueDeclarePassive(queue).getMessageCount()).isZero();
            channel.basicAck(deadLetter.getEnvelope().getDeliveryTag(), false);
        }
    }

    @Test
    void closingChannelBeforeAckRedeliversPersistentMessage() throws Exception {
        try (Connection firstConnection = connection();
                Channel firstChannel = firstConnection.createChannel()) {
            declareTopology(firstChannel);
            firstChannel.basicPublish(
                    exchange,
                    "generation.requested",
                    persistentProperties(),
                    "redeliver".getBytes(StandardCharsets.UTF_8));
            GetResponse firstDelivery = waitForMessage(firstChannel, queue);
            assertThat(firstDelivery).isNotNull();
            assertThat(firstDelivery.getEnvelope().isRedeliver()).isFalse();
        }

        try (Connection secondConnection = connection();
                Channel secondChannel = secondConnection.createChannel()) {
            GetResponse redelivery = waitForMessage(secondChannel, queue);
            assertThat(redelivery).isNotNull();
            assertThat(redelivery.getEnvelope().isRedeliver()).isTrue();
            assertThat(redelivery.getProps().getDeliveryMode()).isEqualTo(2);
            secondChannel.basicAck(redelivery.getEnvelope().getDeliveryTag(), false);
        }
    }

    @Test
    void declaredBusinessAndDeadLetterQueuesAreDurableQuorum() throws Exception {
        try (Connection connection = connection(); Channel channel = connection.createChannel()) {
            declareTopology(channel);
            var result = RABBIT.execInContainer(
                    "rabbitmqctl", "list_queues", "name", "durable", "type");

            assertThat(result.getExitCode()).isZero();
            assertThat(result.getStdout())
                    .contains(queue + "\ttrue\tquorum")
                    .contains(deadLetterQueue + "\ttrue\tquorum");
        }
    }

    @Test
    void ownerControlMessageRoutesOnlyToInstanceAAndCancelsOnlyItsRegistry()
            throws Exception {
        String controlExchange = exchange + ".control";
        String queueA = AiConversationGenerationRabbitNames.controlQueue("instance-a")
                + "." + UUID.randomUUID();
        String queueB = AiConversationGenerationRabbitNames.controlQueue("instance-b")
                + "." + UUID.randomUUID();
        AiConversationGenerationActiveRegistry registryA =
                mock(AiConversationGenerationActiveRegistry.class);
        AiConversationGenerationActiveRegistry registryB =
                mock(AiConversationGenerationActiveRegistry.class);
        AiConversationGenerationControlService controlA =
                mock(AiConversationGenerationControlService.class);
        AiConversationGeneration generation = new AiConversationGeneration();
        generation.setGenerationStatus(AiConversationGenerationStatus.CANCEL_REQUESTED.code());
        when(controlA.load(any(byte[].class)))
                .thenReturn(new AiConversationGenerationWorkItem(generation, null));
        AiConversationGenerationRabbitListeners listenerA =
                listeners(mock(AiConversationGenerationWorker.class), registryA, controlA);

        try (Connection connection = connection(); Channel channel = connection.createChannel()) {
            channel.exchangeDeclare(controlExchange, "direct", true);
            channel.queueDeclare(queueA, true, false, false, Map.of("x-queue-type", "quorum"));
            channel.queueDeclare(queueB, true, false, false, Map.of("x-queue-type", "quorum"));
            channel.queueBind(
                    queueA,
                    controlExchange,
                    AiConversationGenerationRabbitNames.controlRoutingKey("instance-a"));
            channel.queueBind(
                    queueB,
                    controlExchange,
                    AiConversationGenerationRabbitNames.controlRoutingKey("instance-b"));

            channel.basicPublish(
                    controlExchange,
                    AiConversationGenerationRabbitNames.controlRoutingKey("instance-a"),
                    true,
                    persistentProperties(),
                    "owner-a-only".getBytes(StandardCharsets.UTF_8));

            GetResponse deliveryA = waitForMessage(channel, queueA);
            assertThat(deliveryA).isNotNull();
            assertThat(channel.basicGet(queueB, false)).isNull();
            listenerA.control(controlEnvelope(), springMessage(deliveryA), channel);

            verify(registryA).cancel("AAAAAAAAAAAAAAAAAAAAAA");
            verifyNoInteractions(registryB);
        }
    }

    private void declareTopology(Channel channel) throws Exception {
        channel.exchangeDeclare(exchange, "direct", true);
        channel.exchangeDeclare(deadLetterExchange, "direct", true);
        channel.queueDeclare(
                deadLetterQueue,
                true,
                false,
                false,
                Map.of("x-queue-type", "quorum"));
        channel.queueBind(deadLetterQueue, deadLetterExchange, "dead");
        channel.queueDeclare(
                queue,
                true,
                false,
                false,
                Map.of(
                        "x-queue-type", "quorum",
                        "x-dead-letter-exchange", deadLetterExchange,
                        "x-dead-letter-routing-key", "dead"));
        channel.queueBind(queue, exchange, "generation.requested");
    }

    private static AMQP.BasicProperties persistentProperties() {
        return new AMQP.BasicProperties.Builder()
                .deliveryMode(2)
                .messageId(UUID.randomUUID().toString())
                .build();
    }

    private static GetResponse waitForMessage(Channel channel, String queueName)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        GetResponse response;
        do {
            response = channel.basicGet(queueName, false);
            if (response != null) {
                return response;
            }
            Thread.sleep(20L);
        } while (System.nanoTime() < deadline);
        return null;
    }

    private static Message springMessage(GetResponse delivery) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
        return new Message(delivery.getBody(), properties);
    }

    private static AiConversationGenerationEnvelope<AiConversationGenerationRequested>
            envelope() {
        return AiConversationGenerationEnvelope.of(
                UUID.randomUUID(),
                "AI_GENERATION_REQUESTED",
                OffsetDateTime.now(ZoneOffset.UTC),
                "trace-test",
                new AiConversationGenerationRequested(
                        "AAAAAAAAAAAAAAAAAAAAAA",
                        "BBBBBBBBBBBBBBBBBBBBBB"));
    }

    private static AiConversationGenerationEnvelope<AiConversationGenerationCancelRequested>
            controlEnvelope() {
        return AiConversationGenerationEnvelope.of(
                UUID.randomUUID(),
                "AI_GENERATION_CANCEL_REQUESTED",
                OffsetDateTime.now(ZoneOffset.UTC),
                "trace-test",
                new AiConversationGenerationCancelRequested(
                        "AAAAAAAAAAAAAAAAAAAAAA",
                        "USER_STOP",
                        1));
    }

    private static AiConversationGenerationRabbitListeners listeners(
            AiConversationGenerationWorker worker) {
        return listeners(
                worker,
                mock(AiConversationGenerationActiveRegistry.class),
                mock(AiConversationGenerationControlService.class));
    }

    private static AiConversationGenerationRabbitListeners listeners(
            AiConversationGenerationWorker worker,
            AiConversationGenerationActiveRegistry activeRegistry,
            AiConversationGenerationControlService controlService) {
        return new AiConversationGenerationRabbitListeners(
                worker,
                activeRegistry,
                controlService,
                mock(AiConversationGenerationCancellationService.class),
                mock(AiConversationGenerationBillingConsumer.class),
                mock(AiConversationGenerationRecoveryService.class),
                new HybridBase64UrlCodec(),
                mock(AiConversationMetrics.class));
    }

    private static Connection connection() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getMappedPort(5672));
        factory.setUsername(USER);
        factory.setPassword(PASSWORD);
        return factory.newConnection("ai-generation-reliability-test");
    }
}
