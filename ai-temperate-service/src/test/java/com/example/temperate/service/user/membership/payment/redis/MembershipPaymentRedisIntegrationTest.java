package com.example.temperate.service.user.membership.payment.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackClaim;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackCompletion;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackEnqueueResult;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackSnapshot;
import com.example.temperate.service.user.membership.payment.callback.PaymentProviderResultCompletionAction;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRefundRequiredFinalizationCommand;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRejectedCallbackReleaseCommand;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderPaidCommand;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.persistence.OrderPersistToken;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderResult;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.store.impl.RedisMembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipPaymentMissingSnapshotReleaseOutcome;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCommand;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteMode;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteOutcome;
import com.example.temperate.service.user.membership.payment.store.MembershipProviderTradeNoPatchOutcome;
import com.example.temperate.service.user.membership.payment.store.impl.RedisMembershipPaymentUnappliedCallbackStore;
import com.example.temperate.service.user.membership.payment.store.impl.RedisOrderPersistenceQueue;
import com.example.temperate.service.user.membership.payment.store.impl.RedisPaymentCallbackQueue;
import com.example.temperate.service.user.membership.payment.store.impl.RedisSimulatedPaymentProviderResultStore;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 该集成测试是来验证会员支付回调领取租约和订单版本持久化令牌在真实 Redis 中保持原子、可恢复且不误删新状态。
 */
@Testcontainers(disabledWithoutDocker = true)
final class MembershipPaymentRedisIntegrationTest {

    private static final String REDIS_IMAGE =
            System.getenv().getOrDefault("AIT_TEST_REDIS_IMAGE", "redis:7.4.2-alpine");
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final RedisKeyFactory KEYS = new RedisKeyFactory("test");
    private static final HmacSha256Identifier HMAC = new HmacSha256Identifier(
            "membership-payment-test-secret-0123456789"
                    .getBytes(StandardCharsets.UTF_8));

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RedisMembershipOrderSnapshotStore orderStore;
    private RedisMembershipPaymentUnappliedCallbackStore unappliedCallbackStore;
    private RedisPaymentCallbackQueue callbackQueue;
    private RedisOrderPersistenceQueue persistenceQueue;
    private RedisSimulatedPaymentProviderResultStore providerStore;

