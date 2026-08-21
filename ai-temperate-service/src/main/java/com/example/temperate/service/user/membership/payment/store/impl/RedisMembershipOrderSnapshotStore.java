package com.example.temperate.service.user.membership.payment.store.impl;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.common.redis.key.PaymentCallbackRedisId;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderPaidCommand;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionOutcome;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 该实现是来使用 Redis Hash 与 Lua 维护会员订单状态快照，并把每次有效迁移原子写入版本化脏队列。
 *
 * <p>状态机只接受计划定义的迁移；Pipeline 仅用于有界批量读取，不被描述为事务或强一致性保证。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class RedisMembershipOrderSnapshotStore
        implements MembershipOrderSnapshotStore {

    private static final int MAXIMUM_BATCH = 500;
    private static final long SNAPSHOT_TTL_MILLIS = Duration.ofHours(6).toMillis();
    private static final RedisScript<String> PUT = stringScript("put_order_snapshot.lua");
    private static final RedisScript<Long> PUT_BATCH = longScript("put_order_snapshots.lua");
    private static final RedisScript<List> MARK_PAID_BATCH = listScript("mark_paid_batch.lua");
    private static final RedisScript<String> CANCEL = stringScript("cancel_order.lua");
    private static final RedisScript<String> START_CLOSING =
            stringScript("start_closing.lua");
    private static final RedisScript<String> FINALIZE_CLOSING =
            stringScript("finalize_closing.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;

    public RedisMembershipOrderSnapshotStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
    }

    @Override
    public void put(MembershipOrderSnapshot snapshot) {
        MembershipOrderSnapshot valid = Objects.requireNonNull(snapshot);
        Map<String, String> fields = MembershipPaymentRedisCodec.writeOrder(valid);
        List<Object> arguments = new ArrayList<>();
        arguments.add(Long.toString(valid.stateVersion()));
        arguments.add(Long.toString(SNAPSHOT_TTL_MILLIS));
        arguments.add(Integer.toString(fields.size()));
        fields.forEach((name, value) -> {
            arguments.add(name);
            arguments.add(value);
        });
        String outcome = execute(
                PUT,
                List.of(keyFactory.membershipOrderSnapshotKey(orderId(valid.orderId()))),
                arguments.toArray());
        if (!List.of("CREATED", "REPLACED", "UNCHANGED", "STALE").contains(outcome)) {
            throw unavailable("Unexpected membership order snapshot put result.");
        }
    }

    @Override
    public void putAll(Collection<MembershipOrderSnapshot> snapshots) {
        List<MembershipOrderSnapshot> valid = boundedSnapshots(snapshots);
        if (valid.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        arguments.add(Long.toString(SNAPSHOT_TTL_MILLIS));
        arguments.add(Integer.toString(valid.size()));
        for (MembershipOrderSnapshot snapshot : valid) {
            keys.add(keyFactory.membershipOrderSnapshotKey(orderId(snapshot.orderId())));
            Map<String, String> fields = MembershipPaymentRedisCodec.writeOrder(snapshot);
            arguments.add(Long.toString(snapshot.stateVersion()));
            arguments.add(Integer.toString(fields.size()));
            fields.forEach((name, value) -> {
                arguments.add(name);
                arguments.add(value);
            });
        }
        if (executeLong(PUT_BATCH, keys, arguments.toArray()) != valid.size()) {
            throw unavailable("Redis membership order batch put result is incomplete.");
        }
    }

    @Override
    public Optional<MembershipOrderSnapshot> find(String orderId) {
        String key = keyFactory.membershipOrderSnapshotKey(orderId(orderId));
        try {
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
            return raw.isEmpty()
                    ? Optional.empty()
                    : Optional.of(MembershipPaymentRedisCodec.readOrder(
                            MembershipPaymentRedisCodec.stringMap(raw)));
        } catch (RuntimeException exception) {
            throw unavailable("Redis membership order snapshot read failed.", exception);
        }
    }

    @Override
    public Map<String, MembershipOrderSnapshot> findAll(Collection<String> orderIds) {
        List<String> requested = boundedIds(orderIds);
        if (requested.isEmpty()) {
            return Map.of();
        }
        List<Object> responses;
        try {
            // 命令在一个 Pipeline 中提交，避免按订单逐次产生网络往返；读取结果仍可能跨时刻，不具备事务快照语义。
            responses = redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (String id : requested) {
                        operations.opsForHash().entries(
                                keyFactory.membershipOrderSnapshotKey(orderId(id)));
                    }
                    return null;
                }
            });
        } catch (RuntimeException exception) {
            throw unavailable("Redis membership order snapshot pipeline failed.", exception);
        }
        if (responses.size() != requested.size()) {
            throw unavailable("Redis membership order snapshot pipeline result is incomplete.");
        }
        Map<String, MembershipOrderSnapshot> snapshots = new LinkedHashMap<>();
        for (int index = 0; index < requested.size(); index++) {
            Object response = responses.get(index);
            if (!(response instanceof Map<?, ?> raw) || raw.isEmpty()) {
                continue;
            }
            MembershipOrderSnapshot snapshot = MembershipPaymentRedisCodec.readOrder(
                    MembershipPaymentRedisCodec.stringMap(raw));
            if (!requested.get(index).equals(snapshot.orderId())) {
                throw unavailable("Redis membership order snapshot ID is inconsistent.");
            }
            snapshots.put(snapshot.orderId(), snapshot);
        }
        return Map.copyOf(snapshots);
    }

    @Override
    public boolean callbackInProgress(String orderId) {
        MembershipOrderRedisId validOrderId = orderId(orderId);
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(
                    keyFactory.membershipOrderCallbackMarkerKey(validOrderId)));
        } catch (RuntimeException exception) {
            throw unavailable("Redis membership order callback marker read failed.", exception);
        }
    }

    @Override
    public MembershipOrderTransitionResult markPaid(
            String orderId,
            String callbackId,
            String providerTradeNo,
            BigDecimal paidAmountYuan,
            OffsetDateTime paidAt) {
        PaymentCallbackRedisId validCallbackId = new PaymentCallbackRedisId(callbackId);
        MembershipOrderTransitionResult result = markPaidAll(List.of(
                        new MembershipOrderPaidCommand(
                                validCallbackId.value(),
                                orderId,
                                requiredText("provider trade number", providerTradeNo, 128),
                                requireAmount(paidAmountYuan),
                                paidAt,
                                paidAt)))
                .get(validCallbackId.value());
        if (result == null) {
            throw unavailable("Redis membership order paid result is missing.");
        }
        return result;
    }

    @Override
    public Map<String, MembershipOrderTransitionResult> markPaidAll(
            Collection<MembershipOrderPaidCommand> commands) {
        List<MembershipOrderPaidCommand> valid = boundedPaidCommands(commands);
        if (valid.isEmpty()) {
            return Map.of();
        }
        List<String> keys = new ArrayList<>();
        keys.add(keyFactory.orderPersistenceDirtyKey());
        List<Object> arguments = new ArrayList<>();
        arguments.add(Integer.toString(valid.size()));
        arguments.add(Long.toString(SNAPSHOT_TTL_MILLIS));
        for (MembershipOrderPaidCommand command : valid) {
            MembershipOrderRedisId validOrderId = orderId(command.orderId());
            keys.add(keyFactory.membershipOrderSnapshotKey(validOrderId));
            keys.add(keyFactory.membershipOrderCallbackMarkerKey(validOrderId));
            arguments.add(command.callbackId());
            arguments.add(validOrderId.value());
            arguments.add(requiredText(
                    "provider trade number", command.providerTradeNo(), 128));
            arguments.add(requireAmount(command.paidAmountYuan()).toPlainString());
            arguments.add(Long.toString(epochMillis(command.paidAt(), "paidAt")));
            arguments.add(Long.toString(epochMillis(command.changedAt(), "changedAt")));
        }
        List<String> rows = executeList(MARK_PAID_BATCH, keys, arguments.toArray());
        if (rows.size() != valid.size()) {
            throw unavailable("Redis membership order paid batch result is incomplete.");
        }
        Map<String, MembershipOrderTransitionResult> results = new LinkedHashMap<>();
        for (String row : rows) {
            String[] parts = row.split("\\|", -1);
            if (parts.length != 4) {
                throw unavailable("Redis membership order paid batch result is malformed.");
            }
            try {
                String validCallbackId = new PaymentCallbackRedisId(parts[0]).value();
                MembershipOrderTransitionOutcome outcome =
                        MembershipOrderTransitionOutcome.valueOf(parts[1]);
                MembershipOrderStatus status = parts[2].isEmpty()
                        ? null
                        : MembershipOrderStatus.valueOf(parts[2]);
                long stateVersion = Long.parseLong(parts[3]);
                if (results.put(validCallbackId, new MembershipOrderTransitionResult(
                        outcome, status, stateVersion)) != null) {
                    throw unavailable("Redis membership order paid callback result is duplicated.");
                }
            } catch (IllegalArgumentException exception) {
                throw unavailable("Redis membership order paid batch result is invalid.", exception);
            }
        }
        return Map.copyOf(results);
    }

    @Override
    public MembershipOrderTransitionResult cancel(
            String orderId,
            OffsetDateTime cancelledAt) {
        MembershipOrderRedisId validOrderId = orderId(orderId);
        return transition(
                CANCEL,
                List.of(
                        keyFactory.membershipOrderSnapshotKey(validOrderId),
                        keyFactory.membershipOrderCallbackMarkerKey(validOrderId),
                        keyFactory.orderPersistenceDirtyKey()),
                Long.toString(epochMillis(cancelledAt, "cancelledAt")),
                Long.toString(SNAPSHOT_TTL_MILLIS),
                validOrderId.value());
    }

    @Override
    public MembershipOrderTransitionResult startClosing(
            String orderId,
            OffsetDateTime closingDeadlineAt,
            OffsetDateTime changedAt) {
        MembershipOrderRedisId validOrderId = orderId(orderId);
        long deadline = epochMillis(closingDeadlineAt, "closingDeadlineAt");
        long changed = epochMillis(changedAt, "changedAt");
        return transition(
                START_CLOSING,
                List.of(
                        keyFactory.membershipOrderSnapshotKey(validOrderId),
                        keyFactory.orderPersistenceDirtyKey()),
                Long.toString(deadline),
                Long.toString(changed),
                Long.toString(SNAPSHOT_TTL_MILLIS),
                validOrderId.value());
    }

    @Override
    public MembershipOrderTransitionResult finalizeClosing(
            String orderId,
            OffsetDateTime changedAt) {
        MembershipOrderRedisId validOrderId = orderId(orderId);
        return transition(
                FINALIZE_CLOSING,
                List.of(
                        keyFactory.membershipOrderSnapshotKey(validOrderId),
                        keyFactory.membershipOrderCallbackMarkerKey(validOrderId),
                        keyFactory.simulatedPaymentProviderResultKey(validOrderId),
                        keyFactory.orderPersistenceDirtyKey()),
                Long.toString(epochMillis(changedAt, "changedAt")),
                Long.toString(SNAPSHOT_TTL_MILLIS),
                validOrderId.value());
    }

    private MembershipOrderTransitionResult transition(
            RedisScript<String> script,
            List<String> keys,
            Object... arguments) {
        String raw = execute(script, keys, arguments);
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 3) {
            throw unavailable("Redis membership order transition result is malformed.");
        }
        try {
            MembershipOrderTransitionOutcome outcome =
                    MembershipOrderTransitionOutcome.valueOf(parts[0]);
            MembershipOrderStatus status = parts[1].isEmpty()
                    ? null
                    : MembershipOrderStatus.valueOf(parts[1]);
            long stateVersion = Long.parseLong(parts[2]);
            return new MembershipOrderTransitionResult(outcome, status, stateVersion);
        } catch (IllegalArgumentException exception) {
            throw unavailable("Redis membership order transition result is invalid.", exception);
        }
    }

    private String execute(
            RedisScript<String> script,
            List<String> keys,
            Object... arguments) {
        try {
            String result = redisTemplate.execute(script, keys, arguments);
            if (result == null) {
                throw unavailable("Redis membership order script returned no result.");
            }
            return result;
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis membership order script failed.", exception);
        }
    }

    private long executeLong(
            RedisScript<Long> script,
            List<String> keys,
            Object... arguments) {
        try {
            Long result = redisTemplate.execute(script, keys, arguments);
            if (result == null || result < 0L) {
                throw unavailable("Redis membership order script returned an invalid count.");
            }
            return result;
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis membership order script failed.", exception);
        }
    }

    private List<String> executeList(
            RedisScript<List> script,
            List<String> keys,
            Object... arguments) {
        try {
            List<?> result = redisTemplate.execute(script, keys, arguments);
            if (result == null || result.size() > MAXIMUM_BATCH) {
                throw unavailable("Redis membership order script returned an invalid list.");
            }
            return result.stream().map(RedisMembershipOrderSnapshotStore::text).toList();
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis membership order script failed.", exception);
        }
    }

    private static List<String> boundedIds(Collection<String> ids) {
        Objects.requireNonNull(ids, "orderIds must not be null");
        List<String> values = ids.stream()
                .map(id -> new MembershipOrderRedisId(id).value())
                .distinct()
                .toList();
        if (values.size() > MAXIMUM_BATCH) {
            throw new IllegalArgumentException("Membership order Redis batch exceeds 500 IDs.");
        }
        return values;
    }

    private static List<MembershipOrderSnapshot> boundedSnapshots(
            Collection<MembershipOrderSnapshot> snapshots) {
        Objects.requireNonNull(snapshots, "snapshots must not be null");
        Map<String, MembershipOrderSnapshot> highest = new LinkedHashMap<>();
        for (MembershipOrderSnapshot snapshot : snapshots) {
            MembershipOrderSnapshot value = Objects.requireNonNull(snapshot);
            highest.merge(
                    value.orderId(),
                    value,
                    (left, right) -> left.stateVersion() >= right.stateVersion()
                            ? left
                            : right);
        }
        if (highest.size() > MAXIMUM_BATCH) {
            throw new IllegalArgumentException(
                    "Membership order Redis batch exceeds 500 snapshots.");
        }
        return List.copyOf(highest.values());
    }

    private static List<MembershipOrderPaidCommand> boundedPaidCommands(
            Collection<MembershipOrderPaidCommand> commands) {
        Objects.requireNonNull(commands, "commands must not be null");
        List<MembershipOrderPaidCommand> values = commands.stream()
                .map(Objects::requireNonNull)
                .toList();
        if (values.size() > MAXIMUM_BATCH) {
            throw new IllegalArgumentException(
                    "Membership order paid batch exceeds 500 commands.");
        }
        if (values.stream().map(MembershipOrderPaidCommand::callbackId).distinct().count()
                != values.size()) {
            throw new IllegalArgumentException(
                    "Membership order paid callback IDs must be unique.");
        }
        return values;
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private static MembershipOrderRedisId orderId(String value) {
        return new MembershipOrderRedisId(value);
    }

    private static long epochMillis(OffsetDateTime value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value.toInstant().toEpochMilli();
    }

    private static String requiredText(String name, String value, int maximumLength) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.trim())
                || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static BigDecimal requireAmount(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("paid amount must be non-negative");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "paid amount must contain at most two decimals", exception);
        }
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

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> listScript(String fileName) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/membership-payment/" + fileName));
        script.setResultType(List.class);
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
