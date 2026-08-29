package com.example.temperate.service.user.membership.payment.rabbit.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentRabbitPublishBreakdown;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 该测试是来证明发布 worker 不串行等待 Confirm，并锁定零延迟消息、持久化属性和逐消息 ACK 完成边界。
 */
final class BoundedMembershipPaymentRabbitConfirmCoordinatorImplTest {

    @Test
    void workersPublishSeveralMessagesBeforeTheirIndependentConfirmsComplete()
            throws Exception {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        List<CorrelationData> correlations = new CopyOnWriteArrayList<>();
        List<Message> messages = new CopyOnWriteArrayList<>();
        CountDownLatch published = new CountDownLatch(2);
        doAnswer(invocation -> {
                    MessagePostProcessor processor = invocation.getArgument(3);
                    messages.add(processor.postProcessMessage(new Message(new byte[0])));
                    correlations.add(invocation.getArgument(4));
                    published.countDown();
                    return null;
                })
                .when(rabbitTemplate)
                .convertAndSend(
                        anyString(),
                        anyString(),
                        any(),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));
        ExecutorService publishExecutor = Executors.newFixedThreadPool(8);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        BoundedMembershipPaymentRabbitConfirmCoordinatorImpl coordinator =
                new BoundedMembershipPaymentRabbitConfirmCoordinatorImpl(
                        rabbitTemplate,
                        new MembershipPaymentMetrics(new SimpleMeterRegistry()),
                        publishExecutor);
        coordinator.afterPropertiesSet();

        try {
            CompletableFuture<MembershipPaymentRabbitPublishBreakdown> first =
                    CompletableFuture.supplyAsync(
                    () -> coordinator.publishAndAwait(
                            "membership.exchange",
                            "membership.key",
                            envelope((byte) 1),
                            Duration.ZERO),
                    callers);
            CompletableFuture<MembershipPaymentRabbitPublishBreakdown> second =
                    CompletableFuture.supplyAsync(
                    () -> coordinator.publishAndAwait(
                            "membership.exchange",
                            "membership.key",
                            envelope((byte) 2),
                            Duration.ZERO),
                    callers);

            assertThat(published.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(first).isNotDone();
            assertThat(second).isNotDone();
            correlations.forEach(correlation -> correlation.getFuture().complete(
                    new CorrelationData.Confirm(true, null)));
            CompletableFuture.allOf(first, second).get(1, TimeUnit.SECONDS);
            assertThat(first.join().submissionSize()).isEqualTo(1);
            assertThat(first.join().submitNanos()).isNotNegative();
            assertThat(first.join().confirmWaitNanos()).isNotNegative();
            assertThat(second.join().submissionSize()).isEqualTo(1);

            assertThat(messages).hasSize(2).allSatisfy(message -> {
                assertThat(message.getMessageProperties().getDeliveryMode().name())
                        .isEqualTo("PERSISTENT");
                Long delayMillis = message.getMessageProperties().getHeader("x-delay");
                assertThat(delayMillis).isZero();
            });
        } finally {
            coordinator.destroy();
            callers.shutdownNow();
            publishExecutor.shutdownNow();
        }
    }

    @Test
    void nackFailsOnlyTheCorrespondingPublishWithTheControlledError() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
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
        ExecutorService executor = Executors.newFixedThreadPool(8);
        BoundedMembershipPaymentRabbitConfirmCoordinatorImpl coordinator =
                coordinator(rabbitTemplate, executor);

        try {
            assertThatThrownBy(() -> coordinator.publishAndAwait(
                            "membership.exchange",
                            "membership.key",
                            envelope((byte) 3),
                            Duration.ZERO))
                    .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                            assertThat(exception.code()).isEqualTo(
                                    MembershipPaymentErrorCode
                                            .MEMBERSHIP_PAYMENT_RABBIT_UNAVAILABLE));
        } finally {
            coordinator.destroy();
            executor.shutdownNow();
        }
    }

    @Test
    void mandatoryReturnFailsEvenWhenBrokerConfirmIsAck() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
                    CorrelationData correlation = invocation.getArgument(4);
                    correlation.setReturned(new ReturnedMessage(
                            new Message(new byte[0]),
                            312,
                            "NO_ROUTE",
                            "membership.exchange",
                            "membership.key"));
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
        ExecutorService executor = Executors.newFixedThreadPool(8);
        BoundedMembershipPaymentRabbitConfirmCoordinatorImpl coordinator =
                coordinator(rabbitTemplate, executor);

        try {
            assertThatThrownBy(() -> coordinator.publishAndAwait(
                            "membership.exchange",
                            "membership.key",
                            envelope((byte) 4),
                            Duration.ZERO))
                    .isInstanceOf(MembershipPaymentException.class);
        } finally {
            coordinator.destroy();
            executor.shutdownNow();
        }
    }

    @Test
    void positiveSubMillisecondDelayRoundsUpInsteadOfCrossingTheBoundaryEarly() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AtomicReference<Object> delayHeader = new AtomicReference<>();
        doAnswer(invocation -> {
                    MessagePostProcessor processor = invocation.getArgument(3);
                    Message message = processor.postProcessMessage(new Message(new byte[0]));
                    delayHeader.set(message.getMessageProperties().getHeader("x-delay"));
                    CorrelationData correlation = invocation.getArgument(4);
                    correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
                    return null;
                })
                .when(rabbitTemplate)
                .convertAndSend(
                        anyString(),
                        anyString(),
                        any(),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        BoundedMembershipPaymentRabbitConfirmCoordinatorImpl coordinator =
                coordinator(rabbitTemplate, executor);

        try {
            MembershipPaymentRabbitPublishBreakdown breakdown = coordinator.publishAndAwait(
                    "membership.exchange",
                    "membership.key",
                    envelope((byte) 5),
                    Duration.ofNanos(1));

            assertThat(delayHeader.get()).isEqualTo(1L);
            assertThat(breakdown.submissionSize()).isEqualTo(1);
        } finally {
            coordinator.destroy();
            executor.shutdownNow();
        }
    }

    private static BoundedMembershipPaymentRabbitConfirmCoordinatorImpl coordinator(
            RabbitTemplate rabbitTemplate,
            ExecutorService executor) {
        BoundedMembershipPaymentRabbitConfirmCoordinatorImpl coordinator =
                new BoundedMembershipPaymentRabbitConfirmCoordinatorImpl(
                        rabbitTemplate,
                        new MembershipPaymentMetrics(new SimpleMeterRegistry()),
                        executor);
        coordinator.afterPropertiesSet();
        return coordinator;
    }

    private static MembershipPaymentRabbitEnvelope<MembershipPaymentCheckMessage> envelope(
            byte value) {
        String orderId = id((byte) 9);
        return new MembershipPaymentRabbitEnvelope<>(
                id(value),
                "membership-payment-check",
                1,
                OffsetDateTime.of(2026, 8, 25, 12, 0, 0, 0, ZoneOffset.UTC),
                "trace-test",
                new MembershipPaymentCheckMessage(orderId, 8));
    }

    private static String id(byte value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, value);
        return new HybridBase64UrlCodec().encode(bytes);
    }
}
