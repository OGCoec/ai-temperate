package com.example.temperate.service.user.membership.payment.store.impl;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.common.redis.key.PaymentCallbackRedisId;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRefundRequiredFinalizationCommand;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRejectedCallbackReleaseCommand;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionOutcome;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.store.MembershipPaymentMissingSnapshotReleaseOutcome;
import com.example.temperate.service.user.membership.payment.store.MembershipPaymentUnappliedCallbackStore;
import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 该实现是来用有界的单订单 Lua 批量收敛未应用回调，避免 Marker 清理与恢复消息之间依赖固定时间窗。
 *
 * <p>业务层一次提交 Collection，适配层按五十条 Pipeline 合并网络往返；每个订单仍由固定 Key、无循环 Lua 独立保证原子性。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class RedisMembershipPaymentUnappliedCallbackStore
        implements MembershipPaymentUnappliedCallbackStore {

    private static final int MAXIMUM_BATCH = 500;
    private static final int PIPELINE_BATCH_SIZE = 50;
    private static final long SNAPSHOT_TTL_MILLIS = Duration.ofHours(6).toMillis();
    private static final RedisScript<String> FINALIZE_REFUND_REQUIRED =
            stringScript("finalize_refund_required.lua");
    private static final RedisScript<String> RELEASE_MISSING_REFUND_REQUIRED =
            stringScript("release_missing_refund_required.lua");
    private static final RedisScript<Long> RELEASE_REJECTED =
            longScript("release_rejected_callback.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;

    public RedisMembershipPaymentUnappliedCallbackStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
    }

    @Override
    public Map<String, MembershipOrderTransitionResult> finalizeRefundRequired(
            Collection<MembershipPaymentRefundRequiredFinalizationCommand> commands) {
        List<MembershipPaymentRefundRequiredFinalizationCommand> valid =
                boundedRefundCommands(commands);
        if (valid.isEmpty()) {
            return Map.of();
        }
        Map<String, MembershipOrderTransitionResult> results = new LinkedHashMap<>();
        for (int start = 0; start < valid.size(); start += PIPELINE_BATCH_SIZE) {
            List<MembershipPaymentRefundRequiredFinalizationCommand> batch = valid.subList(
                    start, Math.min(start + PIPELINE_BATCH_SIZE, valid.size()));
            List<Object> responses = executeRefundPipeline(batch);
            requirePipelineSize(responses, batch.size(), "refund finalization");
            for (int index = 0; index < batch.size(); index++) {
                String callbackId = batch.get(index).claim().callbackId();
                MembershipOrderTransitionResult previous = results.put(
                        callbackId, parseTransition(text(responses.get(index))));
                if (previous != null) {
                    throw unavailable("Redis refund finalization result is duplicated.");
                }
            }
        }
        return Map.copyOf(results);
    }

    @Override
    public Map<String, MembershipPaymentMissingSnapshotReleaseOutcome>
            releaseMissingRefundRequired(
                    Collection<MembershipPaymentRefundRequiredFinalizationCommand> commands) {
        List<MembershipPaymentRefundRequiredFinalizationCommand> valid =
                boundedRefundCommands(commands);
        if (valid.isEmpty()) {
            return Map.of();
        }
        Map<String, MembershipPaymentMissingSnapshotReleaseOutcome> results =
                new LinkedHashMap<>();
        for (int start = 0; start < valid.size(); start += PIPELINE_BATCH_SIZE) {
            List<MembershipPaymentRefundRequiredFinalizationCommand> batch = valid.subList(
                    start, Math.min(start + PIPELINE_BATCH_SIZE, valid.size()));
            List<Object> responses = executeMissingRefundReleasePipeline(batch);
            requirePipelineSize(responses, batch.size(), "missing refund release");
            for (int index = 0; index < batch.size(); index++) {
                String callbackId = batch.get(index).claim().callbackId();
                MembershipPaymentMissingSnapshotReleaseOutcome previous = results.put(
                        callbackId, parseMissingRelease(text(responses.get(index))));
                if (previous != null) {
                    throw unavailable("Redis missing refund release result is duplicated.");
                }
            }
        }
        return Map.copyOf(results);
    }

    @Override
    public Set<String> releaseRejected(
            Collection<MembershipPaymentRejectedCallbackReleaseCommand> commands) {
        List<MembershipPaymentRejectedCallbackReleaseCommand> valid =
                boundedRejectedCommands(commands);
        if (valid.isEmpty()) {
            return Set.of();
        }
        Set<String> released = new LinkedHashSet<>();
        for (int start = 0; start < valid.size(); start += PIPELINE_BATCH_SIZE) {
            List<MembershipPaymentRejectedCallbackReleaseCommand> batch = valid.subList(
                    start, Math.min(start + PIPELINE_BATCH_SIZE, valid.size()));
            List<Object> responses = executeRejectedPipeline(batch);
            requirePipelineSize(responses, batch.size(), "rejected release");
            for (int index = 0; index < batch.size(); index++) {
                long result = number(responses.get(index));
                if (result < 0L || result > 1L) {
                    throw unavailable("Redis rejected callback release result is invalid.");
                }
                if (result == 1L) {
                    released.add(batch.get(index).claim().callbackId());
                }
            }
        }
        return Set.copyOf(released);
    }

    private List<Object> executeRefundPipeline(
            List<MembershipPaymentRefundRequiredFinalizationCommand> batch) {
        try {
            // Pipeline 只减少网络往返；单订单 Lua 的 claim 校验和状态迁移仍各自独立，不形成批次事务。
            return redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (MembershipPaymentRefundRequiredFinalizationCommand command : batch) {
                        PaymentCallbackRedisId callbackId = new PaymentCallbackRedisId(
                                command.claim().callbackId());
                        MembershipOrderRedisId orderId = new MembershipOrderRedisId(
                                command.orderId());
                        operations.execute(
                                FINALIZE_REFUND_REQUIRED,
                                List.of(
                                        keyFactory.paymentCallbackProcessingKey(),
                                        keyFactory.membershipOrderSnapshotKey(orderId),
                                        keyFactory.membershipOrderCallbackMarkerKey(orderId),
                                        keyFactory.simulatedPaymentProviderResultKey(orderId),
                                        keyFactory.orderPersistenceDirtyKey()),
                                callbackId.value(),
                                Long.toString(command.claim().claimedAtEpochMillis()),
                                Long.toString(epochMicros(command.hardCloseAt())),
                                Long.toString(epochMicros(command.resolvedAt())),
                                Long.toString(command.resolvedAt().toInstant().toEpochMilli()),
                                Long.toString(SNAPSHOT_TTL_MILLIS),
                                orderId.value());
                    }
                    return null;
                }
            });
        } catch (RuntimeException exception) {
            throw unavailable("Redis refund finalization pipeline failed.", exception);
        }
    }

    private List<Object> executeRejectedPipeline(
            List<MembershipPaymentRejectedCallbackReleaseCommand> batch) {
        try {
            // Marker 在 Rabbit 发布前释放，但 processing claim 保留到发布成功，崩溃后仍可恢复并重复发布。
            return redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (MembershipPaymentRejectedCallbackReleaseCommand command : batch) {
                        PaymentCallbackRedisId callbackId = new PaymentCallbackRedisId(
                                command.claim().callbackId());
                        MembershipOrderRedisId orderId = new MembershipOrderRedisId(
                                command.orderId());
                        operations.execute(
                                RELEASE_REJECTED,
                                List.of(
                                        keyFactory.paymentCallbackProcessingKey(),
                                        keyFactory.membershipOrderCallbackMarkerKey(orderId),
                                        keyFactory.simulatedPaymentProviderResultKey(orderId)),
                                callbackId.value(),
                                Long.toString(command.claim().claimedAtEpochMillis()));
                    }
                    return null;
                }
            });
        } catch (RuntimeException exception) {
            throw unavailable("Redis rejected callback release pipeline failed.", exception);
        }
    }

    private List<Object> executeMissingRefundReleasePipeline(
            List<MembershipPaymentRefundRequiredFinalizationCommand> batch) {
        try {
            // PostgreSQL 已证明终态后这里只释放本 callback 的临时事实；processing claim 留给退款成功后的 complete。
            return redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (MembershipPaymentRefundRequiredFinalizationCommand command : batch) {
                        PaymentCallbackRedisId callbackId = new PaymentCallbackRedisId(
                                command.claim().callbackId());
                        MembershipOrderRedisId orderId = new MembershipOrderRedisId(
                                command.orderId());
                        operations.execute(
                                RELEASE_MISSING_REFUND_REQUIRED,
                                List.of(
                                        keyFactory.paymentCallbackProcessingKey(),
                                        keyFactory.membershipOrderCallbackMarkerKey(orderId),
                                        keyFactory.simulatedPaymentProviderResultKey(orderId)),
                                callbackId.value(),
                                Long.toString(command.claim().claimedAtEpochMillis()));
                    }
                    return null;
                }
            });
        } catch (RuntimeException exception) {
            throw unavailable("Redis missing refund release pipeline failed.", exception);
        }
    }

    private static List<MembershipPaymentRefundRequiredFinalizationCommand>
            boundedRefundCommands(
                    Collection<MembershipPaymentRefundRequiredFinalizationCommand> commands) {
        Objects.requireNonNull(commands, "refund finalization commands must not be null");
        List<MembershipPaymentRefundRequiredFinalizationCommand> values = commands.stream()
                .map(Objects::requireNonNull)
                .distinct()
                .toList();
        requireBatchSize(values.size());
        requireUniqueCallbackIds(values.stream()
                .map(command -> command.claim().callbackId())
                .toList());
        return values;
    }

    private static List<MembershipPaymentRejectedCallbackReleaseCommand>
            boundedRejectedCommands(
                    Collection<MembershipPaymentRejectedCallbackReleaseCommand> commands) {
        Objects.requireNonNull(commands, "rejected release commands must not be null");
        List<MembershipPaymentRejectedCallbackReleaseCommand> values = commands.stream()
                .map(Objects::requireNonNull)
                .distinct()
                .toList();
        requireBatchSize(values.size());
        requireUniqueCallbackIds(values.stream()
                .map(command -> command.claim().callbackId())
                .toList());
        return values;
    }

    private static void requireBatchSize(int size) {
        if (size > MAXIMUM_BATCH) {
            throw new IllegalArgumentException(
                    "Membership unapplied callback Redis batch exceeds 500 commands.");
        }
    }

    private static void requireUniqueCallbackIds(List<String> callbackIds) {
        if (callbackIds.stream().distinct().count() != callbackIds.size()) {
            throw new IllegalArgumentException(
                    "Membership unapplied callback IDs must be unique.");
        }
    }

    private static MembershipOrderTransitionResult parseTransition(String raw) {
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 3) {
            throw unavailable("Redis refund finalization result is malformed.");
        }
        try {
            MembershipOrderTransitionOutcome outcome =
                    MembershipOrderTransitionOutcome.valueOf(parts[0]);
            MembershipOrderStatus status = parts[1].isEmpty()
                    ? null
                    : MembershipOrderStatus.valueOf(parts[1]);
            return new MembershipOrderTransitionResult(
                    outcome, status, Long.parseLong(parts[2]));
        } catch (IllegalArgumentException exception) {
            throw unavailable("Redis refund finalization result is invalid.", exception);
        }
    }

    private static MembershipPaymentMissingSnapshotReleaseOutcome parseMissingRelease(
            String raw) {
        try {
            return MembershipPaymentMissingSnapshotReleaseOutcome.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            throw unavailable("Redis missing refund release result is invalid.", exception);
        }
    }

    private static void requirePipelineSize(
            List<Object> responses,
            int expected,
            String operation) {
        if (responses.size() != expected) {
            throw unavailable("Redis membership callback " + operation
                    + " pipeline result is incomplete.");
        }
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(text(value));
        } catch (NumberFormatException exception) {
            throw unavailable("Redis rejected callback release result is not numeric.", exception);
        }
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private static long epochMicros(OffsetDateTime value) {
        return MembershipPaymentTime.toEpochMicros(value);
    }

    private static RedisScript<String> stringScript(String fileName) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/membership-payment/" + fileName));
        script.setResultType(String.class);
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
