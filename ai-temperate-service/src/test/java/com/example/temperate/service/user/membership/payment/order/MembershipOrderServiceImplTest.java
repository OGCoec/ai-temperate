package com.example.temperate.service.user.membership.payment.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.MembershipExpirationService;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.order.impl.MembershipOrderServiceImpl;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderReference;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckPublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentFinalCheckScheduler;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipSupersededClosePublisher;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCoordinator;
import com.example.temperate.service.user.membership.purchase.MembershipPlanPriceService;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionDecision;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionPolicy;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionRejectionReason;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionType;
import com.example.temperate.service.user.membership.purchase.MembershipUpgradeQuote;
import com.example.temperate.service.user.membership.purchase.MembershipUpgradeQuoteService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 该单元测试是来约束会员订单创建的数据库幂等恢复、Redis/Rabbit 补偿边界和取消 marker 冲突，不连接外部基础设施。
 */
final class MembershipOrderServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final byte[] ORDER_ID = id((byte) 7);

    private MembershipExpirationService expirationService;
    private UserMembershipQuotaMapper quotaMapper;
    private MembershipTransitionPolicy transitionPolicy;
    private MembershipPlanPriceService priceService;
    private MembershipUpgradeQuoteService quoteService;
    private MembershipOrderMapper orderMapper;
    private MembershipOrderCreationTransactionService creationTransactionService;
    private MembershipOrderCreationLockService creationLockService;
    private HybridSemaphoreIdWorker idWorker;
    private MembershipOrderSnapshotStore snapshotStore;
    private MembershipOrderSnapshotWriteCoordinator snapshotWriteCoordinator;
    private MembershipPaymentProviderRegistry providerRegistry;
    private MembershipPaymentProvider provider;
    private MembershipClosingCheckPublisher closingPublisher;
    private MembershipPaymentFinalCheckScheduler finalCheckScheduler;
    private MembershipSupersededClosePublisher supersededClosePublisher;
    private MembershipOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        expirationService = mock(MembershipExpirationService.class);
        quotaMapper = mock(UserMembershipQuotaMapper.class);
        transitionPolicy = mock(MembershipTransitionPolicy.class);
        priceService = mock(MembershipPlanPriceService.class);
        quoteService = mock(MembershipUpgradeQuoteService.class);
        orderMapper = mock(MembershipOrderMapper.class);
        creationTransactionService = mock(MembershipOrderCreationTransactionService.class);
        creationLockService = mock(MembershipOrderCreationLockService.class);
        when(creationLockService.execute(anyLong(), any()))
                .thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(1)
                        .get());
        idWorker = mock(HybridSemaphoreIdWorker.class);
        snapshotStore = mock(MembershipOrderSnapshotStore.class);
        snapshotWriteCoordinator = mock(MembershipOrderSnapshotWriteCoordinator.class);
        when(snapshotWriteCoordinator.putAndGet(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        providerRegistry = mock(MembershipPaymentProviderRegistry.class);
        provider = mock(MembershipPaymentProvider.class);
        when(providerRegistry.getRequired(any())).thenReturn(provider);
        closingPublisher = mock(MembershipClosingCheckPublisher.class);
        finalCheckScheduler = mock(MembershipPaymentFinalCheckScheduler.class);
        supersededClosePublisher = mock(MembershipSupersededClosePublisher.class);
        service = new MembershipOrderServiceImpl(
                expirationService,
                quotaMapper,
                transitionPolicy,
                priceService,
                quoteService,
                orderMapper,
                creationTransactionService,
                creationLockService,
                idWorker,
                new HybridBase64UrlCodec(),
                snapshotStore,
                snapshotWriteCoordinator,
                providerRegistry,
                closingPublisher,
                supersededClosePublisher,
                finalCheckScheduler,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void freeUserCreatesProviderNeutralPendingOrderAndSchedulesFinalCheck() {
        UserMembershipQuota quota = quota(MembershipTier.FREE);
        when(quotaMapper.findByLoginIdentityId(17L)).thenReturn(quota);
        when(transitionPolicy.evaluate(any())).thenReturn(new MembershipTransitionDecision(
                MembershipTier.FREE,
                MembershipTier.PLUS,
                MembershipTransitionType.NEW_PURCHASE,
                MembershipTransitionRejectionReason.NONE));
        when(priceService.getRequiredPrice(MembershipTier.PLUS))
                .thenReturn(new BigDecimal("20.00"));
        when(idWorker.nextId()).thenReturn(ORDER_ID);
        when(creationTransactionService.createOrGet(any()))
                .thenAnswer(invocation -> new MembershipOrderCreationResult(
                        invocation.getArgument(0), true));
        MembershipOrderResult result = service.create(
                17L,
                new MembershipOrderCreateCommand(
                        MembershipTier.PLUS,
                        "alipay",
                        UUID.fromString("550e8400-e29b-41d4-a716-446655440000")));

        assertThat(result.created()).isTrue();
        assertThat(result.snapshot().status()).isEqualTo(MembershipOrderStatus.PENDING_PAYMENT);
        assertThat(result.snapshot().payAmountYuan()).isEqualByComparingTo("20.00");
        assertThat(result.snapshot().providerTradeNo()).isNull();
        verify(expirationService).expireIfDue(17L);
        verify(snapshotWriteCoordinator).putAndGet(result.snapshot());
        verify(provider, never()).initializeOrder(any());
        verify(finalCheckScheduler).schedulePending(
                result.snapshot().orderId(), result.snapshot().expiresAt());
    }

    @Test
    void concurrentHigherRedisVersionPreventsStaleProviderInitializationAndPublish() {
        when(quotaMapper.findByLoginIdentityId(17L)).thenReturn(quota(MembershipTier.FREE));
        when(transitionPolicy.evaluate(any())).thenReturn(new MembershipTransitionDecision(
                MembershipTier.FREE,
                MembershipTier.PLUS,
                MembershipTransitionType.NEW_PURCHASE,
                MembershipTransitionRejectionReason.NONE));
        when(priceService.getRequiredPrice(MembershipTier.PLUS))
                .thenReturn(new BigDecimal("20.00"));
        when(idWorker.nextId()).thenReturn(ORDER_ID);
        when(creationTransactionService.createOrGet(any()))
                .thenAnswer(invocation -> new MembershipOrderCreationResult(
                        invocation.getArgument(0), true));
        when(snapshotWriteCoordinator.putAndGet(any())).thenAnswer(invocation -> {
            MembershipOrderSnapshot database = invocation.getArgument(0);
            return new MembershipOrderSnapshot(
                    database.schemaVersion(),
                    database.orderId(),
                    database.loginIdentityId(),
                    database.membershipTier(),
                    database.payAmountYuan(),
                    database.payType(),
                    MembershipOrderStatus.CLOSING,
                    database.idempotencyKey(),
                    database.providerTradeNo(),
                    database.paymentStartedAt(),
                    database.expiresAt(),
                    database.expiresAt().plusMinutes(5),
                    null,
                    database.stateVersion() + 1L,
                    database.createdAt(),
                    database.updatedAt().plusNanos(1_000L));
        });

        MembershipOrderResult result = service.create(
                17L,
                new MembershipOrderCreateCommand(
                        MembershipTier.PLUS,
                        "alipay",
                        UUID.fromString("4b6a6142-6b43-44d8-a53d-df2fe483b95e")));

        assertThat(result.snapshot().status()).isEqualTo(MembershipOrderStatus.CLOSING);
        assertThat(result.snapshot().stateVersion()).isEqualTo(2L);
        verify(providerRegistry, never()).getRequired(any());
        verify(finalCheckScheduler, never()).schedulePending(anyString(), any());
    }

    @Test
    void creationNormalizesStateMachineTimestampsToPostgresMicrosecondPrecision() {
        Instant preciseNow = Instant.parse("2026-08-20T12:00:00.123456789Z");
        service = new MembershipOrderServiceImpl(
                expirationService,
                quotaMapper,
                transitionPolicy,
                priceService,
                quoteService,
                orderMapper,
                creationTransactionService,
                creationLockService,
                idWorker,
                new HybridBase64UrlCodec(),
                snapshotStore,
                snapshotWriteCoordinator,
                providerRegistry,
                closingPublisher,
                supersededClosePublisher,
                finalCheckScheduler,
                properties(),
                Clock.fixed(preciseNow, ZoneOffset.UTC));
        when(quotaMapper.findByLoginIdentityId(17L)).thenReturn(quota(MembershipTier.FREE));
        when(transitionPolicy.evaluate(any())).thenReturn(new MembershipTransitionDecision(
                MembershipTier.FREE,
                MembershipTier.GO,
                MembershipTransitionType.NEW_PURCHASE,
                MembershipTransitionRejectionReason.NONE));
        when(priceService.getRequiredPrice(MembershipTier.GO))
                .thenReturn(new BigDecimal("0.30"));
        when(idWorker.nextId()).thenReturn(ORDER_ID);
        when(creationTransactionService.createOrGet(any()))
                .thenAnswer(invocation -> new MembershipOrderCreationResult(
                        invocation.getArgument(0), true));
        MembershipOrderResult result = service.create(
                17L,
                new MembershipOrderCreateCommand(
                        MembershipTier.GO,
                        "alipay",
                        UUID.fromString("3d0dc5d6-8d85-4fa5-8288-6a8af490eb61")));

        assertThat(result.snapshot().createdAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-20T12:00:00.123456Z"));
        assertThat(result.snapshot().expiresAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-20T12:05:00.123456Z"));
    }

    @Test
    void databaseWinnerCanBeRecoveredAfterFirstRedisFailureWithSameIdempotencyKey() {
        UserMembershipQuota quota = quota(MembershipTier.FREE);
        when(quotaMapper.findByLoginIdentityId(17L)).thenReturn(quota);
        when(transitionPolicy.evaluate(any())).thenReturn(new MembershipTransitionDecision(
                MembershipTier.FREE,
                MembershipTier.GO,
                MembershipTransitionType.NEW_PURCHASE,
                MembershipTransitionRejectionReason.NONE));
        when(priceService.getRequiredPrice(MembershipTier.GO))
                .thenReturn(new BigDecimal("0.30"));
        when(idWorker.nextId()).thenReturn(ORDER_ID);
        when(creationTransactionService.createOrGet(any()))
                .thenAnswer(invocation -> new MembershipOrderCreationResult(
                        invocation.getArgument(0), false));
        when(snapshotWriteCoordinator.putAndGet(any()))
                .thenThrow(new MembershipPaymentInfrastructureException("redis unavailable"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        MembershipOrderCreateCommand command = new MembershipOrderCreateCommand(
                MembershipTier.GO,
                "alipay",
                UUID.fromString("4b6a6142-6b43-44d8-a53d-df2fe483b95e"));

        assertThatThrownBy(() -> service.create(17L, command))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_REDIS_UNAVAILABLE));

        MembershipOrderResult recovered = service.create(17L, command);
        assertThat(recovered.created()).isFalse();
        verify(finalCheckScheduler).schedulePending(
                recovered.snapshot().orderId(), recovered.snapshot().expiresAt());
    }

    @Test
    void rabbitPublishFailureCanBeRecoveredByTheSameIdempotentRequest() {
        UserMembershipQuota quota = quota(MembershipTier.FREE);
        when(quotaMapper.findByLoginIdentityId(17L)).thenReturn(quota);
        when(transitionPolicy.evaluate(any())).thenReturn(new MembershipTransitionDecision(
                MembershipTier.FREE,
                MembershipTier.GO,
                MembershipTransitionType.NEW_PURCHASE,
                MembershipTransitionRejectionReason.NONE));
        when(priceService.getRequiredPrice(MembershipTier.GO))
                .thenReturn(new BigDecimal("0.30"));
        when(idWorker.nextId()).thenReturn(ORDER_ID);
        when(creationTransactionService.createOrGet(any()))
                .thenAnswer(invocation -> new MembershipOrderCreationResult(
                        invocation.getArgument(0), false));
        doThrow(new MembershipPaymentException(
                        MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_RABBIT_UNAVAILABLE,
                        "confirm failed"))
                .doNothing()
                .when(finalCheckScheduler)
                .schedulePending(anyString(), any());
        MembershipOrderCreateCommand command = new MembershipOrderCreateCommand(
                MembershipTier.GO,
                "alipay",
                UUID.fromString("96b9fe15-45a2-4f48-a5d8-07d81f2bcd73"));

        assertThatThrownBy(() -> service.create(17L, command))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_RABBIT_UNAVAILABLE));
        assertThat(service.create(17L, command).created()).isFalse();
    }

    @Test
    void differentIdempotencyKeyCreatesReplacementEvenWhenOldClosePublishFails() {
        when(quotaMapper.findByLoginIdentityId(17L)).thenReturn(quota(MembershipTier.FREE));
        when(transitionPolicy.evaluate(any())).thenReturn(new MembershipTransitionDecision(
                MembershipTier.FREE,
                MembershipTier.PLUS,
                MembershipTransitionType.NEW_PURCHASE,
                MembershipTransitionRejectionReason.NONE));
        when(priceService.getRequiredPrice(MembershipTier.PLUS))
                .thenReturn(new BigDecimal("20.00"));
        when(idWorker.nextId()).thenReturn(ORDER_ID);
        MembershipOrder active = activeOrder(
                id((byte) 6),
                MembershipOrderStatus.PENDING_PAYMENT,
                NOW.atOffset(ZoneOffset.UTC).minusSeconds(5),
                "LIUHAO:TRADE:old-provider-trade");
        when(creationTransactionService.createOrGet(any()))
                .thenReturn(new MembershipOrderCreationResult(active, false, true));
        when(snapshotStore.supersedeForReplacement(
                        new HybridBase64UrlCodec().encode(active.getId()),
                        true,
                        NOW.atOffset(ZoneOffset.UTC)))
                .thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.CLOSED,
                        3L));
        when(creationTransactionService.replaceActive(any()))
                .thenAnswer(invocation -> new MembershipOrderCreationResult(
                        invocation.<MembershipOrderReplacementCommand>getArgument(0).candidate(),
                        true));
        doThrow(new IllegalStateException("confirm unavailable"))
                .when(supersededClosePublisher)
                .publish(anyString(), anyInt(), any());

        MembershipOrderResult result = service.create(
                17L,
                new MembershipOrderCreateCommand(
                        MembershipTier.PLUS,
                        "wxpay",
                        UUID.fromString("4b6a6142-6b43-44d8-a53d-df2fe483b95e")));

        assertThat(result.created()).isTrue();
        assertThat(result.snapshot().status()).isEqualTo(MembershipOrderStatus.PENDING_PAYMENT);
        verify(creationTransactionService).replaceActive(any());
        verify(supersededClosePublisher).publish(
                new HybridBase64UrlCodec().encode(active.getId()), 0, Duration.ZERO);
        verify(finalCheckScheduler).schedulePending(
                result.snapshot().orderId(), result.snapshot().expiresAt());
    }

    @Test
    void replacementStopsForTheShortWindowWhileCallbackMarkerIsActive() {
        when(quotaMapper.findByLoginIdentityId(17L)).thenReturn(quota(MembershipTier.FREE));
        when(transitionPolicy.evaluate(any())).thenReturn(new MembershipTransitionDecision(
                MembershipTier.FREE,
                MembershipTier.PLUS,
                MembershipTransitionType.NEW_PURCHASE,
                MembershipTransitionRejectionReason.NONE));
        when(priceService.getRequiredPrice(MembershipTier.PLUS))
                .thenReturn(new BigDecimal("20.00"));
        when(idWorker.nextId()).thenReturn(ORDER_ID);
        MembershipOrder active = activeOrder(
                id((byte) 6),
                MembershipOrderStatus.PENDING_PAYMENT,
                NOW.atOffset(ZoneOffset.UTC).minusSeconds(5),
                "LIUHAO:TRADE:old-provider-trade");
        when(creationTransactionService.createOrGet(any()))
                .thenReturn(new MembershipOrderCreationResult(active, false, true));
        when(snapshotStore.supersedeForReplacement(anyString(), anyBoolean(), any()))
                .thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.CALLBACK_IN_PROGRESS,
                        MembershipOrderStatus.PENDING_PAYMENT,
                        2L));

        assertThatThrownBy(() -> service.create(
                        17L,
                        new MembershipOrderCreateCommand(
                                MembershipTier.PLUS,
                                "alipay",
                                UUID.fromString("4b6a6142-6b43-44d8-a53d-df2fe483b95e"))))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_CALLBACK_IN_PROGRESS));
        verify(creationTransactionService, never()).replaceActive(any());
        verify(supersededClosePublisher, never()).publish(anyString(), anyInt(), any());
    }

    @Test
    void cancelRejectsOrderWhileCallbackMarkerIsInProgress() {
        MembershipOrderSnapshot snapshot = snapshot(17L, MembershipOrderStatus.PENDING_PAYMENT);
        when(snapshotStore.find(snapshot.orderId())).thenReturn(Optional.of(snapshot));
        when(snapshotStore.cancel(anyString(), any())).thenReturn(
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.CALLBACK_IN_PROGRESS,
                        MembershipOrderStatus.PENDING_PAYMENT,
                        1L));

        assertThatThrownBy(() -> service.cancel(17L, ORDER_ID))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_CALLBACK_IN_PROGRESS));
    }

    @Test
    void startedExternalPaymentCancelEntersClosingAndPublishesImmediateClose() {
        MembershipOrderSnapshot snapshot = externalSnapshot(
                MembershipOrderStatus.PENDING_PAYMENT,
                NOW.atOffset(ZoneOffset.UTC));
        when(snapshotStore.find(snapshot.orderId())).thenReturn(Optional.of(snapshot));
        when(snapshotStore.startClosing(
                snapshot.orderId(),
                NOW.atOffset(ZoneOffset.UTC).plusMinutes(10),
                NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC).plusMinutes(10)))
                .thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.CLOSING,
                        2L));

        MembershipOrderResult result = service.cancel(17L, ORDER_ID);

        assertThat(result.snapshot().status()).isEqualTo(MembershipOrderStatus.CLOSING);
        assertThat(result.snapshot().closingDeadlineAt())
                .isEqualTo(NOW.atOffset(ZoneOffset.UTC).plusMinutes(10));
        verify(closingPublisher).publishNext(
                snapshot.orderId(), 0, 0, Duration.ZERO);
        verify(snapshotStore, never()).cancel(anyString(), any());
    }

    @Test
    void externalPaymentNotStartedYetStillCancelsLocallyWithoutProviderClose() {
        MembershipOrderSnapshot snapshot = externalSnapshot(
                MembershipOrderStatus.PENDING_PAYMENT,
                null);
        when(snapshotStore.find(snapshot.orderId())).thenReturn(Optional.of(snapshot));
        when(snapshotStore.cancel(snapshot.orderId(), NOW.atOffset(ZoneOffset.UTC)))
                .thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.CANCELLED,
                        2L));

        MembershipOrderResult result = service.cancel(17L, ORDER_ID);

        assertThat(result.snapshot().status()).isEqualTo(MembershipOrderStatus.CANCELLED);
        verify(closingPublisher, never()).publishNext(
                anyString(), anyInt(), anyInt(), any());
        verify(snapshotStore, never()).startClosing(anyString(), any(), any());
    }

    @Test
    void startedExternalPaymentCancelStopsWhenCallbackMarkerWins() {
        MembershipOrderSnapshot snapshot = externalSnapshot(
                MembershipOrderStatus.PENDING_PAYMENT,
                NOW.atOffset(ZoneOffset.UTC));
        when(snapshotStore.find(snapshot.orderId())).thenReturn(Optional.of(snapshot));
        when(snapshotStore.startClosing(anyString(), any(), any(), any()))
                .thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.CALLBACK_IN_PROGRESS,
                        MembershipOrderStatus.PENDING_PAYMENT,
                        1L));

        assertThatThrownBy(() -> service.cancel(17L, ORDER_ID))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_CALLBACK_IN_PROGRESS));

        verify(closingPublisher, never()).publishNext(
                anyString(), anyInt(), anyInt(), any());
    }

    @Test
    void repeatedClosingCancelRepublishesIdempotentStageZero() {
        MembershipOrderSnapshot snapshot = externalSnapshot(
                MembershipOrderStatus.CLOSING,
                NOW.atOffset(ZoneOffset.UTC));
        when(snapshotStore.find(snapshot.orderId())).thenReturn(Optional.of(snapshot));
        when(snapshotStore.callbackInProgress(snapshot.orderId())).thenReturn(false);

        MembershipOrderResult result = service.cancel(17L, ORDER_ID);

        assertThat(result.snapshot()).isSameAs(snapshot);
        verify(closingPublisher).publishNext(snapshot.orderId(), 0, 0, Duration.ZERO);
        verify(snapshotStore, never()).startClosing(anyString(), any(), any());
        verify(snapshotStore, never()).cancel(anyString(), any());
    }

    @Test
    void redisSnapshotOwnedByAnotherUserIsIndistinguishableFromMissing() {
        MembershipOrderSnapshot snapshot = snapshot(88L, MembershipOrderStatus.PENDING_PAYMENT);
        when(snapshotStore.find(snapshot.orderId())).thenReturn(Optional.of(snapshot));

        assertThatThrownBy(() -> service.getOwned(17L, ORDER_ID))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_NOT_FOUND));
    }

    @Test
    void educationAndTeamTiersCannotEnterPersonalPaymentOrders() {
        UUID idempotencyKey =
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        assertThatThrownBy(() -> service.create(
                        17L,
                        new MembershipOrderCreateCommand(
                                MembershipTier.EDU, "alipay", idempotencyKey)))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.INPUT_INVALID));
        assertThatThrownBy(() -> service.create(
                        17L,
                        new MembershipOrderCreateCommand(
                                MembershipTier.TEAM, "alipay", idempotencyKey)))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.INPUT_INVALID));
    }

    @Test
    void personalUpgradeUsesTrustedPaidHistoryAndCurrentExpiration() {
        UserMembershipQuota quota = quota(MembershipTier.PLUS);
        quota.setMembershipExpiresAt(NOW.atOffset(ZoneOffset.UTC).plusDays(15));
        when(quotaMapper.findByLoginIdentityId(17L)).thenReturn(quota);
        when(transitionPolicy.evaluate(any())).thenReturn(new MembershipTransitionDecision(
                MembershipTier.PLUS,
                MembershipTier.PRO,
                MembershipTransitionType.UPGRADE,
                MembershipTransitionRejectionReason.NONE));
        MembershipOrder latestPaid = new MembershipOrder();
        latestPaid.setPaidAt(NOW.atOffset(ZoneOffset.UTC).minusDays(15));
        latestPaid.setPayAmountYuan(new BigDecimal("20.00"));
        when(orderMapper.findLatestPaidOrder(
                        17L, MembershipTier.PLUS))
                .thenReturn(latestPaid);
        when(quoteService.quote(any())).thenReturn(new MembershipUpgradeQuote(
                MembershipTier.PLUS,
                MembershipTier.PRO,
                new BigDecimal("60.00"),
                new BigDecimal("10.00"),
                new BigDecimal("50.00"),
                30,
                15,
                NOW.atOffset(ZoneOffset.UTC)));
        when(idWorker.nextId()).thenReturn(ORDER_ID);
        when(creationTransactionService.createOrGet(any()))
                .thenAnswer(invocation -> new MembershipOrderCreationResult(
                        invocation.getArgument(0), true));
        MembershipOrderResult result = service.create(
                17L,
                new MembershipOrderCreateCommand(
                        MembershipTier.PRO,
                        "alipay",
                        UUID.fromString("4b6a6142-6b43-44d8-a53d-df2fe483b95e")));

        assertThat(result.snapshot().payAmountYuan()).isEqualByComparingTo("50.00");
        verify(quoteService).quote(any());
    }

    @Test
    void upgradeWithoutTrustedPaidHistoryReturnsControlledConflict() {
        UserMembershipQuota quota = quota(MembershipTier.PLUS);
        OffsetDateTime expiresAt = NOW.atOffset(ZoneOffset.UTC).plusDays(15);
        quota.setMembershipExpiresAt(expiresAt);
        when(quotaMapper.findByLoginIdentityId(17L)).thenReturn(quota);
        when(transitionPolicy.evaluate(any())).thenReturn(new MembershipTransitionDecision(
                MembershipTier.PLUS,
                MembershipTier.PRO,
                MembershipTransitionType.UPGRADE,
                MembershipTransitionRejectionReason.NONE));
        when(orderMapper.findLatestPaidOrder(
                        17L, MembershipTier.PLUS))
                .thenReturn(null);

        assertThatThrownBy(() -> service.create(
                        17L,
                        new MembershipOrderCreateCommand(
                                MembershipTier.PRO,
                                "alipay",
                                UUID.fromString("4b6a6142-6b43-44d8-a53d-df2fe483b95e"))))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_UPGRADE_HISTORY_MISSING));
    }

    private static UserMembershipQuota quota(MembershipTier tier) {
        UserMembershipQuota quota = new UserMembershipQuota();
        quota.setId(3L);
        quota.setLoginIdentityId(17L);
        quota.setMembershipTier(tier.ordinal());
        quota.setQuotaBalanceMinor(100L);
        return quota;
    }

    private static MembershipOrderSnapshot snapshot(
            long userId,
            MembershipOrderStatus status) {
        String orderId = new HybridBase64UrlCodec().encode(ORDER_ID);
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                orderId,
                userId,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                status,
                UUID.fromString("96b9fe15-45a2-4f48-a5d8-07d81f2bcd73"),
                null,
                NOW.atOffset(ZoneOffset.UTC).plusMinutes(5),
                null,
                null,
                1L,
                NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC));
    }

    private static MembershipOrderSnapshot externalSnapshot(
            MembershipOrderStatus status,
            OffsetDateTime paymentStartedAt) {
        String orderId = new HybridBase64UrlCodec().encode(ORDER_ID);
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                orderId,
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                status,
                UUID.fromString("b8fd3d92-012c-4fd9-bdab-23f1e6f08fa9"),
                null,
                paymentStartedAt,
                now.plusMinutes(5),
                status == MembershipOrderStatus.CLOSING ? now.plusMinutes(5) : null,
                null,
                status == MembershipOrderStatus.CLOSING ? 2L : 1L,
                now,
                now);
    }

    private static MembershipOrder activeOrder(
            byte[] orderId,
            MembershipOrderStatus status,
            OffsetDateTime paymentStartedAt,
            String providerTradeNo) {
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);
        MembershipOrder order = new MembershipOrder();
        order.setId(orderId);
        order.setLoginIdentityId(17L);
        order.setMembershipTier(MembershipTier.PLUS);
        order.setPayAmountYuan(new BigDecimal("20.00"));
        order.setPayType("alipay");
        order.setStatus(status);
        order.setIdempotencyKey(
                UUID.fromString("b8fd3d92-012c-4fd9-bdab-23f1e6f08fa9"));
        order.setProviderTradeNo(providerTradeNo);
        order.setPaymentStartedAt(paymentStartedAt);
        order.setExpiresAt(now.plusMinutes(5));
        order.setStateVersion(status == MembershipOrderStatus.CLOSING ? 3L : 2L);
        order.setCreatedAt(now.minusMinutes(1));
        order.setUpdatedAt(now.minusSeconds(5));
        return order;
    }

    private static byte[] id(byte value) {
        byte[] id = new byte[16];
        Arrays.fill(id, value);
        return id;
    }

    private static MembershipPaymentProperties properties() {
        return new MembershipPaymentProperties(
                true,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                new MembershipPaymentProperties.Simulator(
                        false, "", "", Duration.ofMinutes(5), 16_384, false),
                new MembershipPaymentProperties.Callback(
                        5_000L,
                        100,
                        20,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(10),
                        Duration.ofHours(6)),
                new MembershipPaymentProperties.OrderPersist(
                        5_000L,
                        100,
                        20,
                        Duration.ofSeconds(60),
                        Duration.ofMillis(100)),
                new MembershipPaymentProperties.Rabbit(
                        List.of(10_000L, 10_000L, 10_000L, 15_000L, 15_000L,
                                30_000L, 30_000L, 60_000L, 120_000L),
                        List.of(30_000L, 30_000L, 60_000L, 60_000L, 120_000L),
                        Duration.ofSeconds(30),
                        3));
    }
}