    @BeforeAll
    static void connectToRedis() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnectFromRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
        orderStore = new RedisMembershipOrderSnapshotStore(redisTemplate, KEYS);
        unappliedCallbackStore =
                new RedisMembershipPaymentUnappliedCallbackStore(redisTemplate, KEYS);
        callbackQueue = new RedisPaymentCallbackQueue(redisTemplate, KEYS);
        persistenceQueue = new RedisOrderPersistenceQueue(redisTemplate, KEYS);
        providerStore = new RedisSimulatedPaymentProviderResultStore(redisTemplate, KEYS);
    }

    @Test
    void putAndGetReturnsTheMonotonicRedisWinnerWithoutAnExtraRead() {
        String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
        MembershipOrderSnapshot initial = order(orderId);

        assertThat(orderStore.putAndGet(initial)).isEqualTo(initial);

        MembershipOrderSnapshot closing = new MembershipOrderSnapshot(
                initial.schemaVersion(),
                initial.orderId(),
                initial.loginIdentityId(),
                initial.membershipTier(),
                initial.payAmountYuan(),
                initial.payType(),
                MembershipOrderStatus.CLOSING,
                initial.idempotencyKey(),
                initial.providerTradeNo(),
                initial.paymentStartedAt(),
                initial.expiresAt(),
                NOW.plusMinutes(10),
                null,
                2L,
                initial.createdAt(),
                NOW.plusMinutes(5));

        assertThat(orderStore.putAndGet(closing)).isEqualTo(closing);
        assertThat(orderStore.putAndGet(initial)).isEqualTo(closing);
        assertThat(orderStore.putAndGet(closing)).isEqualTo(closing);
    }

    @Test
    void paymentAttemptPatchUsesMonotonicVersionAndReturnsTheCurrentSnapshot() {
        String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
        MembershipOrderSnapshot initial = order(orderId);
        orderStore.put(initial);
        MembershipOrderSnapshot paymentStarted = paymentAttemptSnapshot(
                initial, initial.loginIdentityId(), 2L, NOW.plusSeconds(1));

        assertThat(orderStore.writeAll(List.of(new MembershipOrderSnapshotWriteCommand(
                        MembershipOrderSnapshotWriteMode.PAYMENT_ATTEMPT_PATCH,
                        paymentStarted))))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.outcome())
                            .isEqualTo(MembershipOrderSnapshotWriteOutcome.APPLIED);
                    assertThat(result.snapshot()).isEqualTo(paymentStarted);
                });
        assertThat(orderStore.writeAll(List.of(new MembershipOrderSnapshotWriteCommand(
                        MembershipOrderSnapshotWriteMode.PAYMENT_ATTEMPT_PATCH,
                        paymentStarted))))
                .singleElement()
                .extracting(result -> result.outcome())
                .isEqualTo(MembershipOrderSnapshotWriteOutcome.UNCHANGED);

        MembershipOrderSnapshot stale = paymentAttemptSnapshot(
                initial, initial.loginIdentityId(), 1L, NOW.plusSeconds(1));
        assertThat(orderStore.writeAll(List.of(new MembershipOrderSnapshotWriteCommand(
                        MembershipOrderSnapshotWriteMode.PAYMENT_ATTEMPT_PATCH,
                        stale))))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.outcome())
                            .isEqualTo(MembershipOrderSnapshotWriteOutcome.STALE);
                    assertThat(result.snapshot()).isEqualTo(paymentStarted);
                });

        MembershipOrderSnapshot versionGap = paymentAttemptSnapshot(
                initial, initial.loginIdentityId(), 4L, NOW.plusSeconds(2));
        assertThat(orderStore.writeAll(List.of(new MembershipOrderSnapshotWriteCommand(
                        MembershipOrderSnapshotWriteMode.PAYMENT_ATTEMPT_PATCH,
                        versionGap))))
                .singleElement()
                .extracting(result -> result.outcome())
                .isEqualTo(MembershipOrderSnapshotWriteOutcome.REQUIRES_RESTORE);

        MembershipOrderSnapshot wrongOwner = paymentAttemptSnapshot(
                initial, 18L, 3L, NOW.plusSeconds(2));
        assertThat(orderStore.writeAll(List.of(new MembershipOrderSnapshotWriteCommand(
                        MembershipOrderSnapshotWriteMode.PAYMENT_ATTEMPT_PATCH,
                        wrongOwner))))
                .singleElement()
                .extracting(result -> result.outcome())
                .isEqualTo(MembershipOrderSnapshotWriteOutcome.CONFLICT);
    }

    @Test
    void providerTradePatchIsConditionalAndNeverChangesTheStateVersion() {
        String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
        MembershipOrderSnapshot initial = order(orderId);
        assertThat(orderStore.patchProviderTradeNo(
                        orderId, initial.loginIdentityId(), "provider-trade-1"))
                .isEqualTo(MembershipProviderTradeNoPatchOutcome.MISSING);

        orderStore.put(initial);
        assertThat(orderStore.patchProviderTradeNo(
                        orderId, initial.loginIdentityId(), "provider-trade-1"))
                .isEqualTo(MembershipProviderTradeNoPatchOutcome.APPLIED);
        assertThat(orderStore.patchProviderTradeNo(
                        orderId, initial.loginIdentityId(), "provider-trade-1"))
                .isEqualTo(MembershipProviderTradeNoPatchOutcome.UNCHANGED);
        assertThat(orderStore.patchProviderTradeNo(
                        orderId, initial.loginIdentityId(), "provider-trade-2"))
                .isEqualTo(MembershipProviderTradeNoPatchOutcome.CONFLICT);
        assertThat(orderStore.find(orderId)).get().satisfies(snapshot -> {
            assertThat(snapshot.providerTradeNo()).isEqualTo("provider-trade-1");
            assertThat(snapshot.stateVersion()).isEqualTo(1L);
        });

        orderStore.startClosing(orderId, NOW.plusMinutes(10), NOW.plusMinutes(5));
        assertThat(orderStore.patchProviderTradeNo(
                        orderId, initial.loginIdentityId(), "provider-trade-1"))
                .isEqualTo(MembershipProviderTradeNoPatchOutcome.CONFLICT);
    }

    @Test
    void oneCoordinatorBatchOfPutAndGetResultsPreservesAllOneHundredNinetyTwoItems() {
        HybridBase64UrlCodec codec = new HybridBase64UrlCodec();
        List<MembershipOrderSnapshot> snapshots = IntStream.range(0, 192)
                .mapToObj(index -> order(codec.encode(ByteBuffer.allocate(16)
                        .putLong(11L)
                        .putLong(index + 1L)
                        .array())))
                .toList();

        List<MembershipOrderSnapshot> first = orderStore.putAndGetAll(snapshots);
        List<MembershipOrderSnapshot> replay = orderStore.putAndGetAll(snapshots);

        assertThat(first).containsExactlyElementsOf(snapshots);
        assertThat(replay).containsExactlyElementsOf(snapshots);
        assertThat(first.get(191).orderId()).isEqualTo(snapshots.get(191).orderId());
    }

    @Test
    void coordinatorRejectsOneHundredNinetyThreeItemsBeforeSubmittingRedisCommands() {
        HybridBase64UrlCodec codec = new HybridBase64UrlCodec();
        List<MembershipOrderSnapshot> snapshots = IntStream.range(0, 193)
                .mapToObj(index -> order(codec.encode(ByteBuffer.allocate(16)
                        .putLong(13L)
                        .putLong(index + 1L)
                        .array())))
                .toList();

        assertThatThrownBy(() -> orderStore.putAndGetAll(snapshots))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 192 snapshots");
    }

    @Test
    void coordinatorPipelineUsesEvalShaAndReloadsAfterScriptCacheFlush() {
        HybridBase64UrlCodec codec = new HybridBase64UrlCodec();
        List<MembershipOrderSnapshot> snapshots = IntStream.range(0, 64)
                .mapToObj(index -> order(codec.encode(ByteBuffer.allocate(16)
                        .putLong(12L)
                        .putLong(index + 1L)
                        .array())))
                .toList();
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.scriptingCommands().scriptFlush();
            connection.serverCommands().resetConfigStats();
        }

        assertThat(orderStore.putAndGetAll(snapshots)).containsExactlyElementsOf(snapshots);
        assertThat(commandCalls("eval")).isZero();
        assertThat(commandCalls("evalsha")).isEqualTo(64L);

        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.scriptingCommands().scriptFlush();
        }
        assertThat(orderStore.putAndGetAll(snapshots)).containsExactlyElementsOf(snapshots);
        assertThat(commandCalls("eval")).isZero();
        assertThat(commandCalls("evalsha")).isEqualTo(192L);
    }

    @Test
    void oldPersistenceCompletionCannotDeleteNewerPaidSnapshot() {
        String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
        orderStore.put(order(orderId));

        MembershipOrderTransitionResult closing = orderStore.startClosing(
                orderId, NOW.plusMinutes(10), NOW.plusMinutes(5));
        assertThat(closing.applied()).isTrue();
        assertThat(closing.stateVersion()).isEqualTo(2L);

        List<OrderPersistToken> closingClaim = persistenceQueue.claim(
                100, NOW.plusMinutes(5).toInstant().toEpochMilli());
        assertThat(closingClaim).singleElement()
                .extracting(OrderPersistToken::stateVersion)
                .isEqualTo(2L);

        MembershipOrderTransitionResult paid = orderStore.markPaid(
                orderId,
                "AaAjECcaAQGqi_h2Rl1PiQ",
                "provider-trade-1",
                new BigDecimal("20.00"),
                NOW.plusMinutes(6));
        assertThat(paid.applied()).isTrue();
        assertThat(paid.stateVersion()).isEqualTo(3L);

        assertThat(persistenceQueue.complete(closingClaim)).isEqualTo(1);
        assertThat(orderStore.find(orderId)).get()
                .extracting(MembershipOrderSnapshot::stateVersion)
                .isEqualTo(3L);

        List<OrderPersistToken> paidClaim = persistenceQueue.claim(
                100, NOW.plusMinutes(6).toInstant().toEpochMilli());
        assertThat(paidClaim).singleElement()
                .extracting(OrderPersistToken::stateVersion)
                .isEqualTo(3L);
        assertThat(persistenceQueue.complete(paidClaim)).isEqualTo(1);
        assertThat(orderStore.find(orderId)).isEmpty();
    }

    @Test
    void fiveHundredOrdersUseBoundedPipelinesAndRemainIdempotent() {
        HybridBase64UrlCodec codec = new HybridBase64UrlCodec();
        List<MembershipOrderSnapshot> snapshots = IntStream.range(0, 500)
                .mapToObj(index -> order(codec.encode(ByteBuffer.allocate(16)
                        .putLong(1L)
                        .putLong(index + 1L)
                        .array())))
                .toList();
        orderStore.putAll(snapshots);
        assertThat(orderStore.findAll(snapshots.stream()
                        .map(MembershipOrderSnapshot::orderId)
                        .toList()))
                .hasSize(500)
                .containsKeys(
                        snapshots.get(0).orderId(),
                        snapshots.get(127).orderId(),
                        snapshots.get(128).orderId(),
                        snapshots.get(499).orderId());

        List<MembershipOrderPaidCommand> commands = IntStream.range(0, 500)
                .mapToObj(index -> new MembershipOrderPaidCommand(
                        codec.encode(ByteBuffer.allocate(16)
                                .putLong(2L)
                                .putLong(index + 1L)
                                .array()),
                        snapshots.get(index).orderId(),
                        "provider-trade-" + index,
                        new BigDecimal("20.00"),
                        NOW.plusMinutes(1),
                        NOW.plusMinutes(1)))
                .toList();

        assertThat(orderStore.markPaidAll(commands.subList(0, 50)))
                .hasSize(50)
                .allSatisfy((callbackId, result) -> assertThat(result.applied()).isTrue());
        assertThat(orderStore.markPaidAll(commands))
                .hasSize(500)
                .satisfies(results -> {
                    assertThat(results.values())
                            .filteredOn(result -> "ALREADY_APPLIED".equals(
                                    result.outcome().name()))
                            .hasSize(50);
                    assertThat(results.values())
                            .filteredOn(MembershipOrderTransitionResult::applied)
                            .hasSize(450);
                });
        assertThat(orderStore.markPaidAll(commands))
                .hasSize(500)
                .allSatisfy((callbackId, result) ->
                        assertThat(result.outcome().name()).isEqualTo("ALREADY_APPLIED"));
    }

    @Test
    void fiveHundredCallbackCompletionsRejectOldClaimsAcrossPipelineBoundaries() {
        HybridBase64UrlCodec codec = new HybridBase64UrlCodec();
        Map<String, String> orderByCallback = IntStream.range(0, 500)
                .mapToObj(index -> {
                    String orderId = codec.encode(ByteBuffer.allocate(16)
                            .putLong(3L)
                            .putLong(index + 1L)
                            .array());
                    String callbackId = codec.encode(ByteBuffer.allocate(16)
                            .putLong(4L)
                            .putLong(index + 1L)
                            .array());
                    PaymentCallbackSnapshot snapshot = callback(
                            callbackId,
                            orderId,
                            "provider-batch-" + index,
                            HMAC.identify("callback-batch-" + index).value());
                    assertThat(callbackQueue.enqueue(
                                    snapshot,
                                    HmacIdentifier.fromProtectedValue(
                                            snapshot.idempotencyFingerprint()),
                                    HMAC.identify(snapshot.providerTradeNo()))
                            .enqueued()).isTrue();
                    return Map.entry(callbackId, orderId);
                })
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue));
        long firstClaimedAt = NOW.toInstant().toEpochMilli();
        List<PaymentCallbackClaim> oldClaims = callbackQueue.claim(
                500, firstClaimedAt);
        assertThat(oldClaims).hasSize(500);
        assertThat(callbackQueue.recoverTimedOut(
                firstClaimedAt,
                500,
                firstClaimedAt + 1_000L)).isEqualTo(500);
        List<PaymentCallbackClaim> currentClaims = callbackQueue.claim(
                500, firstClaimedAt + 2_000L);
        assertThat(currentClaims).hasSize(500);

        List<PaymentCallbackCompletion> oldCompletions = oldClaims.stream()
                .map(claim -> new PaymentCallbackCompletion(
                        claim, orderByCallback.get(claim.callbackId())))
                .toList();
        List<PaymentCallbackCompletion> currentCompletions = currentClaims.stream()
                .map(claim -> new PaymentCallbackCompletion(
                        claim, orderByCallback.get(claim.callbackId())))
                .toList();
        assertThat(callbackQueue.complete(oldCompletions)).isZero();
        assertThat(callbackQueue.complete(currentCompletions)).isEqualTo(500);
        assertThat(callbackQueue.complete(currentCompletions)).isZero();
    }

    @Test
    void callbackFingerprintDeduplicatesTransportAndOldLeaseCannotCompleteNewClaim() {
        String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
        HmacIdentifier fingerprint = HMAC.identify("same-business-callback");
        PaymentCallbackSnapshot first = callback(
                "AaAjECcaAQGqi_h2Rl1PiQ", orderId, fingerprint.value());
        PaymentCallbackSnapshot replay = callback(
                "AaAjECcaAQGqi_h2Rl1Pig", orderId, fingerprint.value());

        HmacIdentifier providerFingerprint = HMAC.identify("provider-trade-1");
        PaymentCallbackEnqueueResult created = callbackQueue.enqueue(
                first, fingerprint, providerFingerprint);
        PaymentCallbackEnqueueResult duplicate = callbackQueue.enqueue(
                replay, fingerprint, providerFingerprint);
        assertThat(created.enqueued()).isTrue();
        assertThat(duplicate.enqueued()).isFalse();
        assertThat(duplicate.callbackId()).isEqualTo(first.callbackId());

        long firstClaimTime = NOW.toInstant().toEpochMilli();
        List<PaymentCallbackClaim> firstClaim = callbackQueue.claim(100, firstClaimTime);
        assertThat(firstClaim).singleElement()
                .extracting(PaymentCallbackClaim::callbackId)
                .isEqualTo(first.callbackId());

        assertThat(callbackQueue.recoverTimedOut(
                firstClaimTime, 100, firstClaimTime + 1_000L)).isEqualTo(1);
        List<PaymentCallbackClaim> secondClaim = callbackQueue.claim(
                100, firstClaimTime + 2_000L);
        assertThat(callbackQueue.complete(completions(firstClaim, orderId))).isZero();
        assertThat(callbackQueue.findAll(List.of(first.callbackId())))
                .containsKey(first.callbackId());
        assertThat(callbackQueue.complete(completions(secondClaim, orderId))).isEqualTo(1);
        assertThat(callbackQueue.findAll(List.of(first.callbackId()))).isEmpty();
    }

    @Test
    void orderAndProviderTradeIdentitiesEachDeduplicateAtomically() {
        String firstOrderId = "AaAjECcaAQGqi_h2Rl1PiA";
        String secondOrderId = "AaAjECcaAQGqi_h2Rl1Piw";
        PaymentCallbackSnapshot first = callback(
                "AaAjECcaAQGqi_h2Rl1PiQ",
                firstOrderId,
                "provider-trade-1",
                HMAC.identify("payload-1").value());
        PaymentCallbackSnapshot sameOrderDifferentTrade = callback(
                "AaAjECcaAQGqi_h2Rl1Pig",
                firstOrderId,
                "provider-trade-2",
                HMAC.identify("payload-2").value());
        PaymentCallbackSnapshot differentOrderSameTrade = callback(
                "AaAjECcaAQGqi_h2Rl1PjA",
                secondOrderId,
                "provider-trade-1",
                HMAC.identify("payload-3").value());

        assertThat(callbackQueue.enqueue(
                first,
                HmacIdentifier.fromProtectedValue(first.idempotencyFingerprint()),
                HMAC.identify(first.providerTradeNo())).enqueued()).isTrue();
        assertThat(callbackQueue.enqueue(
                sameOrderDifferentTrade,
                HmacIdentifier.fromProtectedValue(
                        sameOrderDifferentTrade.idempotencyFingerprint()),
                HMAC.identify(sameOrderDifferentTrade.providerTradeNo())).enqueued()).isFalse();
        assertThat(callbackQueue.enqueue(
                differentOrderSameTrade,
                HmacIdentifier.fromProtectedValue(
                        differentOrderSameTrade.idempotencyFingerprint()),
                HMAC.identify(differentOrderSameTrade.providerTradeNo())).enqueued()).isFalse();
        assertThat(callbackQueue.claim(10, NOW.toInstant().toEpochMilli()))
                .extracting(PaymentCallbackClaim::callbackId)
                .containsExactly(first.callbackId());
    }

    @Test
    void rejectedCompletionRestoresExplicitUnpaidProviderResult() {
        String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
        PaymentCallbackSnapshot callback = callback(
                "AaAjECcaAQGqi_h2Rl1PiQ",
                orderId,
                "provider-trade-after-dedupe-expiry",
                HMAC.identify("payload-after-dedupe-expiry").value());

        assertThat(callbackQueue.enqueue(
                callback,
                HmacIdentifier.fromProtectedValue(callback.idempotencyFingerprint()),
                HMAC.identify(callback.providerTradeNo())).enqueued()).isTrue();
        assertThat(providerStore.find(orderId)).get()
                .extracting(SimulatedPaymentProviderResult::callbackId)
                .isEqualTo(callback.callbackId());

        List<PaymentCallbackClaim> claims = callbackQueue.claim(
                10, NOW.toInstant().toEpochMilli());
        assertThat(callbackQueue.complete(List.of(new PaymentCallbackCompletion(
                claims.get(0), orderId,
                PaymentProviderResultCompletionAction.RESET_UNPAID)))).isEqualTo(1);
        assertThat(providerStore.find(orderId)).get().satisfies(result -> {
            assertThat(result.status()).isEqualTo(SimulatedPaymentProviderStatus.UNPAID);
            assertThat(result.callbackId()).isNull();
            assertThat(result.providerTradeNo()).isNull();
            assertThat(result.paidAmountYuan()).isNull();
        });
    }

    @Test
    void refundRequiredFinalizationClosesOrderAndKeepsClaimForRefundRetry() {
        String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
        String callbackId = "AaAjECcaAQGqi_h2Rl1PiQ";
        OffsetDateTime hardCloseAt = NOW.plusMinutes(10);
        PaymentCallbackSnapshot callback = callback(
                callbackId,
                orderId,
                HMAC.identify("refund-required-finalization").value());
        orderStore.put(order(orderId));
        orderStore.startClosing(orderId, hardCloseAt, NOW.plusMinutes(5));
        assertThat(callbackQueue.enqueue(
                callback,
                HmacIdentifier.fromProtectedValue(callback.idempotencyFingerprint()),
                HMAC.identify(callback.providerTradeNo())).enqueued()).isTrue();
        PaymentCallbackClaim claim = callbackQueue.claim(
                10, hardCloseAt.toInstant().toEpochMilli()).getFirst();

        MembershipOrderTransitionResult result = unappliedCallbackStore
                .finalizeRefundRequired(List.of(
                        new MembershipPaymentRefundRequiredFinalizationCommand(
                                claim,
                                orderId,
                                callback.providerTradeNo(),
                                hardCloseAt,
                                hardCloseAt)))
                .get(callbackId);

        assertThat(result.applied()).isTrue();
        assertThat(result.status()).isEqualTo(MembershipOrderStatus.CLOSED);
        assertThat(result.stateVersion()).isEqualTo(3L);
        assertThat(orderStore.find(orderId)).get().satisfies(snapshot -> {
            assertThat(snapshot.status()).isEqualTo(MembershipOrderStatus.CLOSED);
            assertThat(snapshot.closingDeadlineAt()).isEqualTo(hardCloseAt);
            assertThat(snapshot.updatedAt()).isEqualTo(hardCloseAt);
        });
        assertThat(orderStore.callbackInProgress(orderId)).isFalse();
        assertThat(callbackQueue.processingSize()).isEqualTo(1L);
        assertThat(providerStore.find(orderId)).get().satisfies(provider -> {
            assertThat(provider.status()).isEqualTo(SimulatedPaymentProviderStatus.UNPAID);
            assertThat(provider.callbackId()).isNull();
        });
        MembershipOrderTransitionResult replay = unappliedCallbackStore
                .finalizeRefundRequired(List.of(
                        new MembershipPaymentRefundRequiredFinalizationCommand(
                                claim,
                                orderId,
                                callback.providerTradeNo(),
                                hardCloseAt,
                                hardCloseAt.plusSeconds(1))))
                .get(callbackId);
        assertThat(replay.outcome().name()).isEqualTo("ALREADY_APPLIED");
        assertThat(replay.stateVersion()).isEqualTo(3L);
        assertThat(orderStore.find(orderId)).get()
                .extracting(MembershipOrderSnapshot::updatedAt)
                .isEqualTo(hardCloseAt);
    }

    @Test
    void refundRequiredFinalizationCanClosePendingOrderAtHardBoundary() {
        String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
        String callbackId = "AaAjECcaAQGqi_h2Rl1PiQ";
        OffsetDateTime hardCloseAt = NOW.plusMinutes(10);
        PaymentCallbackSnapshot callback = callback(
                callbackId,
                orderId,
                HMAC.identify("pending-refund-required-finalization").value());
        orderStore.put(order(orderId));
        callbackQueue.enqueue(
                callback,
                HmacIdentifier.fromProtectedValue(callback.idempotencyFingerprint()),
                HMAC.identify(callback.providerTradeNo()));
        PaymentCallbackClaim claim = callbackQueue.claim(
                10, hardCloseAt.toInstant().toEpochMilli()).getFirst();

        MembershipOrderTransitionResult result = unappliedCallbackStore
                .finalizeRefundRequired(List.of(
                        new MembershipPaymentRefundRequiredFinalizationCommand(
                                claim,
                                orderId,
                                callback.providerTradeNo(),
                                hardCloseAt,
                                hardCloseAt)))
                .get(callbackId);

        assertThat(result.applied()).isTrue();
        assertThat(result.stateVersion()).isEqualTo(2L);
        assertThat(orderStore.find(orderId)).get().satisfies(snapshot -> {
            assertThat(snapshot.status()).isEqualTo(MembershipOrderStatus.CLOSED);
            assertThat(snapshot.closingDeadlineAt()).isEqualTo(hardCloseAt);
            assertThat(snapshot.updatedAt()).isEqualTo(hardCloseAt);
        });
    }

    @Test
    void missingSnapshotRefundReleaseCleansOwnedFactsButKeepsProcessingClaim() {
        String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
        String callbackId = "AaAjECcaAQGqi_h2Rl1PiQ";
        OffsetDateTime hardCloseAt = NOW.plusMinutes(10);
        PaymentCallbackSnapshot callback = callback(
                callbackId,
                orderId,
                HMAC.identify("missing-refund-required-release").value());
        assertThat(callbackQueue.enqueue(
                callback,
                HmacIdentifier.fromProtectedValue(callback.idempotencyFingerprint()),
                HMAC.identify(callback.providerTradeNo())).enqueued()).isTrue();
        PaymentCallbackClaim claim = callbackQueue.claim(
                10, hardCloseAt.toInstant().toEpochMilli()).getFirst();
        MembershipPaymentRefundRequiredFinalizationCommand command =
                new MembershipPaymentRefundRequiredFinalizationCommand(
                        claim,
                        orderId,
                        callback.providerTradeNo(),
                        hardCloseAt,
                        hardCloseAt);

        assertThat(unappliedCallbackStore.releaseMissingRefundRequired(List.of(command)))
                .containsEntry(
                        callbackId,
                        MembershipPaymentMissingSnapshotReleaseOutcome.RELEASED);

        assertThat(orderStore.find(orderId)).isEmpty();
        assertThat(orderStore.callbackInProgress(orderId)).isFalse();
        assertThat(callbackQueue.processingSize()).isEqualTo(1L);
        assertThat(providerStore.find(orderId)).get().satisfies(provider -> {
            assertThat(provider.status()).isEqualTo(SimulatedPaymentProviderStatus.UNPAID);
            assertThat(provider.callbackId()).isNull();
        });
        assertThat(unappliedCallbackStore.releaseMissingRefundRequired(List.of(command)))
                .containsEntry(
                        callbackId,
                        MembershipPaymentMissingSnapshotReleaseOutcome.ALREADY_RELEASED);
        assertThat(callbackQueue.processingSize()).isEqualTo(1L);
    }

    @Test
    void fourHundredMissingSnapshotRefundClaimsConvergeAcrossPipelineBoundaries() {
        HybridBase64UrlCodec codec = new HybridBase64UrlCodec();
        Map<String, PaymentCallbackSnapshot> callbacks = IntStream.range(0, 400)
                .mapToObj(index -> {
                    String orderId = codec.encode(ByteBuffer.allocate(16)
                            .putLong(21L)
                            .putLong(index + 1L)
                            .array());
                    String callbackId = codec.encode(ByteBuffer.allocate(16)
                            .putLong(22L)
                            .putLong(index + 1L)
                            .array());
                    PaymentCallbackSnapshot callback = callback(
                            callbackId,
                            orderId,
                            "provider-missing-refund-" + index,
                            HMAC.identify("missing-refund-batch-" + index).value());
                    assertThat(callbackQueue.enqueue(
                                    callback,
                                    HmacIdentifier.fromProtectedValue(
                                            callback.idempotencyFingerprint()),
                                    HMAC.identify(callback.providerTradeNo()))
                            .enqueued()).isTrue();
                    return callback;
                })
                .collect(Collectors.toMap(
                        PaymentCallbackSnapshot::callbackId,
                        callback -> callback));
        long claimedAt = NOW.plusMinutes(10).toInstant().toEpochMilli();
        List<PaymentCallbackClaim> claims = callbackQueue.claim(400, claimedAt);
        assertThat(claims).hasSize(400);
        List<MembershipPaymentRefundRequiredFinalizationCommand> commands = claims.stream()
                .map(claim -> {
                    PaymentCallbackSnapshot callback = callbacks.get(claim.callbackId());
                    return new MembershipPaymentRefundRequiredFinalizationCommand(
                            claim,
                            callback.orderId(),
                            callback.providerTradeNo(),
                            NOW.plusMinutes(10),
                            NOW.plusMinutes(10));
                })
                .toList();

        assertThat(unappliedCallbackStore.releaseMissingRefundRequired(commands))
                .hasSize(400)
                .allSatisfy((callbackId, outcome) -> assertThat(outcome)
                        .isEqualTo(MembershipPaymentMissingSnapshotReleaseOutcome.RELEASED));
        assertThat(redisTemplate.opsForZSet().zCard(KEYS.paymentCallbackReadyKey())).isZero();
        assertThat(callbackQueue.processingSize()).isEqualTo(400L);

        List<PaymentCallbackCompletion> completions = claims.stream()
                .map(claim -> new PaymentCallbackCompletion(
                        claim,
                        callbacks.get(claim.callbackId()).orderId(),
                        PaymentProviderResultCompletionAction.RESET_UNPAID))
                .toList();
        assertThat(callbackQueue.complete(completions)).isEqualTo(400);
        assertThat(redisTemplate.opsForZSet().zCard(KEYS.paymentCallbackReadyKey())).isZero();
        assertThat(callbackQueue.processingSize()).isZero();
        assertThat(callbackQueue.findAll(callbacks.keySet())).isEmpty();
    }

    @Test
    void missingSnapshotRefundReleaseRejectsStaleClaimAndForeignCallbackFacts() {
        String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
        String callbackId = "AaAjECcaAQGqi_h2Rl1PiQ";
        OffsetDateTime hardCloseAt = NOW.plusMinutes(10);
        PaymentCallbackSnapshot callback = callback(
                callbackId,
                orderId,
                HMAC.identify("missing-refund-required-conflict").value());
        callbackQueue.enqueue(
                callback,
                HmacIdentifier.fromProtectedValue(callback.idempotencyFingerprint()),
                HMAC.identify(callback.providerTradeNo()));
        PaymentCallbackClaim claim = callbackQueue.claim(
                10, hardCloseAt.toInstant().toEpochMilli()).getFirst();
        PaymentCallbackClaim stale = new PaymentCallbackClaim(
                callbackId, claim.claimedAtEpochMillis() + 1L);

        assertThat(unappliedCallbackStore.releaseMissingRefundRequired(List.of(
                new MembershipPaymentRefundRequiredFinalizationCommand(
                        stale,
                        orderId,
                        callback.providerTradeNo(),
                        hardCloseAt,
                        hardCloseAt))))
                .containsEntry(
                        callbackId,
                        MembershipPaymentMissingSnapshotReleaseOutcome.CLAIM_MISMATCH);

        redisTemplate.opsForValue().set(
                KEYS.membershipOrderCallbackMarkerKey(new MembershipOrderRedisId(orderId)),
                "AaAjECcaAQGqi_h2Rl1Pig");
        MembershipPaymentRefundRequiredFinalizationCommand command =
                new MembershipPaymentRefundRequiredFinalizationCommand(
                        claim,
                        orderId,
                        callback.providerTradeNo(),
                        hardCloseAt,
                        hardCloseAt);
        assertThat(unappliedCallbackStore.releaseMissingRefundRequired(List.of(command)))
                .containsEntry(
                        callbackId,
                        MembershipPaymentMissingSnapshotReleaseOutcome.CALLBACK_CONFLICT);

        redisTemplate.opsForValue().set(
                KEYS.membershipOrderCallbackMarkerKey(new MembershipOrderRedisId(orderId)),
                callbackId);
        redisTemplate.opsForHash().put(
                KEYS.simulatedPaymentProviderResultKey(new MembershipOrderRedisId(orderId)),
                "callbackId",
                "AaAjECcaAQGqi_h2Rl1Pig");
        assertThat(unappliedCallbackStore.releaseMissingRefundRequired(List.of(command)))
                .containsEntry(
                        callbackId,
                        MembershipPaymentMissingSnapshotReleaseOutcome.CALLBACK_CONFLICT);
        assertThat(orderStore.callbackInProgress(orderId)).isTrue();
        assertThat(callbackQueue.processingSize()).isEqualTo(1L);
    }

    @Test
    void refundRequiredFinalizationRejectsActiveOrderBeforeHardBoundary() {
        String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
        String callbackId = "AaAjECcaAQGqi_h2Rl1PiQ";
        OffsetDateTime hardCloseAt = NOW.plusMinutes(10);
        PaymentCallbackSnapshot callback = callback(
                callbackId,
                orderId,
                HMAC.identify("early-refund-required-finalization").value());
        orderStore.put(order(orderId));
        callbackQueue.enqueue(
                callback,
                HmacIdentifier.fromProtectedValue(callback.idempotencyFingerprint()),
                HMAC.identify(callback.providerTradeNo()));
        PaymentCallbackClaim claim = callbackQueue.claim(
                10, NOW.plusMinutes(9).toInstant().toEpochMilli()).getFirst();

        MembershipOrderTransitionResult result = unappliedCallbackStore
                .finalizeRefundRequired(List.of(
                        new MembershipPaymentRefundRequiredFinalizationCommand(
                                claim,
                                orderId,
                                callback.providerTradeNo(),
                                hardCloseAt,
                                hardCloseAt.minusNanos(1_000L))))
                .get(callbackId);

        assertThat(result.outcome().name()).isEqualTo("TOO_EARLY");
        assertThat(orderStore.callbackInProgress(orderId)).isTrue();
        assertThat(orderStore.find(orderId)).get()
                .extracting(MembershipOrderSnapshot::status)
                .isEqualTo(MembershipOrderStatus.PENDING_PAYMENT);
    }

    @Test
    void rejectedReleaseClearsOwnMarkerWithoutCompletingClaimOrChangingOrder() {
        String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
        String callbackId = "AaAjECcaAQGqi_h2Rl1PiQ";
        PaymentCallbackSnapshot callback = callback(
                callbackId,
                orderId,
                HMAC.identify("rejected-release").value());
        orderStore.put(order(orderId));
        assertThat(callbackQueue.enqueue(
                callback,
                HmacIdentifier.fromProtectedValue(callback.idempotencyFingerprint()),
                HMAC.identify(callback.providerTradeNo())).enqueued()).isTrue();
        PaymentCallbackClaim claim = callbackQueue.claim(
                10, NOW.plusSeconds(1).toInstant().toEpochMilli()).getFirst();

        assertThat(unappliedCallbackStore.releaseRejected(List.of(
                new MembershipPaymentRejectedCallbackReleaseCommand(claim, orderId))))
                .containsExactly(callbackId);

        assertThat(orderStore.callbackInProgress(orderId)).isFalse();
        assertThat(callbackQueue.processingSize()).isEqualTo(1L);
        assertThat(orderStore.find(orderId)).get().satisfies(snapshot -> {
            assertThat(snapshot.status()).isEqualTo(MembershipOrderStatus.PENDING_PAYMENT);
            assertThat(snapshot.stateVersion()).isEqualTo(1L);
        });
        assertThat(providerStore.find(orderId)).get().satisfies(provider -> {
            assertThat(provider.status()).isEqualTo(SimulatedPaymentProviderStatus.UNPAID);
            assertThat(provider.callbackId()).isNull();
        });
    }

    @Test
    void staleClaimAndForeignMarkerCannotFinalizeRefundRequiredOrder() {
        String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
        String callbackId = "AaAjECcaAQGqi_h2Rl1PiQ";
        OffsetDateTime hardCloseAt = NOW.plusMinutes(10);
        PaymentCallbackSnapshot callback = callback(
                callbackId,
                orderId,
                HMAC.identify("refund-required-conflict").value());
        orderStore.put(order(orderId));
        orderStore.startClosing(orderId, hardCloseAt, NOW.plusMinutes(5));
        callbackQueue.enqueue(
                callback,
                HmacIdentifier.fromProtectedValue(callback.idempotencyFingerprint()),
                HMAC.identify(callback.providerTradeNo()));
        PaymentCallbackClaim claim = callbackQueue.claim(
                10, hardCloseAt.toInstant().toEpochMilli()).getFirst();
        PaymentCallbackClaim stale = new PaymentCallbackClaim(
                callbackId, claim.claimedAtEpochMillis() + 1L);

        MembershipOrderTransitionResult staleResult = unappliedCallbackStore
                .finalizeRefundRequired(List.of(
                        new MembershipPaymentRefundRequiredFinalizationCommand(
                                stale,
                                orderId,
                                callback.providerTradeNo(),
                                hardCloseAt,
                                hardCloseAt)))
                .get(callbackId);
        assertThat(staleResult.outcome().name()).isEqualTo("NOT_ALLOWED");

        redisTemplate.opsForValue().set(
                KEYS.membershipOrderCallbackMarkerKey(new MembershipOrderRedisId(orderId)),
                "AaAjECcaAQGqi_h2Rl1Pig");
        MembershipOrderTransitionResult markerResult = unappliedCallbackStore
                .finalizeRefundRequired(List.of(
                        new MembershipPaymentRefundRequiredFinalizationCommand(
                                claim,
                                orderId,
                                callback.providerTradeNo(),
                                hardCloseAt,
                                hardCloseAt)))
                .get(callbackId);

        assertThat(markerResult.outcome().name()).isEqualTo("CALLBACK_IN_PROGRESS");
        assertThat(orderStore.find(orderId)).get()
                .extracting(MembershipOrderSnapshot::status)
                .isEqualTo(MembershipOrderStatus.CLOSING);
    }

    @Test
    void unknownProviderResultCannotCloseOrderButExplicitUnpaidCan() {
        String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
        orderStore.put(order(orderId));
        orderStore.startClosing(orderId, NOW.plusMinutes(10), NOW.plusMinutes(5));
        providerStore.put(new SimulatedPaymentProviderResult(
                SimulatedPaymentProviderResult.CURRENT_SCHEMA_VERSION,
                orderId,
                SimulatedPaymentProviderStatus.UNKNOWN,
                null,
                null,
                null,
                null,
                NOW.plusMinutes(10)));

        MembershipOrderTransitionResult unknown =
                orderStore.finalizeClosing(orderId, NOW.plusMinutes(11));

        assertThat(unknown.outcome().name()).isEqualTo("PROVIDER_STATUS_UNSAFE");
        assertThat(orderStore.find(orderId)).get()
                .extracting(MembershipOrderSnapshot::status)
                .isEqualTo(MembershipOrderStatus.CLOSING);

        providerStore.put(new SimulatedPaymentProviderResult(
                SimulatedPaymentProviderResult.CURRENT_SCHEMA_VERSION,
                orderId,
                SimulatedPaymentProviderStatus.UNPAID,
                null,
                null,
                null,
                null,
                NOW.plusMinutes(11)));
        MembershipOrderTransitionResult unpaid =
                orderStore.finalizeClosing(orderId, NOW.plusMinutes(11));

        assertThat(unpaid.applied()).isTrue();
        assertThat(unpaid.status()).isEqualTo(MembershipOrderStatus.CLOSED);
    }

    @Test
    void duplicateAfterCompletionDoesNotRecreateMarkerOrReadyWork() {
        String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
        PaymentCallbackSnapshot first = callback(
                "AaAjECcaAQGqi_h2Rl1PiQ",
                orderId,
                HMAC.identify("first-callback").value());
        PaymentCallbackSnapshot second = callback(
                "AaAjECcaAQGqi_h2Rl1Pig",
                orderId,
                HMAC.identify("second-callback").value());
        callbackQueue.enqueue(
                first,
                HmacIdentifier.fromProtectedValue(first.idempotencyFingerprint()),
                HMAC.identify(first.providerTradeNo()));
        List<PaymentCallbackClaim> firstClaim = callbackQueue.claim(
                100, NOW.toInstant().toEpochMilli());
        assertThat(callbackQueue.complete(completions(firstClaim, orderId))).isEqualTo(1);
        assertThat(orderStore.callbackInProgress(orderId)).isFalse();

        PaymentCallbackEnqueueResult duplicate = callbackQueue.enqueue(
                second,
                HmacIdentifier.fromProtectedValue(second.idempotencyFingerprint()),
                HMAC.identify(second.providerTradeNo()));

        assertThat(duplicate.enqueued()).isFalse();
        assertThat(callbackQueue.claim(
                100, NOW.plusSeconds(1).toInstant().toEpochMilli())).isEmpty();
        assertThat(orderStore.callbackInProgress(orderId)).isFalse();
    }

    @Test
    void terminalOrdersNeverRegressAndClosingCannotBeCancelled() {
        String cancelledId = "AaAjECcaAQGqi_h2Rl1PiA";
        orderStore.put(order(cancelledId));
        MembershipOrderTransitionResult cancelled =
                orderStore.cancel(cancelledId, NOW.plusMinutes(1));
        MembershipOrderTransitionResult latePaid = orderStore.markPaid(
                cancelledId,
                "AaAjECcaAQGqi_h2Rl1PiQ",
                "provider-trade-1",
                new BigDecimal("20.00"),
                NOW.plusMinutes(2));

        assertThat(cancelled.status()).isEqualTo(MembershipOrderStatus.CANCELLED);
        assertThat(latePaid.outcome().name()).isEqualTo("LATE_TERMINAL");
        assertThat(orderStore.find(cancelledId)).get()
                .extracting(MembershipOrderSnapshot::status)
                .isEqualTo(MembershipOrderStatus.CANCELLED);

        String closingId = "AaAjECcaAQGqi_h2Rl1Pig";
        orderStore.put(order(closingId));
        orderStore.startClosing(closingId, NOW.plusMinutes(10), NOW.plusMinutes(5));
        MembershipOrderTransitionResult rejectedCancel =
                orderStore.cancel(closingId, NOW.plusMinutes(6));

        assertThat(rejectedCancel.outcome().name()).isEqualTo("NOT_ALLOWED");
        assertThat(orderStore.find(closingId)).get()
                .extracting(MembershipOrderSnapshot::status)
                .isEqualTo(MembershipOrderStatus.CLOSING);
    }

    @ParameterizedTest
    @EnumSource(
            value = MembershipOrderStatus.class,
            names = {"PAID", "CANCELLED", "CLOSED"})
    void persistedTerminalOrderLeavesNeitherSnapshotNorQueueMember(
            MembershipOrderStatus terminalStatus) {
        String orderId = switch (terminalStatus) {
            case PAID -> "AaAjECcaAQGqi_h2Rl1PiA";
            case CANCELLED -> "AaAjECcaAQGqi_h2Rl1PiQ";
            case CLOSED -> "AaAjECcaAQGqi_h2Rl1Pig";
            default -> throw new IllegalArgumentException("Terminal status is required.");
        };
        orderStore.put(order(orderId));

        switch (terminalStatus) {
            case PAID -> orderStore.markPaid(
                    orderId,
                    "AaAjECcaAQGqi_h2Rl1Piw",
                    "provider-terminal-paid",
                    new BigDecimal("20.00"),
                    NOW.plusMinutes(1));
            case CANCELLED -> orderStore.cancel(orderId, NOW.plusMinutes(1));
            case CLOSED -> {
                orderStore.startClosing(
                        orderId, NOW.plusMinutes(2), NOW.plusMinutes(1));
                providerStore.put(new SimulatedPaymentProviderResult(
                        SimulatedPaymentProviderResult.CURRENT_SCHEMA_VERSION,
                        orderId,
                        SimulatedPaymentProviderStatus.UNPAID,
                        null,
                        null,
                        null,
                        null,
                        NOW.plusMinutes(2)));
                orderStore.finalizeClosing(orderId, NOW.plusMinutes(2));
            }
            default -> throw new IllegalArgumentException("Terminal status is required.");
        }

        assertThat(orderStore.find(orderId)).get()
                .extracting(MembershipOrderSnapshot::status)
                .isEqualTo(terminalStatus);
        assertThat(persistenceQueue.dirtySize()).isPositive();

        List<OrderPersistToken> tokens = persistenceQueue.claim(
                100, NOW.plusMinutes(3).toInstant().toEpochMilli());
        assertThat(tokens).isNotEmpty();
        assertThat(persistenceQueue.dirtySize()).isZero();
        assertThat(persistenceQueue.processingSize()).isEqualTo(tokens.size());

        assertThat(persistenceQueue.complete(tokens)).isEqualTo(tokens.size());
        assertThat(persistenceQueue.dirtySize()).isZero();
        assertThat(persistenceQueue.processingSize()).isZero();
        assertThat(orderStore.find(orderId)).isEmpty();
    }

    private static MembershipOrderSnapshot order(String orderId) {
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                orderId,
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                MembershipOrderStatus.PENDING_PAYMENT,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                null,
                NOW.plusMinutes(5),
                null,
                null,
                1L,
                NOW,
                NOW);
    }

    private static MembershipOrderSnapshot paymentAttemptSnapshot(
            MembershipOrderSnapshot source,
            long loginIdentityId,
            long stateVersion,
            OffsetDateTime paymentStartedAt) {
        return new MembershipOrderSnapshot(
                source.schemaVersion(),
                source.orderId(),
                loginIdentityId,
                source.membershipTier(),
                source.payAmountYuan(),
                source.payType(),
                source.status(),
                source.idempotencyKey(),
                source.providerTradeNo(),
                paymentStartedAt,
                source.expiresAt(),
                source.closingDeadlineAt(),
                source.paidAt(),
                stateVersion,
                source.createdAt(),
                paymentStartedAt);
    }

    private static PaymentCallbackSnapshot callback(
            String callbackId,
            String orderId,
            String fingerprint) {
        return callback(callbackId, orderId, "provider-trade-1", fingerprint);
    }

    private static PaymentCallbackSnapshot callback(
            String callbackId,
            String orderId,
            String providerTradeNo,
            String fingerprint) {
        return new PaymentCallbackSnapshot(
                PaymentCallbackSnapshot.CURRENT_SCHEMA_VERSION,
                callbackId,
                orderId,
                "merchant-test",
                providerTradeNo,
                "channel-trade-1",
                "alipay",
                "TRADE_SUCCESS",
                new BigDecimal("20.00"),
                NOW,
                NOW,
                NOW.toEpochSecond(),
                fingerprint,
                "r6J7mFDrb9KH83hLrUkYQgt4AAJwxBkLBzsP4efjEKk");
    }

    private static List<PaymentCallbackCompletion> completions(
            List<PaymentCallbackClaim> claims,
            String orderId) {
        return claims.stream()
                .map(claim -> new PaymentCallbackCompletion(claim, orderId))
                .toList();
    }

    private static long commandCalls(String command) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String statistics = connection.serverCommands()
                    .info("commandstats")
                    .getProperty("cmdstat_" + command, "calls=0");
            return java.util.Arrays.stream(statistics.split(","))
                    .filter(field -> field.startsWith("calls="))
                    .map(field -> field.substring("calls=".length()))
                    .mapToLong(Long::parseLong)
                    .findFirst()
                    .orElse(0L);
        }
    }
}
