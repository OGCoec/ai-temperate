package com.example.temperate.service.user.membership.payment.callback;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.callback.impl.MembershipPaymentRejectedCallbackResumeServiceImpl;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentFinalCheckScheduler;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束 REJECTED 回调原子删除 Marker 后只等待真实业务边界，禁止重新引入固定安全延迟。
 */
final class MembershipPaymentRejectedCallbackResumeServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");

    private MembershipPaymentFinalCheckScheduler finalCheckScheduler;
    private MembershipPaymentRejectedCallbackResumeService service;

    @BeforeEach
    void setUp() {
        finalCheckScheduler = mock(MembershipPaymentFinalCheckScheduler.class);
        service = new MembershipPaymentRejectedCallbackResumeServiceImpl(
                finalCheckScheduler);
    }

    @Test
    void pendingOrderResumesAtFinalPendingStageAtExpiryBoundary() {
        MembershipOrderSnapshot order = order(
                MembershipOrderStatus.PENDING_PAYMENT,
                NOW.plusSeconds(240L));

        service.resume(order);

        verify(finalCheckScheduler).schedulePending(order.orderId(), order.expiresAt());
    }

    @Test
    void closingOrderResumesAtFinalClosingStageAtHardDeadline() {
        MembershipOrderSnapshot order = order(
                MembershipOrderStatus.CLOSING,
                NOW.minusSeconds(240L));

        service.resume(order);

        verify(finalCheckScheduler).scheduleClosing(
                order.orderId(), order.closingDeadlineAt(), 0);
    }

    @Test
    void overdueClosingOrderResumesWithoutMarkerCompletionDelay() {
        MembershipOrderSnapshot order = order(
                MembershipOrderStatus.CLOSING,
                NOW.minusSeconds(600L));

        service.resume(order);

        verify(finalCheckScheduler).scheduleClosing(
                order.orderId(), order.closingDeadlineAt(), 0);
    }

    @Test
    void terminalOrderDoesNotRestartEitherMqTimeChain() {
        service.resume(order(MembershipOrderStatus.CLOSED, NOW.minusSeconds(600L)));

        verify(finalCheckScheduler, never()).schedulePending(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(finalCheckScheduler, never()).scheduleClosing(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    private static MembershipOrderSnapshot order(
            MembershipOrderStatus status,
            Instant expiresAt) {
        OffsetDateTime expiry = OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC);
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                "AaAqkcs1AQEURx6JhQEEVw",
                17L,
                MembershipTier.PLUS,
                new BigDecimal("0.20"),
                "alipay",
                status,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                "provider-trade",
                expiry.minusMinutes(4),
                expiry,
                status == MembershipOrderStatus.CLOSING ? expiry.plusMinutes(5) : null,
                null,
                3L,
                expiry.minusMinutes(5),
                expiry.minusMinutes(1));
    }

}
