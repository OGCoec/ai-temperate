package com.example.temperate.service.admin.mailinspection.rabbit.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionPublishException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * 验证邮箱检查 Rabbit 发布与 Publisher Confirm 后续处理始终离开 AMQP I/O 回调线程，并保持有限重试边界。
 */
final class MailInspectionRabbitConfirmedSenderTest {

    @Test
    void movesChainedPublishOffConfirmCallbackThread() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        List<String> publishThreads = new CopyOnWriteArrayList<>();
        ExecutorService callbackExecutor = Executors.newSingleThreadExecutor(
                runnable -> Thread.ofPlatform()
                        .name("amqp-callback-test")
                        .unstarted(runnable));
        Scheduler publishScheduler = Schedulers.newBoundedElastic(
                2,
                256,
                "admin-mail-rabbit-publish-test");
        doAnswer(invocation -> {
                    publishThreads.add(Thread.currentThread().getName());
                    CorrelationData correlation = invocation.getArgument(4);
                    callbackExecutor.execute(() -> correlation.getFuture().complete(
                            new CorrelationData.Confirm(true, null)));
                    return null;
                })
                .when(rabbitTemplate)
                .convertAndSend(
                        anyString(),
                        anyString(),
                        any(),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));

        try {
            MailInspectionRabbitConfirmedSender sender = sender(
                    rabbitTemplate,
                    properties(
                            Duration.ofSeconds(1),
                            3,
                            List.of(
                                    Duration.ofMillis(10),
                                    Duration.ofMillis(20))),
                    publishScheduler);

            Mono<Void> chained = sender.send(
                            "work.exchange",
                            "work.key",
                            "work-message",
                            "work-event",
                            Map.of("kind", "work"))
                    .then(sender.send(
                            "marker.exchange",
                            "marker.key",
                            "marker-message",
                            "marker-event",
                            Map.of("kind", "marker")));

            StepVerifier.create(chained)
                    .expectComplete()
                    .verify(Duration.ofSeconds(2));

            assertThat(publishThreads).hasSize(2);
            assertThat(publishThreads)
                    .allSatisfy(threadName -> {
                        assertThat(threadName)
                                .startsWith("admin-mail-rabbit-publish-test-");
                        assertThat(threadName)
                                .isNotEqualTo("amqp-callback-test");
                    });
        } finally {
            publishScheduler.dispose();
            callbackExecutor.shutdownNow();
        }
    }

    @Test
    void retriesNackOnlyUpToConfiguredAttemptLimit() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AtomicInteger attempts = new AtomicInteger();
        Scheduler publishScheduler = Schedulers.newBoundedElastic(
                1,
                256,
                "admin-mail-rabbit-publish-test");
        doAnswer(invocation -> {
                    attempts.incrementAndGet();
                    CorrelationData correlation = invocation.getArgument(4);
                    correlation.getFuture().complete(
                            new CorrelationData.Confirm(false, "test-nack"));
                    return null;
                })
                .when(rabbitTemplate)
                .convertAndSend(
                        anyString(),
                        anyString(),
                        any(),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));

        try {
            MailInspectionRabbitConfirmedSender sender = sender(
                    rabbitTemplate,
                    properties(
                            Duration.ofMillis(100),
                            3,
                            List.of(
                                    Duration.ofMillis(5),
                                    Duration.ofMillis(5))),
                    publishScheduler);

            StepVerifier.create(sender.send(
                            "exchange",
                            "routing-key",
                            "message-id",
                            "event-type",
                            Map.of("kind", "nack")))
                    .expectError(MailInspectionPublishException.class)
                    .verify(Duration.ofSeconds(1));

            assertThat(attempts).hasValue(3);
        } finally {
            publishScheduler.dispose();
        }
    }

    @Test
    void rejectsMandatoryReturnEvenWhenConfirmIsAck() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        Scheduler publishScheduler = Schedulers.newBoundedElastic(
                1,
                256,
                "admin-mail-rabbit-publish-test");
        doAnswer(invocation -> {
                    CorrelationData correlation = invocation.getArgument(4);
                    correlation.setReturned(new ReturnedMessage(
                            new Message(new byte[0]),
                            312,
                            "NO_ROUTE",
                            "exchange",
                            "routing-key"));
                    correlation.getFuture().complete(
                            new CorrelationData.Confirm(true, null));
                    return null;
                })
                .when(rabbitTemplate)
                .convertAndSend(
                        anyString(),
                        anyString(),
                        any(),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));

        try {
            MailInspectionRabbitConfirmedSender sender = sender(
                    rabbitTemplate,
                    properties(
                            Duration.ofMillis(100),
                            1,
                            List.of(Duration.ofMillis(5))),
                    publishScheduler);

            StepVerifier.create(sender.send(
                            "exchange",
                            "routing-key",
                            "message-id",
                            "event-type",
                            Map.of("kind", "returned")))
                    .expectError(MailInspectionPublishException.class)
                    .verify(Duration.ofSeconds(1));
        } finally {
            publishScheduler.dispose();
        }
    }

    @Test
    void retriesConfirmTimeoutOnlyUpToConfiguredAttemptLimit() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AtomicInteger attempts = new AtomicInteger();
        Scheduler publishScheduler = Schedulers.newBoundedElastic(
                1,
                256,
                "admin-mail-rabbit-publish-test");
        doAnswer(invocation -> {
                    attempts.incrementAndGet();
                    return null;
                })
                .when(rabbitTemplate)
                .convertAndSend(
                        anyString(),
                        anyString(),
                        any(),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));

        try {
            MailInspectionRabbitConfirmedSender sender = sender(
                    rabbitTemplate,
                    properties(
                            Duration.ofMillis(20),
                            3,
                            List.of(
                                    Duration.ofMillis(5),
                                    Duration.ofMillis(5))),
                    publishScheduler);

            StepVerifier.create(sender.send(
                            "exchange",
                            "routing-key",
                            "message-id",
                            "event-type",
                            Map.of("kind", "timeout")))
                    .expectError(MailInspectionPublishException.class)
                    .verify(Duration.ofSeconds(1));

            assertThat(attempts).hasValue(3);
        } finally {
            publishScheduler.dispose();
        }
    }

    @Test
    void schedulerRejectionDoesNotFallBackToCallingThread() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AtomicInteger attempts = new AtomicInteger();
        Scheduler publishScheduler = Schedulers.newBoundedElastic(
                1,
                1,
                "admin-mail-rabbit-publish-test");
        publishScheduler.dispose();
        doAnswer(invocation -> {
                    attempts.incrementAndGet();
                    return null;
                })
                .when(rabbitTemplate)
                .convertAndSend(
                        anyString(),
                        anyString(),
                        any(),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));

        MailInspectionRabbitConfirmedSender sender = sender(
                rabbitTemplate,
                properties(
                        Duration.ofMillis(20),
                        1,
                        List.of(Duration.ofMillis(5))),
                publishScheduler);

        StepVerifier.create(sender.send(
                        "exchange",
                        "routing-key",
                        "message-id",
                        "event-type",
                        Map.of("kind", "rejected")))
                .expectError(MailInspectionPublishException.class)
                .verify(Duration.ofSeconds(1));

        assertThat(attempts).hasValue(0);
    }

    private static MailInspectionRabbitConfirmedSender sender(
            RabbitTemplate rabbitTemplate,
            AdminMailInspectionProperties properties,
            Scheduler publishScheduler) {
        return new MailInspectionRabbitConfirmedSender(
                rabbitTemplate,
                new ObjectMapper(),
                properties,
                publishScheduler);
    }

    private static AdminMailInspectionProperties properties(
            Duration confirmTimeout,
            int maxAttempts,
            List<Duration> backoffs) {
        AdminMailInspectionProperties defaults =
                AdminMailInspectionProperties.defaults();
        return new AdminMailInspectionProperties(
                defaults.proxy(),
                defaults.oauth(),
                defaults.imap(),
                defaults.job(),
                new AdminMailInspectionProperties.Rabbit(
                        true,
                        defaults.rabbit().payloadKeyBase64(),
                        confirmTimeout,
                        maxAttempts,
                        backoffs,
                        defaults.rabbit().markerCleanupInterval(),
                        defaults.rabbit().markerCleanupBatchSize()),
                defaults.submission(),
                defaults.scan(),
                defaults.matchers());
    }
}
