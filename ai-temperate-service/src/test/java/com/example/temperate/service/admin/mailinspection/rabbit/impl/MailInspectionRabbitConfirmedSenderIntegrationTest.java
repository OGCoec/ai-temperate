package com.example.temperate.service.admin.mailinspection.rabbit.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * 使用隔离 RabbitMQ 验证真实 Publisher Confirm 完成后可以继续发布 Marker，且不会连接项目当前 Broker。
 */
@Testcontainers(disabledWithoutDocker = true)
final class MailInspectionRabbitConfirmedSenderIntegrationTest {

    private static final String WORK_EXCHANGE =
            "test.mail-inspection.work";
    private static final String MARKER_EXCHANGE =
            "test.mail-inspection.marker";
    private static final String WORK_QUEUE =
            "test.mail-inspection.work";
    private static final String MARKER_QUEUE =
            "test.mail-inspection.marker";
    private static final String WORK_KEY = "test.work";
    private static final String MARKER_KEY = "test.marker";

    @Container
    private static final GenericContainer<?> RABBIT =
            new GenericContainer<>(
                    DockerImageName.parse("rabbitmq:4.1-management"))
                    .withEnv("RABBITMQ_DEFAULT_USER", "mailtest")
                    .withEnv("RABBITMQ_DEFAULT_PASS", "mailtest-password")
                    .withExposedPorts(5672);

    @Test
    void publishesWorkAndMarkerAcrossRealConfirmCallbacks() {
        CachingConnectionFactory connectionFactory =
                new CachingConnectionFactory(
                        RABBIT.getHost(),
                        RABBIT.getMappedPort(5672));
        connectionFactory.setUsername("mailtest");
        connectionFactory.setPassword("mailtest-password");
        connectionFactory.setPublisherConfirmType(
                CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        ObjectMapper objectMapper = new ObjectMapper();
        RabbitTemplate rabbitTemplate =
                new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setMessageConverter(
                new Jackson2JsonMessageConverter(objectMapper));
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        declareTopology(rabbitAdmin);
        Scheduler publishScheduler = Schedulers.newBoundedElastic(
                2,
                256,
                "admin-mail-rabbit-publish-integration");

        try {
            MailInspectionRabbitConfirmedSender sender =
                    new MailInspectionRabbitConfirmedSender(
                            rabbitTemplate,
                            objectMapper,
                            AdminMailInspectionProperties.defaults(),
                            publishScheduler);

            StepVerifier.create(sender.send(
                                    WORK_EXCHANGE,
                                    WORK_KEY,
                                    "work-message",
                                    "work-event",
                                    Map.of("kind", "work"))
                            .then(sender.send(
                                    MARKER_EXCHANGE,
                                    MARKER_KEY,
                                    "marker-message",
                                    "marker-event",
                                    Map.of("kind", "marker"))))
                    .expectComplete()
                    .verify(Duration.ofSeconds(10));

            assertThat(rabbitTemplate.receive(WORK_QUEUE, 2_000))
                    .isNotNull();
            assertThat(rabbitTemplate.receive(MARKER_QUEUE, 2_000))
                    .isNotNull();
        } finally {
            publishScheduler.dispose();
            connectionFactory.destroy();
        }
    }

    private static void declareTopology(RabbitAdmin rabbitAdmin) {
        DirectExchange workExchange =
                new DirectExchange(WORK_EXCHANGE, true, false);
        DirectExchange markerExchange =
                new DirectExchange(MARKER_EXCHANGE, true, false);
        Queue workQueue = new Queue(WORK_QUEUE, true, false, false);
        Queue markerQueue = new Queue(MARKER_QUEUE, true, false, false);
        rabbitAdmin.declareExchange(workExchange);
        rabbitAdmin.declareExchange(markerExchange);
        rabbitAdmin.declareQueue(workQueue);
        rabbitAdmin.declareQueue(markerQueue);
        rabbitAdmin.declareBinding(BindingBuilder
                .bind(workQueue)
                .to(workExchange)
                .with(WORK_KEY));
        rabbitAdmin.declareBinding(BindingBuilder
                .bind(markerQueue)
                .to(markerExchange)
                .with(MARKER_KEY));
    }
}
