package com.example.temperate.service.user.membership.payment.store.impl;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.common.redis.key.PaymentCallbackRedisId;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackClaim;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackCompletion;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackEnqueueOutcome;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackEnqueueResult;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackSnapshot;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderResult;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.store.PaymentCallbackQueue;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkAvailableEvent;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 该实现是来通过 Redis Hash、ready/processing ZSet 和 Lua 原子管理支付回调，不依赖 Redis Stream。
 *
 * <p>processing 分值同时充当领取代次；完成和重排必须匹配原分值，超时旧 Worker 因而不能操作新领取。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class RedisPaymentCallbackQueue implements PaymentCallbackQueue {

    private static final int MAXIMUM_BATCH = 500;
    private static final int PIPELINE_BATCH_SIZE = 50;
    private static final long IDEMPOTENCY_TTL_MILLIS = Duration.ofSeconds(30).toMillis();
    private static final long CALLBACK_TTL_MILLIS = Duration.ofHours(6).toMillis();
    private static final long MARKER_TTL_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final long PROVIDER_TTL_MILLIS = Duration.ofHours(6).toMillis();
    private static final RedisScript<String> ENQUEUE = stringScript("enqueue_callback.lua");
    private static final RedisScript<Long> ENSURE_READY = longScript("callback_ensure_ready.lua");
    private static final RedisScript<List> CLAIM = listScript("callback_claim.lua");
    private static final RedisScript<Long> RECOVER = longScript("callback_recover.lua");
    private static final RedisScript<Long> REQUEUE = longScript("callback_requeue.lua");
    private static final RedisScript<Long> COMPLETE = longScript("callback_complete.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final long idempotencyTtlMillis;
    private final long callbackTtlMillis;
    private final long markerTtlMillis;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public RedisPaymentCallbackQueue(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            MembershipPaymentProperties properties,
            ApplicationEventPublisher eventPublisher) {
        this(
                redisTemplate,
                keyFactory,
                properties.callback().dedupeTtl().toMillis(),
                properties.callback().dataTtl().toMillis(),
                properties.callback().markerTtl().toMillis(),
                eventPublisher);
    }

    public RedisPaymentCallbackQueue(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory) {
        this(
                redisTemplate,
                keyFactory,
                IDEMPOTENCY_TTL_MILLIS,
                CALLBACK_TTL_MILLIS,
                MARKER_TTL_MILLIS,
                event -> { });
    }

    private RedisPaymentCallbackQueue(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            long idempotencyTtlMillis,
            long callbackTtlMillis,
            long markerTtlMillis,
            ApplicationEventPublisher eventPublisher) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.idempotencyTtlMillis = requireTtl(idempotencyTtlMillis);
        this.callbackTtlMillis = requireTtl(callbackTtlMillis);
        this.markerTtlMillis = requireTtl(markerTtlMillis);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
    }

    @Override
    public PaymentCallbackEnqueueResult enqueue(
            PaymentCallbackSnapshot snapshot,
            HmacIdentifier fingerprint,
            HmacIdentifier providerTradeFingerprint) {
        PaymentCallbackSnapshot valid = Objects.requireNonNull(snapshot);
        HmacIdentifier validFingerprint = Objects.requireNonNull(fingerprint);
        HmacIdentifier validProviderTradeFingerprint =
                Objects.requireNonNull(providerTradeFingerprint);
        if (!valid.idempotencyFingerprint().equals(validFingerprint.value())) {
            throw new IllegalArgumentException(
                    "Payment callback fingerprint does not match the protected Redis key.");
        }
        PaymentCallbackRedisId callbackId = new PaymentCallbackRedisId(valid.callbackId());
        MembershipOrderRedisId orderId = new MembershipOrderRedisId(valid.orderId());
        String markerKey = keyFactory.membershipOrderCallbackMarkerKey(orderId);

        Map<String, String> callbackFields = new LinkedHashMap<>(
                MembershipPaymentRedisCodec.writeCallback(valid));
        requireCallbackSize(callbackFields);
        SimulatedPaymentProviderResult providerResult = new SimulatedPaymentProviderResult(
                SimulatedPaymentProviderResult.CURRENT_SCHEMA_VERSION,
                valid.orderId(),
                SimulatedPaymentProviderStatus.PAID,
                valid.callbackId(),
                valid.providerTradeNo(),
                valid.payType(),
                valid.paidAmountYuan(),
                valid.paidAt());
        Map<String, String> providerFields =
                MembershipPaymentRedisCodec.writeProvider(providerResult);

        List<Object> arguments = new ArrayList<>();
        arguments.add(valid.callbackId());
        arguments.add(valid.orderId());
        arguments.add(Long.toString(valid.receivedAt().toInstant().toEpochMilli()));
        arguments.add(Long.toString(idempotencyTtlMillis));
        arguments.add(Long.toString(callbackTtlMillis));
        arguments.add(Long.toString(markerTtlMillis));
        arguments.add(Long.toString(PROVIDER_TTL_MILLIS));
        appendFields(arguments, callbackFields);
        appendFields(arguments, providerFields);

        String raw = executeString(
                ENQUEUE,
                List.of(
                        keyFactory.paymentCallbackIdempotencyKey(validFingerprint),
                        keyFactory.paymentCallbackOrderIdempotencyKey(orderId),
                        keyFactory.paymentCallbackProviderTradeIdempotencyKey(
                                validProviderTradeFingerprint),
                        keyFactory.paymentCallbackDataKey(callbackId),
                        keyFactory.paymentCallbackReadyKey(),
                        markerKey,
                        keyFactory.simulatedPaymentProviderResultKey(orderId)),
                arguments.toArray());
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 2) {
            throw unavailable("Redis payment callback enqueue result is malformed.");
        }
        try {
            PaymentCallbackEnqueueResult result = new PaymentCallbackEnqueueResult(
                    PaymentCallbackEnqueueOutcome.valueOf(parts[0]),
                    parts[1]);
            if (result.outcome() == PaymentCallbackEnqueueOutcome.ENQUEUED) {
                signalWork();
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw unavailable("Redis payment callback enqueue result is invalid.", exception);
        }
    }

    @Override
    public boolean ensureReady(String callbackId, long readyAtEpochMillis) {
        PaymentCallbackRedisId validCallbackId = new PaymentCallbackRedisId(callbackId);
        requireEpochMillis(readyAtEpochMillis, "ensure ready time");
        boolean ready = executeLong(
                        ENSURE_READY,
                        List.of(
                                keyFactory.paymentCallbackDataKey(validCallbackId),
                                keyFactory.paymentCallbackReadyKey(),
                                keyFactory.paymentCallbackProcessingKey()),
                        validCallbackId.value(),
                        Long.toString(readyAtEpochMillis))
                == 1L;
        if (ready) {
            signalWork();
        }
        return ready;
    }

    private void signalWork() {
        eventPublisher.publishEvent(new MembershipPaymentWorkAvailableEvent(
                MembershipPaymentWorkType.CALLBACK));
    }

    @Override
    public long processingSize() {
        return zsetSize(keyFactory.paymentCallbackProcessingKey());
    }

    @Override
    public List<PaymentCallbackClaim> claim(int maximum, long claimedAtEpochMillis) {
        int validMaximum = requireBatch(maximum);
        requireEpochMillis(claimedAtEpochMillis, "claim time");
        List<String> callbackIds = executeList(
                CLAIM,
                List.of(
                        keyFactory.paymentCallbackReadyKey(),
                        keyFactory.paymentCallbackProcessingKey()),
                Integer.toString(validMaximum),
                Long.toString(claimedAtEpochMillis));
        return callbackIds.stream()
                .map(id -> new PaymentCallbackClaim(id, claimedAtEpochMillis))
                .toList();
    }

    @Override
    public int recoverTimedOut(
            long cutoffEpochMillis,
            int maximum,
            long readyAtEpochMillis) {
        requireEpochMillis(cutoffEpochMillis, "recovery cutoff");
        requireEpochMillis(readyAtEpochMillis, "recovery ready time");
        return Math.toIntExact(executeLong(
                RECOVER,
                List.of(
                        keyFactory.paymentCallbackReadyKey(),
                        keyFactory.paymentCallbackProcessingKey()),
                Long.toString(cutoffEpochMillis),
                Integer.toString(requireBatch(maximum)),
                Long.toString(readyAtEpochMillis)));
    }

    @Override
    public Map<String, PaymentCallbackSnapshot> findAll(
            Collection<String> callbackIds) {
        List<String> requested = boundedCallbackIds(callbackIds);
        if (requested.isEmpty()) {
            return Map.of();
        }
        List<Object> responses;
        try {
            // 回调 Hash 在单次 Pipeline 中批量读取，循环只排队命令，不产生逐项网络 I/O。
            responses = redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (String id : requested) {
                        operations.opsForHash().entries(
                                keyFactory.paymentCallbackDataKey(
                                        new PaymentCallbackRedisId(id)));
                    }
                    return null;
                }
            });
        } catch (RuntimeException exception) {
            throw unavailable("Redis payment callback pipeline failed.", exception);
        }
        if (responses.size() != requested.size()) {
            throw unavailable("Redis payment callback pipeline result is incomplete.");
        }
        Map<String, PaymentCallbackSnapshot> snapshots = new LinkedHashMap<>();
        for (int index = 0; index < requested.size(); index++) {
            Object response = responses.get(index);
            if (!(response instanceof Map<?, ?> raw) || raw.isEmpty()) {
                continue;
            }
            PaymentCallbackSnapshot snapshot = MembershipPaymentRedisCodec.readCallback(
                    MembershipPaymentRedisCodec.stringMap(raw));
            if (!requested.get(index).equals(snapshot.callbackId())) {
                throw unavailable("Redis payment callback ID is inconsistent.");
            }
            snapshots.put(snapshot.callbackId(), snapshot);
        }
        return Map.copyOf(snapshots);
    }

    @Override
    public int requeue(
            Collection<PaymentCallbackClaim> claims,
            long readyAtEpochMillis) {
        List<PaymentCallbackClaim> valid = boundedClaims(claims);
        if (valid.isEmpty()) {
            return 0;
        }
        requireEpochMillis(readyAtEpochMillis, "requeue time");
        List<Object> arguments = new ArrayList<>();
        arguments.add(Integer.toString(valid.size()));
        arguments.add(Long.toString(readyAtEpochMillis));
        valid.forEach(claim -> {
            arguments.add(claim.callbackId());
            arguments.add(Long.toString(claim.claimedAtEpochMillis()));
        });
        return Math.toIntExact(executeLong(
                REQUEUE,
                List.of(
                        keyFactory.paymentCallbackReadyKey(),
                        keyFactory.paymentCallbackProcessingKey()),
                arguments.toArray()));
    }

    @Override
    public int complete(Collection<PaymentCallbackCompletion> completions) {
        List<PaymentCallbackCompletion> valid = boundedCompletions(completions);
        if (valid.isEmpty()) {
            return 0;
        }
        int completed = 0;
        for (int start = 0; start < valid.size(); start += PIPELINE_BATCH_SIZE) {
            List<PaymentCallbackCompletion> batch = valid.subList(
                    start, Math.min(start + PIPELINE_BATCH_SIZE, valid.size()));
            for (Object response : executeCompletePipeline(batch)) {
                long value = number(response);
                if (value < 0L || value > 1L) {
                    throw unavailable("Redis payment callback completion result is invalid.");
                }
                completed = Math.addExact(completed, Math.toIntExact(value));
            }
        }
        return completed;
    }

    private List<Object> executeCompletePipeline(
            List<PaymentCallbackCompletion> batch) {
        try {
            // 每个回调只保持自己的 claim 代次原子性；Pipeline 部分成功时可依靠 score 精确匹配幂等重试。
            List<Object> responses = redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (PaymentCallbackCompletion completion : batch) {
                        PaymentCallbackClaim claim = completion.claim();
                        String callbackDataKey = keyFactory.paymentCallbackDataKey(
                                new PaymentCallbackRedisId(claim.callbackId()));
                        boolean hasOrder = completion.orderId() != null;
                        String markerKey = callbackDataKey;
                        String providerResultKey = callbackDataKey;
                        if (hasOrder) {
                            MembershipOrderRedisId orderId =
                                    new MembershipOrderRedisId(completion.orderId());
                            markerKey = keyFactory.membershipOrderCallbackMarkerKey(orderId);
                            providerResultKey =
                                    keyFactory.simulatedPaymentProviderResultKey(orderId);
                        }
                        operations.execute(
                                COMPLETE,
                                List.of(
                                        keyFactory.paymentCallbackProcessingKey(),
                                        callbackDataKey,
                                        markerKey,
                                        providerResultKey),
                                claim.callbackId(),
                                Long.toString(claim.claimedAtEpochMillis()),
                                completion.providerResultAction().name(),
                                hasOrder ? "1" : "0");
                    }
                    return null;
                }
            });
            if (responses.size() != batch.size()) {
                throw unavailable(
                        "Redis payment callback completion pipeline result is incomplete.");
            }
            return responses;
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis payment callback completion pipeline failed.", exception);
        }
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(text(value));
        } catch (NumberFormatException exception) {
            throw unavailable("Redis payment callback result is not numeric.", exception);
        }
    }

    private static void appendFields(
            List<Object> arguments,
            Map<String, String> fields) {
        arguments.add(Integer.toString(fields.size()));
        fields.forEach((name, value) -> {
            arguments.add(name);
            arguments.add(value);
        });
    }

    private static List<String> boundedCallbackIds(Collection<String> callbackIds) {
        Objects.requireNonNull(callbackIds, "callbackIds must not be null");
        List<String> ids = callbackIds.stream()
                .map(id -> new PaymentCallbackRedisId(id).value())
                .distinct()
                .toList();
        if (ids.size() > MAXIMUM_BATCH) {
            throw new IllegalArgumentException("Payment callback Redis batch exceeds 500 IDs.");
        }
        return ids;
    }

    private static List<PaymentCallbackClaim> boundedClaims(
            Collection<PaymentCallbackClaim> claims) {
        Objects.requireNonNull(claims, "claims must not be null");
        List<PaymentCallbackClaim> values = claims.stream()
                .map(Objects::requireNonNull)
                .distinct()
                .toList();
        if (values.size() > MAXIMUM_BATCH) {
            throw new IllegalArgumentException("Payment callback claim batch exceeds 500.");
        }
        if (values.stream().map(PaymentCallbackClaim::callbackId).distinct().count()
                != values.size()) {
            throw new IllegalArgumentException(
                    "Payment callback claim IDs must be unique.");
        }
        return values;
    }

    private static List<PaymentCallbackCompletion> boundedCompletions(
            Collection<PaymentCallbackCompletion> completions) {
        Objects.requireNonNull(completions, "completions must not be null");
        List<PaymentCallbackCompletion> values = completions.stream()
                .map(Objects::requireNonNull)
                .distinct()
                .toList();
        if (values.size() > MAXIMUM_BATCH) {
            throw new IllegalArgumentException(
                    "Payment callback completion batch exceeds 500.");
        }
        if (values.stream()
                        .map(completion -> completion.claim().callbackId())
                        .distinct()
                        .count()
                != values.size()) {
            throw new IllegalArgumentException(
                    "Payment callback completion IDs must be unique.");
        }
        return values;
    }

    private static int requireBatch(int value) {
        if (value < 1 || value > MAXIMUM_BATCH) {
            throw new IllegalArgumentException("Payment callback batch must be between 1 and 500.");
        }
        return value;
    }

    private static void requireEpochMillis(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static long requireTtl(long value) {
        if (value <= 0L) {
            throw new IllegalArgumentException("Payment callback Redis TTL must be positive.");
        }
        return value;
    }

    private static void requireCallbackSize(Map<String, String> fields) {
        long bytes = fields.entrySet().stream()
                .mapToLong(entry -> utf8Length(entry.getKey()) + utf8Length(entry.getValue()))
                .sum();
        if (bytes > 8L * 1024L) {
            throw new IllegalArgumentException(
                    "Payment callback Redis Hash exceeds the 8 KiB hard limit.");
        }
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private String executeString(
            RedisScript<String> script,
            List<String> keys,
            Object... arguments) {
        try {
            String result = redisTemplate.execute(script, keys, arguments);
            if (result == null) {
                throw unavailable("Redis payment callback script returned no result.");
            }
            return result;
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis payment callback script failed.", exception);
        }
    }

    private long executeLong(
            RedisScript<Long> script,
            List<String> keys,
            Object... arguments) {
        try {
            Long result = redisTemplate.execute(script, keys, arguments);
            if (result == null || result < 0) {
                throw unavailable("Redis payment callback script returned an invalid count.");
            }
            return result;
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis payment callback script failed.", exception);
        }
    }

    private long zsetSize(String key) {
        try {
            Long size = redisTemplate.opsForZSet().zCard(key);
            if (size == null || size < 0L) {
                throw unavailable("Redis payment callback ZSet size is invalid.");
            }
            return size;
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis payment callback ZSet size failed.", exception);
        }
    }

    private List<String> executeList(
            RedisScript<List> script,
            List<String> keys,
            Object... arguments) {
        try {
            List<?> result = redisTemplate.execute(script, keys, arguments);
            if (result == null || result.size() > MAXIMUM_BATCH) {
                throw unavailable("Redis payment callback claim result is invalid.");
            }
            return result.stream().map(RedisPaymentCallbackQueue::text).toList();
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis payment callback claim failed.", exception);
        }
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private static RedisScript<String> stringScript(String fileName) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/membership-payment/" + fileName));
        script.setResultType(String.class);
        return script;
    }

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> listScript(String fileName) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/membership-payment/" + fileName));
        script.setResultType(List.class);
        return script;
    }

    private static RedisScript<Long> longScript(String fileName) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/membership-payment/" + fileName));
        script.setResultType(Long.class);
        return script;
    }

    private static MembershipPaymentInfrastructureException unavailable(String message) {
        return new MembershipPaymentInfrastructureException(message);
    }

    private static MembershipPaymentInfrastructureException unavailable(
            String message,
            Throwable cause) {
        return new MembershipPaymentInfrastructureException(message, cause);
    }
}
