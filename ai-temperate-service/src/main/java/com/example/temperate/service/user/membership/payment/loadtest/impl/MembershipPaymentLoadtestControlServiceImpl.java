package com.example.temperate.service.user.membership.payment.loadtest.impl;

import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackBatchService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackClaim;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestControlService;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestFaultGate;
import com.example.temperate.service.user.membership.payment.persistence.MembershipOrderBatchPersistenceService;
import com.example.temperate.service.user.membership.payment.persistence.OrderPersistToken;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitSender;
import com.example.temperate.service.user.membership.payment.store.OrderPersistenceQueue;
import com.example.temperate.service.user.membership.payment.store.PaymentCallbackQueue;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 该实现是来复用正式队列、Rabbit 发送器和 RedisKeyFactory，为本机恢复、重试和终态清理测试提供受控探针。
 *
 * <p>恢复领取时间固定回拨到配置租约之外；批量检查只返回存在性和队列大小，不直接修改数据库、不拼接或暴露 Redis Key。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment.loadtest",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentLoadtestControlServiceImpl
        implements MembershipPaymentLoadtestControlService {

    private static final long STALE_MARGIN_MILLIS = 1_000L;
    private static final int MAX_ARTIFACT_ORDERS = 250;

    private final PaymentCallbackQueue callbackQueue;
    private final OrderPersistenceQueue orderQueue;
    private final PaymentCallbackBatchService callbackBatchService;
    private final MembershipOrderBatchPersistenceService orderBatchService;
    private final MembershipPaymentProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final Clock clock;
    private final MembershipPaymentRabbitSender rabbitSender;
    private final HybridSemaphoreIdWorker idWorker;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final MembershipPaymentLoadtestFaultGate faultGate;

    public MembershipPaymentLoadtestControlServiceImpl(
            PaymentCallbackQueue callbackQueue,
            OrderPersistenceQueue orderQueue,
            PaymentCallbackBatchService callbackBatchService,
            MembershipOrderBatchPersistenceService orderBatchService,
            MembershipPaymentProperties properties,
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            Clock clock,
            MembershipPaymentRabbitSender rabbitSender,
            HybridSemaphoreIdWorker idWorker,
            HybridBase64UrlCodec base64UrlCodec,
            MembershipPaymentLoadtestFaultGate faultGate) {
        this.callbackQueue = Objects.requireNonNull(callbackQueue);
        this.orderQueue = Objects.requireNonNull(orderQueue);
        this.callbackBatchService = Objects.requireNonNull(callbackBatchService);
        this.orderBatchService = Objects.requireNonNull(orderBatchService);
        this.properties = Objects.requireNonNull(properties);
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.clock = Objects.requireNonNull(clock);
        this.rabbitSender = Objects.requireNonNull(rabbitSender);
        this.idWorker = Objects.requireNonNull(idWorker);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
        this.faultGate = Objects.requireNonNull(faultGate);
    }

    @Override
    public RecoveryProbe recoverOneCallbackProcessing() {
        long now = clock.millis();
        long staleClaimedAt = now
                - properties.callback().processingTimeout().toMillis()
                - STALE_MARGIN_MILLIS;
        List<PaymentCallbackClaim> claims = callbackQueue.claim(1, staleClaimedAt);
        requireOneClaim(claims.size(), "callback");
        int recovered = callbackQueue.recoverTimedOut(
                now - properties.callback().processingTimeout().toMillis(),
                1,
                now);
        requireOneRecovery(recovered, "callback");
        callbackBatchService.flushOneRun();
        return new RecoveryProbe(
                claims.size(), recovered, callbackQueue.processingSize());
    }

    @Override
    public RecoveryProbe recoverOneOrderProcessing() {
        long now = clock.millis();
        long staleClaimedAt = now
                - properties.orderPersist().processingTimeout().toMillis()
                - STALE_MARGIN_MILLIS;
        List<OrderPersistToken> tokens = orderQueue.claim(1, staleClaimedAt);
        requireOneClaim(tokens.size(), "order persistence");
        int recovered = orderQueue.recoverTimedOut(
                now - properties.orderPersist().processingTimeout().toMillis(),
                1,
                now);
        requireOneRecovery(recovered, "order persistence");
        orderBatchService.flushOneRun();
        return new RecoveryProbe(tokens.size(), recovered, orderQueue.processingSize());
    }

    @Override
    public void flushOneRun() {
        callbackBatchService.flushOneRun();
        orderBatchService.flushOneRun();
    }

    @Override
    public String publishRabbitRetryProbe(String orderId) {
        return publishRabbitProbe(orderId, MembershipPaymentRabbitNames.LOADTEST_RETRY_EVENT);
    }

    @Override
    public String publishRabbitPoisonProbe(String orderId) {
        return publishRabbitProbe(orderId, MembershipPaymentRabbitNames.LOADTEST_POISON_EVENT);
    }

    @Override
    public RedisProbe inspectOrder(String orderId) {
        MembershipOrderRedisId validOrderId = new MembershipOrderRedisId(orderId);
        RedisQueueProbe queues = inspectQueues();
        return new RedisProbe(
                exists(keyFactory.membershipOrderSnapshotKey(validOrderId)),
                exists(keyFactory.membershipOrderCallbackMarkerKey(validOrderId)),
                exists(keyFactory.simulatedPaymentProviderResultKey(validOrderId)),
                exists(keyFactory.paymentCallbackOrderIdempotencyKey(validOrderId)),
                queues.callbackReadySize(),
                queues.callbackProcessingSize(),
                queues.dirtySize(),
                queues.dirtyProcessingSize());
    }

    @Override
    public RedisQueueProbe inspectQueues() {
        return new RedisQueueProbe(
                zsetSize(keyFactory.paymentCallbackReadyKey()),
                zsetSize(keyFactory.paymentCallbackProcessingKey()),
                orderQueue.dirtySize(),
                orderQueue.processingSize());
    }

    @Override
    public List<OrderArtifactProbe> inspectOrderArtifacts(List<String> orderIds) {
        List<MembershipOrderRedisId> requested = canonicalOrderIds(orderIds);
        List<Object> responses;
        try {
            // 每个订单只排入 snapshot 与 marker 两条 EXISTS，最多 250 个订单即 500 条命令，整个批次只发生一次网络往返。
            responses = redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (MembershipOrderRedisId orderId : requested) {
                        operations.hasKey(keyFactory.membershipOrderSnapshotKey(orderId));
                        operations.hasKey(keyFactory.membershipOrderCallbackMarkerKey(orderId));
                    }
                    return null;
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Loadtest Redis terminal artifact pipeline failed.", exception);
        }
        if (responses.size() != requested.size() * 2) {
            throw new IllegalStateException(
                    "Loadtest Redis terminal artifact pipeline result is incomplete.");
        }
        List<OrderArtifactProbe> result = new ArrayList<>(requested.size());
        for (int index = 0; index < requested.size(); index++) {
            result.add(new OrderArtifactProbe(
                    requested.get(index).value(),
                    booleanResponse(responses.get(index * 2)),
                    booleanResponse(responses.get(index * 2 + 1))));
        }
        return List.copyOf(result);
    }

    @Override
    public FaultProbe armCallbackCompleteFailure(String orderId) {
        return new FaultProbe(faultGate.armCallbackCompleteFailure(orderId));
    }

    @Override
    public FaultProbe inspectFaults() {
        return new FaultProbe(faultGate.callbackCompleteFailureCount());
    }

    @Override
    public CallbackHoldProbe armCallbackHold(String orderId, int maxHoldSeconds) {
        MembershipOrderRedisId validOrderId = new MembershipOrderRedisId(orderId);
        faultGate.armCallbackHold(
                validOrderId.value(), Duration.ofSeconds(maxHoldSeconds));
        return callbackHoldProbe(validOrderId);
    }

    @Override
    public CallbackHoldProbe inspectCallbackHold(String orderId) {
        return callbackHoldProbe(new MembershipOrderRedisId(orderId));
    }

    @Override
    public CallbackHoldProbe releaseCallbackHold(String orderId) {
        MembershipOrderRedisId validOrderId = new MembershipOrderRedisId(orderId);
        faultGate.releaseCallbackHold(validOrderId.value());
        return callbackHoldProbe(validOrderId);
    }

    @Override
    public WorkerPauseProbe pauseWorkers(int maxPauseSeconds) {
        Duration duration = Duration.ofSeconds(maxPauseSeconds);
        faultGate.pauseCallbackWorker(duration);
        faultGate.pauseOrderPersistenceWorker(duration);
        return workerPauseProbe();
    }

    @Override
    public WorkerPauseProbe inspectWorkers() {
        return workerPauseProbe();
    }

    @Override
    public WorkerPauseProbe resumeWorkers() {
        faultGate.resumeCallbackWorker();
        faultGate.resumeOrderPersistenceWorker();
        return workerPauseProbe();
    }

    private CallbackHoldProbe callbackHoldProbe(MembershipOrderRedisId orderId) {
        long remaining = faultGate.callbackHoldRemainingMillis(orderId.value());
        return new CallbackHoldProbe(
                remaining > 0L,
                exists(keyFactory.membershipOrderCallbackMarkerKey(orderId)),
                remaining);
    }

    private WorkerPauseProbe workerPauseProbe() {
        return new WorkerPauseProbe(
                faultGate.callbackWorkerPaused(),
                faultGate.callbackWorkerPauseRemainingMillis(),
                faultGate.orderPersistenceWorkerPaused(),
                faultGate.orderPersistenceWorkerPauseRemainingMillis());
    }

    private boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    private long zsetSize(String key) {
        Long size = redisTemplate.opsForZSet().zCard(key);
        if (size == null || size < 0L) {
            throw new IllegalStateException("Loadtest Redis queue size is unavailable.");
        }
        return size;
    }

    private static List<MembershipOrderRedisId> canonicalOrderIds(List<String> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            throw new IllegalArgumentException("At least one loadtest order ID is required.");
        }
        if (orderIds.size() > MAX_ARTIFACT_ORDERS) {
            throw new IllegalArgumentException(
                    "A loadtest Redis artifact batch cannot exceed "
                            + MAX_ARTIFACT_ORDERS
                            + " orders.");
        }
        LinkedHashSet<MembershipOrderRedisId> unique = new LinkedHashSet<>();
        for (String orderId : orderIds) {
            MembershipOrderRedisId valid = new MembershipOrderRedisId(orderId);
            if (!unique.add(valid)) {
                throw new IllegalArgumentException(
                        "Duplicate loadtest order ID is not allowed in one artifact batch.");
            }
        }
        return List.copyOf(unique);
    }

    private static boolean booleanResponse(Object response) {
        if (response instanceof Boolean value) {
            return value;
        }
        if (response instanceof Number value) {
            return value.longValue() != 0L;
        }
        throw new IllegalStateException(
                "Loadtest Redis terminal artifact response has an unexpected type.");
    }

    private String publishRabbitProbe(String orderId, String eventType) {
        MembershipOrderRedisId validOrderId = new MembershipOrderRedisId(orderId);
        String messageId = base64UrlCodec.encode(idWorker.nextId());
        MembershipPaymentRabbitEnvelope<MembershipPaymentCheckMessage> envelope =
                new MembershipPaymentRabbitEnvelope<>(
                        messageId,
                        eventType,
                        MembershipPaymentRabbitEnvelope.CURRENT_SCHEMA_VERSION,
                        MembershipPaymentTime.now(clock),
                        messageId,
                        new MembershipPaymentCheckMessage(validOrderId.value(), 0));
        // 探针仍走正式 Confirm 发送器并固定为持久消息，只把事件类型限定为两种 loadtest 常量。
        rabbitSender.send(
                MembershipPaymentRabbitNames.PAYMENT_EXCHANGE,
                MembershipPaymentRabbitNames.PAYMENT_ROUTING_KEY,
                envelope,
                Duration.ofMillis(1L));
        return messageId;
    }

    private static void requireOneClaim(int count, String queue) {
        if (count != 1) {
            throw new IllegalStateException(
                    "Loadtest " + queue + " recovery requires exactly one ready item.");
        }
    }

    private static void requireOneRecovery(int count, String queue) {
        if (count != 1) {
            throw new IllegalStateException(
                    "Loadtest " + queue + " recovery did not restore exactly one item.");
        }
    }
}
