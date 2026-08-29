package com.example.temperate.service.user.membership.payment.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.callback.PaymentFactReconciliationService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.order.impl.MembershipPaymentAttemptServiceImpl;
import com.example.temperate.service.user.membership.payment.order.impl.MembershipPaymentAttemptTransactionServiceImpl;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutSubmission;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutSubmissionFields;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCoordinator;
import com.example.temperate.service.user.membership.payment.store.MembershipProviderTradeNoPatchOutcome;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 该单元测试是来锁定支付发起写入、Redis 刷新、BAR 提交描述传播、幂等重放和并发取消保护。
 */
final class MembershipPaymentAttemptServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:04:59Z");
    private static final OffsetDateTime NOW_OFFSET =
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final byte[] ORDER_ID = id((byte) 9);

    @Test
    void transactionReportsFirstAtomicWriteAndPreservesIncrementedVersion() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        MembershipOrder started = order(NOW_OFFSET, NOW_OFFSET.plusSeconds(1));
        started.setStateVersion(2L);
        when(mapper.startPaymentAttemptIfAbsent(
                any(), anyLong(), any(), any())).thenReturn(started);
        MembershipPaymentAttemptTransactionServiceImpl service =
                new MembershipPaymentAttemptTransactionServiceImpl(mapper);

        MembershipPaymentAttemptDatabaseResult result = service.startOrGet(
                17L, ORDER_ID, NOW_OFFSET);

        assertThat(result.started()).isTrue();
        assertThat(result.order().getPaymentStartedAt()).isEqualTo(NOW_OFFSET);
        assertThat(result.order().getStateVersion()).isEqualTo(2L);
    }

    @Test
    void transactionReturnsOriginalStartTimeForReplayWithinValidityWindow() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        OffsetDateTime original = NOW_OFFSET.minusSeconds(30);
        MembershipOrder persisted = order(original, NOW_OFFSET.plusSeconds(1));
        when(mapper.findOwnedById(ORDER_ID, 17L)).thenReturn(persisted);
        MembershipPaymentAttemptTransactionServiceImpl service =
                new MembershipPaymentAttemptTransactionServiceImpl(mapper);

        MembershipPaymentAttemptDatabaseResult result = service.startOrGet(
                17L, ORDER_ID, NOW_OFFSET);

        assertThat(result.started()).isFalse();
        assertThat(result.order().getPaymentStartedAt()).isEqualTo(original);
    }

    @Test
    void transactionRejectsExactExpiryAndTerminalStates() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        MembershipOrder expired = order(NOW_OFFSET.minusSeconds(1), NOW_OFFSET);
        when(mapper.findOwnedById(ORDER_ID, 17L)).thenReturn(expired);
        MembershipPaymentAttemptTransactionServiceImpl service =
                new MembershipPaymentAttemptTransactionServiceImpl(mapper);

        assertThatThrownBy(() -> service.startOrGet(17L, ORDER_ID, NOW_OFFSET))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT));

        expired.setExpiresAt(NOW_OFFSET.plusMinutes(1));
        expired.setStatus(MembershipOrderStatus.CLOSING);
        assertThatThrownBy(() -> service.startOrGet(17L, ORDER_ID, NOW_OFFSET))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT));
    }

    @Test
    void providerTradeBindingTreatsTheSameValueAsIdempotentReplay() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        MembershipOrder persisted = order(NOW_OFFSET.minusSeconds(1), NOW_OFFSET.plusMinutes(1));
        persisted.setProviderTradeNo("1234567890123456789");
        when(mapper.findOwnedById(ORDER_ID, 17L)).thenReturn(persisted);
        MembershipPaymentAttemptTransactionServiceImpl service =
                new MembershipPaymentAttemptTransactionServiceImpl(mapper);

        MembershipOrder result = service.bindProviderTradeNo(
                17L, ORDER_ID, "1234567890123456789");

        assertThat(result).isSameAs(persisted);
    }

    @Test
    void providerTradeBindingRejectsADifferentExistingValue() {
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        MembershipOrder persisted = order(NOW_OFFSET.minusSeconds(1), NOW_OFFSET.plusMinutes(1));
        persisted.setProviderTradeNo("1234567890123456788");
        when(mapper.findOwnedById(ORDER_ID, 17L)).thenReturn(persisted);
        MembershipPaymentAttemptTransactionServiceImpl service =
                new MembershipPaymentAttemptTransactionServiceImpl(mapper);

        assertThatThrownBy(() -> service.bindProviderTradeNo(
                        17L, ORDER_ID, "1234567890123456789"))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(MembershipPaymentErrorCode.BAR_ORDER_CONFLICT));
    }

    @Test
    void orchestratorRefreshesRedisAfterDatabaseResultAndReturnsCurrentVersion() {
        MembershipPaymentAttemptTransactionService transactionService =
                mock(MembershipPaymentAttemptTransactionService.class);
        MembershipOrderSnapshotStore snapshotStore = mock(MembershipOrderSnapshotStore.class);
        MembershipOrderSnapshotWriteCoordinator snapshotWriteCoordinator =
                passthroughCoordinator();
        MembershipOrder order = order(NOW_OFFSET, NOW_OFFSET.plusSeconds(1));
        order.setStateVersion(2L);
        when(transactionService.startOrGet(17L, ORDER_ID, NOW_OFFSET))
                .thenReturn(new MembershipPaymentAttemptDatabaseResult(order, true));
        stubRealtimeGuards(snapshotStore, realtimeGuard(order));
        MembershipPaymentAttemptServiceImpl service = attemptService(
                transactionService, snapshotStore, snapshotWriteCoordinator);

        MembershipPaymentAttemptResult result = service.start(17L, ORDER_ID);

        assertThat(result.started()).isTrue();
        assertThat(result.snapshot().paymentStartedAt()).isEqualTo(NOW_OFFSET);
        assertThat(result.snapshot().stateVersion()).isEqualTo(2L);
        assertThat(result.checkoutSubmission()).isNull();
        verify(snapshotWriteCoordinator).patchPaymentAttempt(result.snapshot());
    }

    @Test
    void barOrchestratorPropagatesSubmissionAfterBindingTradeNumberInOrder() {
        MembershipPaymentAttemptTransactionService transactionService =
                mock(MembershipPaymentAttemptTransactionService.class);
        MembershipOrderSnapshotStore snapshotStore = mock(MembershipOrderSnapshotStore.class);
        MembershipOrderSnapshotWriteCoordinator snapshotWriteCoordinator =
                passthroughCoordinator();
        MembershipPaymentProvider provider = mock(MembershipPaymentProvider.class);
        PaymentFactReconciliationService reconciliationService =
                mock(PaymentFactReconciliationService.class);
        MembershipOrder order = order(NOW_OFFSET, NOW_OFFSET.plusMinutes(10));
        order.setStateVersion(2L);
        when(transactionService.startOrGet(17L, ORDER_ID, NOW_OFFSET))
                .thenReturn(new MembershipPaymentAttemptDatabaseResult(order, true));
        stubRealtimeGuards(snapshotStore, realtimeGuard(order));
        PaymentCheckoutSubmission submission = checkoutSubmission("1787241900", "a".repeat(64));
        when(provider.createCheckout(any())).thenReturn(
                new PaymentCheckoutResult(
                        "1234567890123456789",
                        NOW_OFFSET.plusMinutes(10),
                        true,
                        submission));
        MembershipPaymentAttemptServiceImpl service = attemptService(
                transactionService,
                snapshotStore,
                snapshotWriteCoordinator,
                PaymentProviderType.BAR,
                provider,
                reconciliationService);

        MembershipPaymentAttemptResult result = service.start(17L, ORDER_ID);

        assertThat(result.provider()).isEqualTo(PaymentProviderType.BAR);
        assertThat(result.checkoutSubmission()).isSameAs(submission);
        InOrder ordered = inOrder(
                snapshotStore, snapshotWriteCoordinator, transactionService, provider);
        ordered.verify(snapshotStore).findRealtimeGuard(any());
        ordered.verify(transactionService).startOrGet(anyLong(), any(), any());
        ordered.verify(snapshotWriteCoordinator).patchPaymentAttempt(any());
        ordered.verify(provider).createCheckout(any());
        ordered.verify(transactionService).bindProviderTradeNo(
                anyLong(), any(), org.mockito.ArgumentMatchers.eq("1234567890123456789"));
        ordered.verify(snapshotStore).patchProviderTradeNo(
                any(), anyLong(), org.mockito.ArgumentMatchers.eq("1234567890123456789"));
        ordered.verify(snapshotStore).findRealtimeGuard(any());
        verify(provider, never()).closePayment(any());
        verifyNoInteractions(reconciliationService);
    }

    @Test
    void barOrchestratorCapsFirstSubmissionExpiryAtLocalOrderDeadline() {
        MembershipPaymentAttemptTransactionService transactionService =
                mock(MembershipPaymentAttemptTransactionService.class);
        MembershipOrderSnapshotStore snapshotStore = mock(MembershipOrderSnapshotStore.class);
        MembershipPaymentProvider provider = mock(MembershipPaymentProvider.class);
        OffsetDateTime orderExpiresAt = NOW_OFFSET.plusMinutes(5).minusSeconds(1);
        MembershipOrder order = order(NOW_OFFSET, orderExpiresAt);
        order.setStateVersion(2L);
        when(transactionService.startOrGet(17L, ORDER_ID, NOW_OFFSET))
                .thenReturn(new MembershipPaymentAttemptDatabaseResult(order, true));
        stubRealtimeGuards(snapshotStore, realtimeGuard(order));
        PaymentCheckoutSubmission providerSubmission = checkoutSubmission(
                "1787241900",
                "f".repeat(64),
                NOW_OFFSET.plusMinutes(5));
        when(provider.createCheckout(any())).thenReturn(
                new PaymentCheckoutResult(
                        "1234567890123456789",
                        NOW_OFFSET.plusMinutes(10),
                        true,
                        providerSubmission));
        MembershipPaymentAttemptServiceImpl service = attemptService(
                transactionService,
                snapshotStore,
                PaymentProviderType.BAR,
                provider,
                mock(PaymentFactReconciliationService.class));

        MembershipPaymentAttemptResult result = service.start(17L, ORDER_ID);

        assertThat(result.started()).isTrue();
        assertThat(result.checkoutSubmission().submitExpiresAt())
                .isEqualTo(order.getExpiresAt())
                .isBeforeOrEqualTo(result.snapshot().expiresAt());
        assertThat(result.checkoutSubmission().fields())
                .isSameAs(providerSubmission.fields());
        assertThat(result.checkoutSubmission().fields().timestamp())
                .isEqualTo(providerSubmission.fields().timestamp());
        assertThat(result.checkoutSubmission().fields().sign())
                .isEqualTo(providerSubmission.fields().sign());
    }

    @Test
    void barIdempotentReplayStillReturnsTheFreshProviderSubmission() {
        MembershipPaymentAttemptTransactionService transactionService =
                mock(MembershipPaymentAttemptTransactionService.class);
        MembershipOrderSnapshotStore snapshotStore = mock(MembershipOrderSnapshotStore.class);
        MembershipPaymentProvider provider = mock(MembershipPaymentProvider.class);
        MembershipOrder order = order(NOW_OFFSET.minusSeconds(30), NOW_OFFSET.plusMinutes(10));
        when(transactionService.startOrGet(17L, ORDER_ID, NOW_OFFSET))
                .thenReturn(new MembershipPaymentAttemptDatabaseResult(order, false));
        stubRealtimeGuards(snapshotStore, realtimeGuard(order));
        PaymentCheckoutSubmission fresh = checkoutSubmission("1787241902", "b".repeat(64));
        when(provider.createCheckout(any())).thenReturn(
                new PaymentCheckoutResult(
                        "1234567890123456789",
                        NOW_OFFSET.plusMinutes(10),
                        false,
                        fresh));
        MembershipPaymentAttemptServiceImpl service = attemptService(
                transactionService,
                snapshotStore,
                PaymentProviderType.BAR,
                provider,
                mock(PaymentFactReconciliationService.class));

        MembershipPaymentAttemptResult result = service.start(17L, ORDER_ID);

        assertThat(result.started()).isFalse();
        assertThat(result.checkoutSubmission()).isSameAs(fresh);
    }

    @Test
    void barIdempotentReplayCapsSubmissionExpiryAtLocalOrderDeadline() {
        MembershipPaymentAttemptTransactionService transactionService =
                mock(MembershipPaymentAttemptTransactionService.class);
        MembershipOrderSnapshotStore snapshotStore = mock(MembershipOrderSnapshotStore.class);
        MembershipPaymentProvider provider = mock(MembershipPaymentProvider.class);
        OffsetDateTime orderExpiresAt = NOW_OFFSET.plusMinutes(5).minusSeconds(1);
        MembershipOrder order = order(NOW_OFFSET.minusSeconds(30), orderExpiresAt);
        when(transactionService.startOrGet(17L, ORDER_ID, NOW_OFFSET))
                .thenReturn(new MembershipPaymentAttemptDatabaseResult(order, false));
        stubRealtimeGuards(snapshotStore, realtimeGuard(order));
        PaymentCheckoutSubmission providerSubmission = checkoutSubmission(
                "1787241902",
                "0".repeat(64),
                NOW_OFFSET.plusMinutes(5));
        when(provider.createCheckout(any())).thenReturn(
                new PaymentCheckoutResult(
                        "1234567890123456789",
                        NOW_OFFSET.plusMinutes(10),
                        false,
                        providerSubmission));
        MembershipPaymentAttemptServiceImpl service = attemptService(
                transactionService,
                snapshotStore,
                PaymentProviderType.BAR,
                provider,
                mock(PaymentFactReconciliationService.class));

        MembershipPaymentAttemptResult result = service.start(17L, ORDER_ID);

        assertThat(result.started()).isFalse();
        assertThat(result.checkoutSubmission().submitExpiresAt())
                .isEqualTo(order.getExpiresAt())
                .isBeforeOrEqualTo(result.snapshot().expiresAt());
        assertThat(result.checkoutSubmission().fields())
                .isSameAs(providerSubmission.fields());
        assertThat(result.checkoutSubmission().fields().timestamp())
                .isEqualTo(providerSubmission.fields().timestamp());
        assertThat(result.checkoutSubmission().fields().sign())
                .isEqualTo(providerSubmission.fields().sign());
    }

    @Test
    void providerTradePatchConflictClosesTheNewCheckoutBeforeFailing() {
        MembershipPaymentAttemptTransactionService transactionService =
                mock(MembershipPaymentAttemptTransactionService.class);
        MembershipOrderSnapshotStore snapshotStore = mock(MembershipOrderSnapshotStore.class);
        MembershipPaymentProvider provider = mock(MembershipPaymentProvider.class);
        MembershipOrder order = order(NOW_OFFSET, NOW_OFFSET.plusMinutes(10));
        order.setStateVersion(2L);
        when(transactionService.startOrGet(17L, ORDER_ID, NOW_OFFSET))
                .thenReturn(new MembershipPaymentAttemptDatabaseResult(order, true));
        stubRealtimeGuards(snapshotStore, realtimeGuard(order));
        when(provider.createCheckout(any())).thenReturn(new PaymentCheckoutResult(
                "1234567890123456789",
                NOW_OFFSET.plusMinutes(10),
                true,
                checkoutSubmission("1787241900", "1".repeat(64))));
        when(provider.closePayment(any())).thenReturn(new PaymentCloseResult(
                PaymentProviderStatus.CLOSED,
                "1234567890123456789"));
        MembershipPaymentAttemptServiceImpl service = attemptService(
                transactionService,
                snapshotStore,
                PaymentProviderType.BAR,
                provider,
                mock(PaymentFactReconciliationService.class));
        when(snapshotStore.patchProviderTradeNo(any(), anyLong(), any()))
                .thenReturn(MembershipProviderTradeNoPatchOutcome.CONFLICT);

        assertThatThrownBy(() -> service.start(17L, ORDER_ID))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT));
        verify(provider).closePayment(any());
    }

    @Test
    void cancellationDuringBarCreateClosesProviderOrderWithoutLeakingSubmission() {
        MembershipPaymentAttemptTransactionService transactionService =
                mock(MembershipPaymentAttemptTransactionService.class);
        MembershipOrderSnapshotStore snapshotStore = mock(MembershipOrderSnapshotStore.class);
        MembershipPaymentProvider provider = mock(MembershipPaymentProvider.class);
        PaymentFactReconciliationService reconciliationService =
                mock(PaymentFactReconciliationService.class);
        MembershipOrder order = order(NOW_OFFSET, NOW_OFFSET.plusMinutes(10));
        MembershipOrderSnapshot cancelled = snapshotWithStatus(
                order, MembershipOrderStatus.CANCELLED, 3L);
        when(transactionService.startOrGet(17L, ORDER_ID, NOW_OFFSET))
                .thenReturn(new MembershipPaymentAttemptDatabaseResult(order, true));
        when(snapshotStore.findRealtimeGuard(any())).thenReturn(
                Optional.empty(), Optional.of(realtimeGuard(cancelled)));
        PaymentCheckoutSubmission signed = checkoutSubmission(
                "1787241900", "c".repeat(64));
        when(provider.createCheckout(any())).thenReturn(
                new PaymentCheckoutResult(
                        "1234567890123456789",
                        NOW_OFFSET.plusMinutes(10),
                        true,
                        signed));
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(
                        PaymentProviderStatus.CLOSED,
                        "1234567890123456789"));
        MembershipPaymentAttemptServiceImpl service = attemptService(
                transactionService,
                snapshotStore,
                PaymentProviderType.BAR,
                provider,
                reconciliationService);

        assertThatThrownBy(() -> service.start(17L, ORDER_ID))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(
                            MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT);
                    assertThat(exception.getMessage()).doesNotContain(signed.fields().sign());
                });
        verify(provider).closePayment(any());
        verify(provider, never()).queryPayment(any());
        verifyNoInteractions(reconciliationService);
    }

    @Test
    void orderExpiryDuringBarCreateClosesProviderOrderWithoutReturningSubmission() {
        MembershipPaymentAttemptTransactionService transactionService =
                mock(MembershipPaymentAttemptTransactionService.class);
        MembershipOrderSnapshotStore snapshotStore = mock(MembershipOrderSnapshotStore.class);
        MembershipPaymentProvider provider = mock(MembershipPaymentProvider.class);
        Clock advancingClock = mock(Clock.class);
        MembershipOrder order = order(NOW_OFFSET, NOW_OFFSET.plusMinutes(10));
        when(advancingClock.instant()).thenReturn(NOW, NOW.plus(Duration.ofMinutes(11)));
        when(transactionService.startOrGet(17L, ORDER_ID, NOW_OFFSET))
                .thenReturn(new MembershipPaymentAttemptDatabaseResult(order, true));
        stubRealtimeGuards(snapshotStore, realtimeGuard(order));
        PaymentCheckoutSubmission signed = checkoutSubmission(
                "1787241900", "d".repeat(64));
        when(provider.createCheckout(any())).thenReturn(
                new PaymentCheckoutResult(
                        "1234567890123456789",
                        NOW_OFFSET.plusMinutes(15),
                        true,
                        signed));
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(
                        PaymentProviderStatus.CLOSED,
                        "1234567890123456789"));
        MembershipPaymentAttemptServiceImpl service = attemptService(
                transactionService,
                snapshotStore,
                PaymentProviderType.BAR,
                provider,
                mock(PaymentFactReconciliationService.class),
                advancingClock);

        assertThatThrownBy(() -> service.start(17L, ORDER_ID))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(
                            MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT);
                    assertThat(exception.getMessage()).doesNotContain(signed.fields().sign());
                });
        verify(provider).closePayment(any());
    }

    @Test
    void concurrentCancellationStillReconcilesPaidFactReturnedByClose() {
        MembershipPaymentAttemptTransactionService transactionService =
                mock(MembershipPaymentAttemptTransactionService.class);
        MembershipOrderSnapshotStore snapshotStore = mock(MembershipOrderSnapshotStore.class);
        MembershipPaymentProvider provider = mock(MembershipPaymentProvider.class);
        PaymentFactReconciliationService reconciliationService =
                mock(PaymentFactReconciliationService.class);
        MembershipOrder order = order(NOW_OFFSET, NOW_OFFSET.plusMinutes(10));
        MembershipOrderSnapshot cancelled = snapshotWithStatus(
                order, MembershipOrderStatus.CANCELLED, 3L);
        when(transactionService.startOrGet(17L, ORDER_ID, NOW_OFFSET))
                .thenReturn(new MembershipPaymentAttemptDatabaseResult(order, true));
        when(snapshotStore.findRealtimeGuard(any())).thenReturn(
                Optional.empty(), Optional.of(realtimeGuard(cancelled)));
        when(provider.createCheckout(any())).thenReturn(
                new PaymentCheckoutResult(
                        "1234567890123456789",
                        NOW_OFFSET.plusMinutes(10),
                        true,
                        checkoutSubmission("1787241900", "e".repeat(64))));
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(
                        PaymentProviderStatus.PAID,
                        "1234567890123456789"));
        PaymentQueryResult paid = new PaymentQueryResult(
                new HybridBase64UrlCodec().encode(ORDER_ID),
                "1234567890123456789",
                "BAR-P-1234567890123456790",
                PaymentProviderStatus.PAID,
                new BigDecimal("20.00"),
                NOW_OFFSET.plusMinutes(1),
                null);
        when(provider.queryPayment(any())).thenReturn(paid);
        MembershipPaymentAttemptServiceImpl service = attemptService(
                transactionService,
                snapshotStore,
                PaymentProviderType.BAR,
                provider,
                reconciliationService);

        assertThatThrownBy(() -> service.start(17L, ORDER_ID))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT));
        verify(reconciliationService).reconcilePaid(any(), org.mockito.ArgumentMatchers.eq(paid));
    }

    @Test
    void orchestratorRejectsWhenRedisAlreadyContainsNewerPaidState() {
        MembershipPaymentAttemptTransactionService transactionService =
                mock(MembershipPaymentAttemptTransactionService.class);
        MembershipOrderSnapshotStore snapshotStore = mock(MembershipOrderSnapshotStore.class);
        MembershipOrder databaseOrder = order(NOW_OFFSET.minusSeconds(30), NOW_OFFSET.plusMinutes(1));
        databaseOrder.setStateVersion(2L);
        when(transactionService.startOrGet(17L, ORDER_ID, NOW_OFFSET))
                .thenReturn(new MembershipPaymentAttemptDatabaseResult(databaseOrder, false));
        MembershipOrderSnapshot paidSnapshot = new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                new HybridBase64UrlCodec().encode(ORDER_ID),
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                MembershipOrderStatus.PAID,
                databaseOrder.getIdempotencyKey(),
                "provider-trade-paid",
                databaseOrder.getPaymentStartedAt(),
                databaseOrder.getExpiresAt(),
                null,
                NOW_OFFSET,
                3L,
                databaseOrder.getCreatedAt(),
                NOW_OFFSET);
        when(snapshotStore.findRealtimeGuard(any())).thenReturn(Optional.empty());
        MembershipOrderSnapshotWriteCoordinator snapshotWriteCoordinator =
                mock(MembershipOrderSnapshotWriteCoordinator.class);
        when(snapshotWriteCoordinator.patchPaymentAttempt(any())).thenReturn(paidSnapshot);
        MembershipPaymentAttemptServiceImpl service = attemptService(
                transactionService, snapshotStore, snapshotWriteCoordinator);

        assertThatThrownBy(() -> service.start(17L, ORDER_ID))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT));
        verify(transactionService).startOrGet(anyLong(), any(), any());
        verify(snapshotWriteCoordinator).patchPaymentAttempt(any());
    }

    @Test
    void orchestratorDoesNotMutateDatabaseWhenRedisAlreadyContainsCancelledState() {
        MembershipPaymentAttemptTransactionService transactionService =
                mock(MembershipPaymentAttemptTransactionService.class);
        MembershipOrderSnapshotStore snapshotStore = mock(MembershipOrderSnapshotStore.class);
        MembershipOrder databaseOrder = order(null, NOW_OFFSET.plusMinutes(1));
        when(snapshotStore.findRealtimeGuard(new HybridBase64UrlCodec().encode(ORDER_ID)))
                .thenReturn(Optional.of(realtimeGuard(snapshotWithStatus(
                        databaseOrder,
                        MembershipOrderStatus.CANCELLED,
                        2L))));
        MembershipPaymentAttemptServiceImpl service = attemptService(
                transactionService, snapshotStore);

        assertThatThrownBy(() -> service.start(17L, ORDER_ID))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT));
        verifyNoInteractions(transactionService);
    }

    private static MembershipOrderSnapshot snapshotWithStatus(
            MembershipOrder databaseOrder,
            MembershipOrderStatus status,
            long stateVersion) {
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                new HybridBase64UrlCodec().encode(ORDER_ID),
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                status,
                databaseOrder.getIdempotencyKey(),
                status == MembershipOrderStatus.PAID ? "provider-trade-paid" : null,
                databaseOrder.getPaymentStartedAt(),
                databaseOrder.getExpiresAt(),
                null,
                status == MembershipOrderStatus.PAID ? NOW_OFFSET : null,
                stateVersion,
                databaseOrder.getCreatedAt(),
                NOW_OFFSET);
    }

    private static MembershipPaymentAttemptServiceImpl attemptService(
            MembershipPaymentAttemptTransactionService transactionService,
            MembershipOrderSnapshotStore snapshotStore) {
        return attemptService(
                transactionService,
                snapshotStore,
                passthroughCoordinator());
    }

    private static MembershipPaymentAttemptServiceImpl attemptService(
            MembershipPaymentAttemptTransactionService transactionService,
            MembershipOrderSnapshotStore snapshotStore,
            MembershipOrderSnapshotWriteCoordinator snapshotWriteCoordinator) {
        MembershipPaymentProvider provider = mock(MembershipPaymentProvider.class);
        when(provider.createCheckout(any())).thenReturn(
                new PaymentCheckoutResult(null, null, false, null));
        return attemptService(
                transactionService,
                snapshotStore,
                snapshotWriteCoordinator,
                PaymentProviderType.LOCAL_SIMULATOR,
                provider,
                mock(PaymentFactReconciliationService.class));
    }

    private static MembershipPaymentAttemptServiceImpl attemptService(
            MembershipPaymentAttemptTransactionService transactionService,
            MembershipOrderSnapshotStore snapshotStore,
            PaymentProviderType providerType,
            MembershipPaymentProvider provider,
            PaymentFactReconciliationService reconciliationService) {
        return attemptService(
                transactionService,
                snapshotStore,
                passthroughCoordinator(),
                providerType,
                provider,
                reconciliationService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MembershipPaymentAttemptServiceImpl attemptService(
            MembershipPaymentAttemptTransactionService transactionService,
            MembershipOrderSnapshotStore snapshotStore,
            MembershipOrderSnapshotWriteCoordinator snapshotWriteCoordinator,
            PaymentProviderType providerType,
            MembershipPaymentProvider provider,
            PaymentFactReconciliationService reconciliationService) {
        return attemptService(
                transactionService,
                snapshotStore,
                snapshotWriteCoordinator,
                providerType,
                provider,
                reconciliationService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MembershipPaymentAttemptServiceImpl attemptService(
            MembershipPaymentAttemptTransactionService transactionService,
            MembershipOrderSnapshotStore snapshotStore,
            PaymentProviderType providerType,
            MembershipPaymentProvider provider,
            PaymentFactReconciliationService reconciliationService,
            Clock clock) {
        return attemptService(
                transactionService,
                snapshotStore,
                passthroughCoordinator(),
                providerType,
                provider,
                reconciliationService,
                clock);
    }

    private static MembershipPaymentAttemptServiceImpl attemptService(
            MembershipPaymentAttemptTransactionService transactionService,
            MembershipOrderSnapshotStore snapshotStore,
            MembershipOrderSnapshotWriteCoordinator snapshotWriteCoordinator,
            PaymentProviderType providerType,
            MembershipPaymentProvider provider,
            PaymentFactReconciliationService reconciliationService,
            Clock clock) {
        MembershipPaymentProviderRegistry registry =
                mock(MembershipPaymentProviderRegistry.class);
        MembershipPaymentProperties properties = mock(MembershipPaymentProperties.class);
        when(properties.checkoutEnabled()).thenReturn(true);
        when(properties.defaultProvider()).thenReturn(providerType);
        when(registry.getRequired(providerType)).thenReturn(provider);
        when(snapshotStore.patchProviderTradeNo(any(), anyLong(), any()))
                .thenReturn(MembershipProviderTradeNoPatchOutcome.APPLIED);
        return new MembershipPaymentAttemptServiceImpl(
                transactionService,
                snapshotStore,
                snapshotWriteCoordinator,
                registry,
                reconciliationService,
                properties,
                new HybridBase64UrlCodec(),
                clock);
    }

    private static MembershipOrderSnapshotWriteCoordinator passthroughCoordinator() {
        MembershipOrderSnapshotWriteCoordinator coordinator =
                mock(MembershipOrderSnapshotWriteCoordinator.class);
        when(coordinator.putAndGet(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(coordinator.patchPaymentAttempt(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return coordinator;
    }

    private static void stubRealtimeGuards(
            MembershipOrderSnapshotStore snapshotStore,
            MembershipOrderRealtimeGuard afterCheckout) {
        when(snapshotStore.findRealtimeGuard(any())).thenReturn(
                Optional.empty(), Optional.of(afterCheckout));
    }

    private static MembershipOrderRealtimeGuard realtimeGuard(MembershipOrder order) {
        return new MembershipOrderRealtimeGuard(
                new HybridBase64UrlCodec().encode(ORDER_ID),
                17L,
                order.getStatus(),
                order.getExpiresAt(),
                order.getStateVersion());
    }

    private static MembershipOrderRealtimeGuard realtimeGuard(
            MembershipOrderSnapshot snapshot) {
        return new MembershipOrderRealtimeGuard(
                snapshot.orderId(),
                snapshot.loginIdentityId(),
                snapshot.status(),
                snapshot.expiresAt(),
                snapshot.stateVersion());
    }

    private static PaymentCheckoutSubmission checkoutSubmission(
            String timestamp,
            String sign) {
        return checkoutSubmission(timestamp, sign, NOW_OFFSET.plusMinutes(5));
    }

    private static PaymentCheckoutSubmission checkoutSubmission(
            String timestamp,
            String sign,
            OffsetDateTime submitExpiresAt) {
        return new PaymentCheckoutSubmission(
                PaymentProviderType.BAR,
                URI.create("https://ihaveagoddamnplan.com/api/pay/submit"),
                "POST",
                "application/x-www-form-urlencoded",
                submitExpiresAt,
                new PaymentCheckoutSubmissionFields(
                        "1001",
                        new HybridBase64UrlCodec().encode(ORDER_ID),
                        "alipay",
                        "会员模拟支付订单",
                        "20.00",
                        "https://niko000o.site/api/payment/bar/notify",
                        "https://niko000o.site/payment/result",
                        timestamp,
                        "1",
                        "HMAC-SHA256",
                        sign));
    }

    private static MembershipOrder order(
            OffsetDateTime paymentStartedAt,
            OffsetDateTime expiresAt) {
        MembershipOrder order = new MembershipOrder();
        order.setId(ORDER_ID);
        order.setLoginIdentityId(17L);
        order.setMembershipTier(MembershipTier.PLUS);
        order.setPayAmountYuan(new BigDecimal("20.00"));
        order.setPayType("alipay");
        order.setStatus(MembershipOrderStatus.PENDING_PAYMENT);
        order.setIdempotencyKey(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        order.setPaymentStartedAt(paymentStartedAt);
        order.setExpiresAt(expiresAt);
        order.setStateVersion(1L);
        order.setCreatedAt(NOW_OFFSET.minusMinutes(5));
        order.setUpdatedAt(paymentStartedAt);
        return order;
    }

    private static byte[] id(byte value) {
        byte[] id = new byte[16];
        Arrays.fill(id, value);
        return id;
    }
}
