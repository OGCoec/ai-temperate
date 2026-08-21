package com.example.temperate.service.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackBatchService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackClaim;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.loadtest.impl.MembershipPaymentLoadtestControlServiceImpl;
import com.example.temperate.service.user.membership.payment.persistence.MembershipOrderBatchPersistenceService;
import com.example.temperate.service.user.membership.payment.persistence.OrderPersistToken;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitSender;
import com.example.temperate.service.user.membership.payment.store.OrderPersistenceQueue;
import com.example.temperate.service.user.membership.payment.store.PaymentCallbackQueue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 该测试是来锁定压测恢复探针必须真实经历 claim、超时 recover 和正式批处理，空队列不能伪造通过。
 */
final class MembershipPaymentLoadtestControlServiceImplTest {

    private PaymentCallbackQueue callbackQueue;
    private OrderPersistenceQueue orderQueue;
    private PaymentCallbackBatchService callbackBatchService;
    private MembershipOrderBatchPersistenceService orderBatchService;
    private MembershipPaymentLoadtestControlService service;

    @BeforeEach
    void setUp() {
        callbackQueue = mock(PaymentCallbackQueue.class);
        orderQueue = mock(OrderPersistenceQueue.class);
        callbackBatchService = mock(PaymentCallbackBatchService.class);
        orderBatchService = mock(MembershipOrderBatchPersistenceService.class);
        service = new MembershipPaymentLoadtestControlServiceImpl(
                callbackQueue,
                orderQueue,
                callbackBatchService,
                orderBatchService,
                properties(),
                mock(StringRedisTemplate.class),
                mock(RedisKeyFactory.class),
                Clock.fixed(Instant.parse("2026-08-21T12:00:00Z"), ZoneOffset.UTC),
                mock(MembershipPaymentRabbitSender.class),
                mock(HybridSemaphoreIdWorker.class),
                mock(HybridBase64UrlCodec.class),
                mock(MembershipPaymentLoadtestFaultGate.class));
    }

    @Test
    void callbackRecoveryClaimsStaleLeaseAndRunsNormalBatch() {
        when(callbackQueue.claim(eq(1), anyLong())).thenReturn(List.of(
                new PaymentCallbackClaim("AaAjECcaAQGqi_h2Rl1PiA", 1_777_000_000_000L)));
        when(callbackQueue.recoverTimedOut(anyLong(), eq(1), anyLong())).thenReturn(1);
        when(callbackQueue.processingSize()).thenReturn(0L);

        var result = service.recoverOneCallbackProcessing();

        assertThat(result).isEqualTo(
                new MembershipPaymentLoadtestControlService.RecoveryProbe(1, 1, 0L));
        verify(callbackBatchService).flushOneRun();
    }

    @Test
    void orderRecoveryClaimsStaleVersionAndRunsNormalPersistenceBatch() {
        when(orderQueue.claim(eq(1), anyLong())).thenReturn(List.of(
                new OrderPersistToken("AaAjECcaAQGqi_h2Rl1PiA", 2L, 1_777_000_000_000L)));
        when(orderQueue.recoverTimedOut(anyLong(), eq(1), anyLong())).thenReturn(1);
        when(orderQueue.processingSize()).thenReturn(0L);

        var result = service.recoverOneOrderProcessing();

        assertThat(result.recovered()).isEqualTo(1);
        verify(orderBatchService).flushOneRun();
    }

    @Test
    void emptyCallbackReadyQueueFailsInsteadOfReportingRecoveryPass() {
        when(callbackQueue.claim(eq(1), anyLong())).thenReturn(List.of());

        assertThatThrownBy(service::recoverOneCallbackProcessing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one ready item");
        verify(callbackQueue, never()).recoverTimedOut(anyLong(), eq(1), anyLong());
        verify(callbackBatchService, never()).flushOneRun();
    }

    @Test
    void emptyTerminalArtifactBatchIsRejectedBeforeRedisIo() {
        assertThatThrownBy(() -> service.inspectOrderArtifacts(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one loadtest order ID");
    }

    @Test
    void terminalArtifactBatchCannotExceedPipelineBoundary() {
        List<String> orderIds = java.util.stream.IntStream.range(0, 251)
                .mapToObj(index -> "AaAjECcaAQGqi_h2Rl1PiA")
                .toList();

        assertThatThrownBy(() -> service.inspectOrderArtifacts(orderIds))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed 250 orders");
    }

    private static MembershipPaymentProperties properties() {
        return new MembershipPaymentProperties(
                true,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                new MembershipPaymentProperties.Simulator(
                        true,
                        "loadtest-merchant",
                        "membership-loadtest-callback-key-v1-local",
                        Duration.ofMinutes(5),
                        16_384,
                        true),
                new MembershipPaymentProperties.Callback(
                        5_000L, 100, 20, Duration.ofSeconds(60),
                        Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofHours(6)),
                new MembershipPaymentProperties.OrderPersist(
                        5_000L, 100, 20, Duration.ofSeconds(60), Duration.ofMillis(100)),
                new MembershipPaymentProperties.Rabbit(
                        List.of(10_000L, 10_000L, 10_000L, 15_000L, 15_000L,
                                30_000L, 30_000L, 60_000L, 120_000L),
                        List.of(30_000L, 30_000L, 60_000L, 60_000L, 120_000L),
                        Duration.ofSeconds(30),
                        3));
    }
}
