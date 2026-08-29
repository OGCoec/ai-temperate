package com.example.temperate.service.user.membership.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 该测试是来约束会员支付计时上下文聚合Rabbit延迟和分步骤耗时，并在完成后清除复用线程状态。
 */
@ExtendWith(OutputCaptureExtension.class)
final class MembershipPaymentTimingRecorderTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:10Z");

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void logsOneAggregatedRabbitCompletionAndRecordsMetrics(CapturedOutput output) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MembershipPaymentMetrics metrics = new MembershipPaymentMetrics(registry);
        MembershipPaymentTimingRecorder recorder = new MembershipPaymentTimingRecorder(
                metrics,
                new MembershipPaymentObservabilityProperties(
                        true,
                        true,
                        1D,
                        Duration.ofSeconds(1),
                        "boundary-20260824-01",
                        true),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MembershipPaymentTimingRecorder.Session session = recorder.start(
                MembershipPaymentOperation.RABBIT_PENDING,
                new Object[] {envelope(), rabbitMessage()});

        recorder.recordStep(
                MembershipPaymentTimingStep.REDIS_ORDER_READ,
                Duration.ofMillis(3).toNanos(),
                true);
        recorder.recordStep(
                MembershipPaymentTimingStep.REDIS_ORDER_WRITE,
                Duration.ofMillis(4).toNanos(),
                true);
        recorder.recordStep(
                MembershipPaymentTimingStep.REDIS_PROVIDER_WRITE,
                Duration.ofMillis(5).toNanos(),
                true);
        recorder.markRabbitOutcome("ACK", 0L);
        recorder.finish(session, null, null);

        assertThat(output)
                .contains("event=membership_payment_operation_completed")
                .contains("v=2")
                .contains("op=RABBIT_PENDING")
                .contains("r=boundary-20260824-01")
                .contains("oid=" + orderId())
                .contains("fl=PENDING")
                .contains("si=8")
                .contains("sd=10000")
                .contains("aa=ACK")
                .matches("(?s).*t=\\d+\\.\\d{3}.*")
                .matches("(?s).*ro=3\\.000.*")
                .matches("(?s).*row=4\\.000.*")
                .matches("(?s).*rpw=5\\.000.*");
        assertThat(registry.get("membership_payment_operation_total")
                        .tag("operation", "rabbit_pending")
                        .tag("outcome", "acked")
                        .counter()
                        .count())
                .isEqualTo(1D);
        assertThat(recorder.active()).isFalse();
    }

    @Test
    void failureIsAlwaysLoggedEvenWhenSamplingIsDisabled(CapturedOutput output) {
        MembershipPaymentTimingRecorder recorder = new MembershipPaymentTimingRecorder(
                new MembershipPaymentMetrics(new SimpleMeterRegistry()),
                new MembershipPaymentObservabilityProperties(
                        true,
                        false,
                        0D,
                        Duration.ofMinutes(1),
                        "unavailable",
                        false),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MembershipPaymentTimingRecorder.Session session = recorder.start(
                MembershipPaymentOperation.ORDER_GET,
                new Object[] {new byte[16]});

        recorder.finish(session, null, new IllegalStateException("sensitive-detail"));

        assertThat(output)
                .contains("out=FAILED")
                .contains("err=IllegalStateException")
                .doesNotContain("oid=" + orderId())
                .doesNotContain("sensitive-detail");
    }

    @Test
    void forcedOperationIsLoggedEvenWhenItCompletesBelowSlowThreshold(CapturedOutput output) {
        MembershipPaymentTimingRecorder recorder = new MembershipPaymentTimingRecorder(
                new MembershipPaymentMetrics(new SimpleMeterRegistry()),
                new MembershipPaymentObservabilityProperties(
                        true,
                        false,
                        0D,
                        Duration.ofMinutes(1),
                        Set.of(
                                MembershipPaymentOperation.ORDER_CREATE,
                                MembershipPaymentOperation.PAYMENT_ATTEMPT),
                        "membership-payment-256-40k-retest-20260824-170500",
                        true),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MembershipPaymentTimingRecorder.Session session = recorder.start(
                MembershipPaymentOperation.ORDER_CREATE,
                new Object[] {envelope()});

        recorder.recordStep(
                MembershipPaymentTimingStep.DATABASE_CALL,
                Duration.ofMillis(5).toNanos(),
                true);
        recorder.recordStep(
                MembershipPaymentTimingStep.REDIS_ORDER_WRITE,
                Duration.ofMillis(8).toNanos(),
                true);
        recorder.recordStep(
                MembershipPaymentTimingStep.OTHER_REDIS,
                Duration.ofMillis(4).toNanos(),
                true);
        recorder.recordStep(
                MembershipPaymentTimingStep.RABBIT_PUBLISH_CONFIRM,
                Duration.ofMillis(2).toNanos(),
                true);
        recorder.recordRabbitPublishBreakdown(new MembershipPaymentRabbitPublishBreakdown(
                Duration.ofMillis(6).toNanos(),
                Duration.ofMillis(7).toNanos(),
                1));
        recorder.recordDatabaseTransaction(Duration.ofMillis(9).toNanos(), true);
        recorder.finish(session, null, null);

        assertThat(output)
                .contains("event=membership_payment_operation_completed")
                .contains("v=2")
                .contains("r=membership-payment-256-40k-retest-20260824-170500")
                .contains("op=ORDER_CREATE")
                .contains("oid=" + orderId())
                .contains("rps=6.000")
                .contains("rcw=7.000")
                .contains("rpsz=1")
                .contains("dbt=9.000")
                .matches("(?s).*t=\\d+\\.\\d{3}.*");

        String compactEvent = Arrays.stream(output.getOut().split("\\R"))
                .filter(line -> line.contains("event=membership_payment_operation_completed"))
                .map(line -> line.substring(line.indexOf("event=")))
                .findFirst()
                .orElseThrow();
        assertThat(compactEvent.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(288);
        assertThat(compactEvent)
                .doesNotContain("traceId=", "messageId=", "flow=", "stageIndex=")
                .doesNotContain("redisOrderCalls=", "dbCalls=", "markerCalls=")
                .doesNotContain("=0.000", "=none", "=unavailable");
    }

    @Test
    void forcedModeRetainsRabbitRedeliveryEvenWhenItIsFast(CapturedOutput output) {
        MembershipPaymentTimingRecorder recorder = new MembershipPaymentTimingRecorder(
                new MembershipPaymentMetrics(new SimpleMeterRegistry()),
                new MembershipPaymentObservabilityProperties(
                        true,
                        false,
                        0D,
                        Duration.ofMinutes(1),
                        Set.of(
                                MembershipPaymentOperation.ORDER_CREATE,
                                MembershipPaymentOperation.PAYMENT_ATTEMPT),
                        "focused-20260824-retry",
                        true),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MembershipPaymentTimingRecorder.Session retry = recorder.start(
                MembershipPaymentOperation.RABBIT_PENDING,
                new Object[] {envelope(), rabbitMessage()});

        recorder.markRabbitOutcome("ACK", 1L);
        recorder.finish(retry, null, null);

        assertThat(output)
                .contains("v=2")
                .contains("op=RABBIT_PENDING")
                .contains("dc=1")
                .contains("aa=ACK");
    }

    @Test
    void forcedModeSuppressesOnlyUnlistedFastSuccess(CapturedOutput output) {
        MembershipPaymentTimingRecorder recorder = new MembershipPaymentTimingRecorder(
                new MembershipPaymentMetrics(new SimpleMeterRegistry()),
                new MembershipPaymentObservabilityProperties(
                        true,
                        false,
                        0D,
                        Duration.ofMinutes(1),
                        Set.of(MembershipPaymentOperation.ORDER_CREATE),
                        "focused-20260824-02",
                        true),
                Clock.fixed(NOW, ZoneOffset.UTC));

        MembershipPaymentTimingRecorder.Session success = recorder.start(
                MembershipPaymentOperation.CALLBACK_WORKER_BATCH,
                new Object[0]);
        recorder.finish(success, null, null);
        assertThat(output)
                .doesNotContain("event=membership_payment_operation_completed")
                .doesNotContain("op=CALLBACK_WORKER_BATCH");
    }

    @Test
    void forcedModeRetainsUnlistedFailureAndNack(CapturedOutput output) {
        MembershipPaymentTimingRecorder recorder = new MembershipPaymentTimingRecorder(
                new MembershipPaymentMetrics(new SimpleMeterRegistry()),
                new MembershipPaymentObservabilityProperties(
                        true,
                        false,
                        0D,
                        Duration.ofMinutes(1),
                        Set.of(MembershipPaymentOperation.ORDER_CREATE),
                        "focused-20260824-03",
                        true),
                Clock.fixed(NOW, ZoneOffset.UTC));

        MembershipPaymentTimingRecorder.Session failed = recorder.start(
                MembershipPaymentOperation.ORDER_GET,
                new Object[] {new byte[16]});
        recorder.finish(failed, null, new IllegalStateException("ignored"));
        MembershipPaymentTimingRecorder.Session nacked = recorder.start(
                MembershipPaymentOperation.ORDER_CANCEL,
                new Object[] {new byte[16]});
        recorder.markRabbitOutcome("NACK", 0L);
        recorder.finish(nacked, null, null);

        assertThat(output)
                .contains("op=ORDER_GET")
                .contains("out=FAILED")
                .contains("op=ORDER_CANCEL")
                .contains("out=NACKED");
    }

    @Test
    void forcedModeRetainsUnlistedSlowSuccess(CapturedOutput output) {
        MembershipPaymentTimingRecorder recorder = new MembershipPaymentTimingRecorder(
                new MembershipPaymentMetrics(new SimpleMeterRegistry()),
                new MembershipPaymentObservabilityProperties(
                        true,
                        false,
                        0D,
                        Duration.ofNanos(1),
                        Set.of(MembershipPaymentOperation.ORDER_CREATE),
                        "focused-20260824-04",
                        true),
                Clock.fixed(NOW, ZoneOffset.UTC));

        MembershipPaymentTimingRecorder.Session slow = recorder.start(
                MembershipPaymentOperation.CALLBACK_WORKER_BATCH,
                new Object[0]);
        recorder.finish(slow, null, null);

        assertThat(output)
                .contains("op=CALLBACK_WORKER_BATCH")
                .contains("out=SUCCESS");
    }

    @Test
    void diagnosticsFailureDoesNotEscapeIntoBusinessFlow() {
        MembershipPaymentMetrics metrics = mock(MembershipPaymentMetrics.class);
        doThrow(new IllegalStateException("registry unavailable"))
                .when(metrics)
                .operationStarted(MembershipPaymentOperation.ORDER_GET);
        MembershipPaymentTimingRecorder recorder = new MembershipPaymentTimingRecorder(
                metrics,
                new MembershipPaymentObservabilityProperties(
                        true,
                        true,
                        1D,
                        Duration.ofSeconds(1),
                        "boundary-20260824-01",
                        true),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatCode(() -> {
            MembershipPaymentTimingRecorder.Session session = recorder.start(
                    MembershipPaymentOperation.ORDER_GET,
                    new Object[] {new byte[16]});
            recorder.markRabbitOutcome("ACK", 0L);
            recorder.finish(session, null, null);
        }).doesNotThrowAnyException();
        assertThat(recorder.active()).isFalse();
    }

    private static MembershipPaymentRabbitEnvelope<MembershipPaymentCheckMessage> envelope() {
        return new MembershipPaymentRabbitEnvelope<>(
                id((byte) 2),
                MembershipPaymentRabbitNames.PAYMENT_EVENT,
                MembershipPaymentRabbitEnvelope.CURRENT_SCHEMA_VERSION,
                OffsetDateTime.ofInstant(NOW.minusSeconds(10), ZoneOffset.UTC),
                "trace-payment",
                new MembershipPaymentCheckMessage(orderId(), 8));
    }

    private static Message rabbitMessage() {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("x-delay", -10_000L);
        properties.setHeader("x-delivery-count", 0L);
        return new Message(new byte[0], properties);
    }

    private static String orderId() {
        return id((byte) 3);
    }

    private static String id(byte value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, value);
        return new HybridBase64UrlCodec().encode(bytes);
    }
}
