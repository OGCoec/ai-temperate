package com.example.temperate.service.user.membership.payment.store.impl;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.common.redis.key.PaymentCallbackRedisId;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderPaidCommand;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderRealtimeGuard;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionOutcome;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCommand;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteMode;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteResult;
import com.example.temperate.service.user.membership.payment.store.MembershipProviderTradeNoPatchOutcome;
import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkAvailableEvent;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkType;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisPipelineException;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Component;

/**
 * 该实现是来使用 Redis Hash 与 Lua 维护会员订单状态快照，并把每次有效迁移原子写入版本化脏队列。
 *
 * <p>状态机只接受计划定义的迁移；Pipeline 仅用于提交有界的单订单原子脚本，不被描述为事务或强一致性保证。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class RedisMembershipOrderSnapshotStore
        implements MembershipOrderSnapshotStore {

    private static final int MAXIMUM_BATCH = 500;
    private static final int GENERAL_PIPELINE_BATCH_SIZE = 192;
    private static final long SNAPSHOT_TTL_MILLIS = Duration.ofHours(6).toMillis();
    private static final RedisScript<String> PUT = stringScript("put_order_snapshot.lua");
    private static final RedisScript<List> PUT_AND_GET = listScript(
            "put_and_get_order_snapshot.lua");
    private static final RedisScript<List> PATCH_PAYMENT_ATTEMPT = listScript(
            "patch_payment_attempt.lua");
    private static final RedisScript<String> PATCH_PROVIDER_TRADE_NO = stringScript(
            "patch_provider_trade_no.lua");
    private static final RedisScript<String> MARK_PAID = stringScript("mark_paid.lua");
    private static final RedisScript<String> CANCEL = stringScript("cancel_order.lua");
    private static final RedisScript<String> START_CLOSING =
            stringScript("start_closing.lua");
    private static final RedisScript<String> FINALIZE_CLOSING =
            stringScript("finalize_closing.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final ApplicationEventPublisher eventPublisher;
    private final Object coordinatorScriptLoadMonitor = new Object();
    private volatile boolean coordinatorScriptsLoaded;

    @Autowired
    public RedisMembershipOrderSnapshotStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            ApplicationEventPublisher eventPublisher) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
    }

    public RedisMembershipOrderSnapshotStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory) {
        this(redisTemplate, keyFactory, event -> { });
    }

    @Override
    public void put(MembershipOrderSnapshot snapshot) {
        MembershipOrderSnapshot valid = Objects.requireNonNull(snapshot);
        String outcome = execute(
                PUT,
                List.of(keyFactory.membershipOrderSnapshotKey(orderId(valid.orderId()))),
                putArguments(valid).toArray());
        requirePutOutcome(outcome);
    }

    @Override
    public void putAll(Collection<MembershipOrderSnapshot> snapshots) {
        List<MembershipOrderSnapshot> valid = boundedSnapshots(snapshots);
        if (valid.isEmpty()) {
            return;
        }
        for (int start = 0; start < valid.size(); start += GENERAL_PIPELINE_BATCH_SIZE) {
            List<MembershipOrderSnapshot> batch = valid.subList(
                    start, Math.min(start + GENERAL_PIPELINE_BATCH_SIZE, valid.size()));
            List<Object> responses = executePutPipeline(batch);
            for (Object response : responses) {
                requirePutOutcome(text(response));
            }
        }
    }

    @Override
    public MembershipOrderSnapshot putAndGet(MembershipOrderSnapshot snapshot) {
        MembershipOrderSnapshot valid = Objects.requireNonNull(snapshot);
        try {
            List<?> response = redisTemplate.execute(
                    PUT_AND_GET,
                    List.of(keyFactory.membershipOrderSnapshotKey(orderId(valid.orderId()))),
                    putArguments(valid).toArray());
            return requirePutAndGetResponse(response, valid);
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis membership order put-and-get script failed.", exception);
        }
    }

    @Override
    public List<MembershipOrderSnapshot> putAndGetAll(
            List<MembershipOrderSnapshot> snapshots) {
        List<MembershipOrderSnapshot> valid = boundedCoordinatorSnapshots(snapshots);
        if (valid.isEmpty()) {
            return List.of();
        }
        List<MembershipOrderSnapshotWriteResult> responses = writeAll(valid.stream()
                .map(snapshot -> new MembershipOrderSnapshotWriteCommand(
                        MembershipOrderSnapshotWriteMode.FULL_RESTORE, snapshot))
                .toList());
        List<MembershipOrderSnapshot> results = new ArrayList<>(responses.size());
        for (int index = 0; index < responses.size(); index++) {
            results.add(requireWriteSnapshot(responses.get(index), valid.get(index).orderId()));
        }
        return List.copyOf(results);
    }

    /**
     * 单次调用严格对应协调器的一批；Store 不再二次切批，避免两个边界叠加后破坏 lane 的耗时与顺序归因。
     */
    @Override
    public List<MembershipOrderSnapshotWriteResult> writeAll(
            List<MembershipOrderSnapshotWriteCommand> commands) {
        List<MembershipOrderSnapshotWriteCommand> valid = boundedWriteCommands(commands);
        if (valid.isEmpty()) {
            return List.of();
        }
        List<Object> responses = executeMixedWritePipeline(valid);
        List<MembershipOrderSnapshotWriteResult> results = new ArrayList<>(valid.size());
        for (int index = 0; index < valid.size(); index++) {
            MembershipOrderSnapshotWriteResult result;
            try {
                if (!(responses.get(index) instanceof List<?> reply)) {
                    throw new IllegalArgumentException("Redis write result is not a list.");
                }
                result = MembershipPaymentRedisCodec.readOrderWriteReply(
                        reply, valid.get(index).snapshot());
            } catch (RuntimeException exception) {
                throw unavailable("Redis membership order write result is invalid.", exception);
            }
            if (result.snapshot() != null
                    && !valid.get(index).snapshot().orderId().equals(result.snapshot().orderId())) {
                throw unavailable("Redis membership order write result order is inconsistent.");
            }
            results.add(result);
        }
        return List.copyOf(results);
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
    public Optional<MembershipOrderRealtimeGuard> findRealtimeGuard(String orderId) {
        MembershipOrderRedisId validOrderId = orderId(orderId);
        try {
            List<Object> values = redisTemplate.opsForHash().multiGet(
                    keyFactory.membershipOrderSnapshotKey(validOrderId),
                    List.of("orderId", "loginIdentityId", "status", "expiresAt", "stateVersion"));
            if (values.isEmpty() || values.get(0) == null) {
                return Optional.empty();
            }
            if (values.size() != 5 || values.stream().anyMatch(Objects::isNull)) {
                throw unavailable("Redis membership order realtime guard is incomplete.");
            }
            return Optional.of(new MembershipOrderRealtimeGuard(
                    text(values.get(0)),
                    Long.parseLong(text(values.get(1))),
                    MembershipOrderStatus.valueOf(text(values.get(2))),
                    MembershipPaymentTime.fromEpochMicros(Long.parseLong(text(values.get(3)))),
                    Long.parseLong(text(values.get(4)))));
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis membership order realtime guard read failed.", exception);
        }
    }

    @Override
    public Map<String, MembershipOrderSnapshot> findAll(Collection<String> orderIds) {
        List<String> requested = boundedIds(orderIds);
        if (requested.isEmpty()) {
            return Map.of();
        }
        Map<String, MembershipOrderSnapshot> snapshots = new LinkedHashMap<>();
        for (int start = 0; start < requested.size(); start += GENERAL_PIPELINE_BATCH_SIZE) {
            List<String> batch = requested.subList(
                    start,
                    Math.min(start + GENERAL_PIPELINE_BATCH_SIZE, requested.size()));
            List<Object> responses = executeFindPipeline(batch);
            for (int index = 0; index < batch.size(); index++) {
                Object response = responses.get(index);
                if (!(response instanceof Map<?, ?> raw) || raw.isEmpty()) {
                    continue;
                }
                MembershipOrderSnapshot snapshot = MembershipPaymentRedisCodec.readOrder(
                        MembershipPaymentRedisCodec.stringMap(raw));
                if (!batch.get(index).equals(snapshot.orderId())) {
                    throw unavailable("Redis membership order snapshot ID is inconsistent.");
                }
                snapshots.put(snapshot.orderId(), snapshot);
            }
        }
        return Map.copyOf(snapshots);
    }

    private List<Object> executeFindPipeline(List<String> batch) {
        try {
            // 每批最多 128 个 Hash；Pipeline 只减少网络往返，跨 Key 读取仍不构成事务快照。
            List<Object> responses = redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (String id : batch) {
                        operations.opsForHash().entries(
                                keyFactory.membershipOrderSnapshotKey(orderId(id)));
                    }
                    return null;
                }
            });
            requirePipelineSize(responses, batch.size(), "snapshot read");
            return responses;
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis membership order snapshot pipeline failed.", exception);
        }
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
    public MembershipProviderTradeNoPatchOutcome patchProviderTradeNo(
            String orderId,
            long loginIdentityId,
            String providerTradeNo) {
        if (loginIdentityId <= 0L) {
            throw new IllegalArgumentException("Login identity ID must be positive.");
        }
        MembershipOrderRedisId validOrderId = orderId(orderId);
        String raw = execute(
                PATCH_PROVIDER_TRADE_NO,
                List.of(keyFactory.membershipOrderSnapshotKey(validOrderId)),
                Long.toString(loginIdentityId),
                requiredText("provider trade number", providerTradeNo, 128));
        try {
            return MembershipProviderTradeNoPatchOutcome.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            throw unavailable("Redis provider trade number patch result is invalid.", exception);
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
        Map<String, MembershipOrderTransitionResult> results = new LinkedHashMap<>();
        boolean dirtyWorkAvailable = false;
        for (int start = 0; start < valid.size(); start += GENERAL_PIPELINE_BATCH_SIZE) {
            List<MembershipOrderPaidCommand> batch = valid.subList(
                    start, Math.min(start + GENERAL_PIPELINE_BATCH_SIZE, valid.size()));
            List<Object> responses = executeMarkPaidPipeline(batch);
            for (int index = 0; index < batch.size(); index++) {
                String callbackId = new PaymentCallbackRedisId(
                        batch.get(index).callbackId()).value();
                MembershipOrderTransitionResult result =
                        parseTransition(text(responses.get(index)));
                if (results.put(callbackId, result)
                        != null) {
                    throw unavailable("Redis membership order paid callback result is duplicated.");
                }
                dirtyWorkAvailable |= result.outcome()
                        == MembershipOrderTransitionOutcome.APPLIED;
            }
        }
        if (dirtyWorkAvailable) {
            signalDirtyWork();
        }
        return Map.copyOf(results);
    }

    private List<Object> executePutPipeline(List<MembershipOrderSnapshot> batch) {
        try {
            // 批次中每个订单仍由独立 Lua 保证原子性；Pipeline 只合并网络往返，批次失败后依靠版本幂等重试。
            List<Object> responses = redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (MembershipOrderSnapshot snapshot : batch) {
                        operations.execute(
                                PUT,
                                List.of(keyFactory.membershipOrderSnapshotKey(
                                        orderId(snapshot.orderId()))),
                                putArguments(snapshot).toArray());
                    }
                    return null;
                }
            });
            requirePipelineSize(responses, batch.size(), "snapshot put");
            return responses;
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis membership order snapshot pipeline failed.", exception);
        }
    }

    private List<Object> executeMixedWritePipeline(
            List<MembershipOrderSnapshotWriteCommand> batch) {
        try {
            ensureCoordinatorScriptsLoaded();
            List<Object> responses;
            try {
                responses = executeMixedWriteEvalShaPipeline(batch);
            } catch (RuntimeException exception) {
                if (!containsNoScript(exception)) {
                    throw exception;
                }
                // Redis 重启或 SCRIPT FLUSH 只会让 SHA 缓存失效；所有写入均有版本裁决，重载后整批重试仍幂等。
                reloadCoordinatorScripts();
                responses = executeMixedWriteEvalShaPipeline(batch);
            }
            requirePipelineSize(responses, batch.size(), "mixed snapshot write");
            return responses;
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(
                    "Redis membership order mixed write pipeline failed.", exception);
        }
    }

    private List<Object> executeMixedWriteEvalShaPipeline(
            List<MembershipOrderSnapshotWriteCommand> batch) {
        // Spring 的高层脚本执行器在 Pipeline 中会逐条退化为 EVAL；这里预载 SHA 后只发送 EVALSHA，
        // 每个订单仍执行一条独立 Lua，Pipeline 继续只负责减少网络往返而不承担事务原子性。
        return redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (MembershipOrderSnapshotWriteCommand command : batch) {
                MembershipOrderSnapshot snapshot = command.snapshot();
                RedisScript<List> script = command.mode()
                        == MembershipOrderSnapshotWriteMode.FULL_RESTORE
                                ? PUT_AND_GET
                                : PATCH_PAYMENT_ATTEMPT;
                List<Object> arguments = command.mode()
                        == MembershipOrderSnapshotWriteMode.FULL_RESTORE
                                ? putArguments(snapshot)
                                : patchPaymentAttemptArguments(snapshot);
                byte[][] keysAndArguments = new byte[arguments.size() + 1][];
                keysAndArguments[0] = StringRedisSerializer.UTF_8.serialize(
                        keyFactory.membershipOrderSnapshotKey(orderId(snapshot.orderId())));
                for (int index = 0; index < arguments.size(); index++) {
                    keysAndArguments[index + 1] = StringRedisSerializer.UTF_8.serialize(
                            String.valueOf(arguments.get(index)));
                }
                connection.scriptingCommands().evalSha(
                        script.getSha1(), ReturnType.MULTI, 1, keysAndArguments);
            }
            return null;
        }, StringRedisSerializer.UTF_8);
    }

    private void ensureCoordinatorScriptsLoaded() {
        if (coordinatorScriptsLoaded) {
            return;
        }
        synchronized (coordinatorScriptLoadMonitor) {
            if (!coordinatorScriptsLoaded) {
                loadCoordinatorScripts();
                coordinatorScriptsLoaded = true;
            }
        }
    }

    private void reloadCoordinatorScripts() {
        synchronized (coordinatorScriptLoadMonitor) {
            loadCoordinatorScripts();
            coordinatorScriptsLoaded = true;
        }
    }

    private void loadCoordinatorScripts() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            for (RedisScript<?> script : List.of(PUT_AND_GET, PATCH_PAYMENT_ATTEMPT)) {
                String loadedSha = connection.scriptingCommands().scriptLoad(
                        StringRedisSerializer.UTF_8.serialize(script.getScriptAsString()));
                if (!script.getSha1().equals(loadedSha)) {
                    throw unavailable("Redis membership order script SHA is inconsistent.");
                }
            }
            return null;
        });
    }

    private static boolean containsNoScript(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null
                    && current.getMessage().contains("NOSCRIPT")) {
                return true;
            }
            if (current instanceof RedisPipelineException pipelineException) {
                for (Object result : pipelineException.getPipelineResult()) {
                    if (result instanceof Throwable nested && containsNoScript(nested)) {
                        return true;
                    }
                    if (result != null && String.valueOf(result).contains("NOSCRIPT")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<Object> patchPaymentAttemptArguments(
            MembershipOrderSnapshot snapshot) {
        if (snapshot.paymentStartedAt() == null) {
            throw new IllegalArgumentException(
                    "Payment attempt patch requires paymentStartedAt.");
        }
        return List.of(
                Long.toString(snapshot.loginIdentityId()),
                Long.toString(snapshot.stateVersion()),
                Long.toString(MembershipPaymentTime.toEpochMicros(snapshot.paymentStartedAt())),
                Long.toString(MembershipPaymentTime.toEpochMicros(snapshot.updatedAt())),
                Long.toString(SNAPSHOT_TTL_MILLIS));
    }

    private List<Object> executeMarkPaidPipeline(List<MembershipOrderPaidCommand> batch) {
        try {
            List<Object> responses = redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (MembershipOrderPaidCommand command : batch) {
                        MembershipOrderRedisId validOrderId = orderId(command.orderId());
                        operations.execute(
                                MARK_PAID,
                                List.of(
                                        keyFactory.membershipOrderSnapshotKey(validOrderId),
                                        keyFactory.membershipOrderCallbackMarkerKey(validOrderId),
                                        keyFactory.orderPersistenceDirtyKey()),
                                markPaidArguments(command, validOrderId).toArray());
                    }
                    return null;
                }
            });
            requirePipelineSize(responses, batch.size(), "paid transition");
            return responses;
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis membership order paid pipeline failed.", exception);
        }
    }

    private static List<Object> putArguments(MembershipOrderSnapshot snapshot) {
        Map<String, String> fields = MembershipPaymentRedisCodec.writeOrder(snapshot);
        List<Object> arguments = new ArrayList<>(3 + fields.size() * 2);
        arguments.add(Long.toString(snapshot.stateVersion()));
        arguments.add(Long.toString(SNAPSHOT_TTL_MILLIS));
        arguments.add(Integer.toString(fields.size()));
        fields.forEach((name, value) -> {
            arguments.add(name);
            arguments.add(value);
        });
        return arguments;
    }

    private static List<Object> markPaidArguments(
            MembershipOrderPaidCommand command,
            MembershipOrderRedisId orderId) {
        List<Object> arguments = new ArrayList<>(8);
        arguments.add(new PaymentCallbackRedisId(command.callbackId()).value());
        arguments.add(requiredText(
                "provider trade number", command.providerTradeNo(), 128));
        arguments.add(requireAmount(command.paidAmountYuan()).toPlainString());
        // Hash 中的业务事实使用微秒；脏队列分数只承担调度，继续使用毫秒。
        arguments.add(Long.toString(epochMicros(command.paidAt(), "paidAt")));
        arguments.add(Long.toString(epochMicros(command.changedAt(), "changedAt")));
        arguments.add(Long.toString(epochMillis(command.changedAt(), "changedAt")));
        arguments.add(Long.toString(SNAPSHOT_TTL_MILLIS));
        arguments.add(orderId.value());
        return arguments;
    }

    private static MembershipOrderTransitionResult parseTransition(String raw) {
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 3) {
            throw unavailable("Redis membership order paid result is malformed.");
        }
        try {
            MembershipOrderTransitionOutcome outcome =
                    MembershipOrderTransitionOutcome.valueOf(parts[0]);
            MembershipOrderStatus status = parts[1].isEmpty()
                    ? null
                    : MembershipOrderStatus.valueOf(parts[1]);
            MembershipOrderTransitionResult result = new MembershipOrderTransitionResult(
                    outcome, status, Long.parseLong(parts[2]));
            return result;
        } catch (IllegalArgumentException exception) {
            throw unavailable("Redis membership order paid result is invalid.", exception);
        }
    }

    private void signalDirtyIfApplied(MembershipOrderTransitionResult result) {
        if (result != null && result.outcome() == MembershipOrderTransitionOutcome.APPLIED) {
            signalDirtyWork();
        }
    }

    private void signalDirtyWork() {
        eventPublisher.publishEvent(new MembershipPaymentWorkAvailableEvent(
                MembershipPaymentWorkType.ORDER_PERSIST));
    }

    private static void requirePutOutcome(String outcome) {
        if (!List.of("CREATED", "REPLACED", "UNCHANGED", "STALE").contains(outcome)) {
            throw unavailable("Unexpected membership order snapshot put result.");
        }
    }

    private static MembershipOrderSnapshot requirePutAndGetResponse(
            Object response,
            MembershipOrderSnapshot submittedSnapshot) {
        if (!(response instanceof List<?> values)) {
            throw unavailable("Redis membership order put-and-get result is malformed.");
        }
        MembershipOrderSnapshotWriteResult parsed;
        try {
            // 新写入直接复用数据库已提交快照；只有 STALE/UNCHANGED 才解析 Lua 返回的当前 Redis 胜者。
            parsed = MembershipPaymentRedisCodec.readOrderWriteReply(
                    values, submittedSnapshot);
        } catch (RuntimeException exception) {
            throw unavailable("Redis membership order put-and-get result is invalid.", exception);
        }
        if (parsed.snapshot() == null
                || !submittedSnapshot.orderId().equals(parsed.snapshot().orderId())) {
            throw unavailable("Redis membership order put-and-get ID is inconsistent.");
        }
        return parsed.snapshot();
    }

    private static MembershipOrderSnapshot requireWriteSnapshot(
            MembershipOrderSnapshotWriteResult result,
            String expectedOrderId) {
        MembershipOrderSnapshot snapshot = result.snapshot();
        if (snapshot == null || !expectedOrderId.equals(snapshot.orderId())) {
            throw unavailable("Redis membership order write snapshot is missing or inconsistent.");
        }
        return snapshot;
    }

    private static void requirePipelineSize(
            List<Object> responses,
            int expected,
            String operation) {
        if (responses.size() != expected) {
            throw unavailable("Redis membership order " + operation
                    + " pipeline result is incomplete.");
        }
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
                Long.toString(epochMicros(cancelledAt, "cancelledAt")),
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
        long deadlineMicros = epochMicros(closingDeadlineAt, "closingDeadlineAt");
        long changedMicros = epochMicros(changedAt, "changedAt");
        long dirtyScoreMillis = epochMillis(changedAt, "changedAt");
        return transition(
                START_CLOSING,
                List.of(
                        keyFactory.membershipOrderSnapshotKey(validOrderId),
                        keyFactory.orderPersistenceDirtyKey()),
                Long.toString(deadlineMicros),
                Long.toString(changedMicros),
                Long.toString(dirtyScoreMillis),
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
                Long.toString(epochMicros(changedAt, "changedAt")),
                Long.toString(epochMillis(changedAt, "changedAt")),
                Long.toString(SNAPSHOT_TTL_MILLIS),
                validOrderId.value());
    }

    /** 外部 Provider 没有本地模拟结果 Hash，因此把已核验的安全终态作为 Lua 参数参与原子关单裁决。 */
    @Override
    public MembershipOrderTransitionResult finalizeClosing(
            String orderId,
            PaymentProviderStatus providerStatus,
            OffsetDateTime changedAt) {
        MembershipOrderRedisId validOrderId = orderId(orderId);
        return transition(
                FINALIZE_CLOSING,
                List.of(
                        keyFactory.membershipOrderSnapshotKey(validOrderId),
                        keyFactory.membershipOrderCallbackMarkerKey(validOrderId),
                        keyFactory.simulatedPaymentProviderResultKey(validOrderId),
                        keyFactory.orderPersistenceDirtyKey()),
                Long.toString(epochMicros(changedAt, "changedAt")),
                Long.toString(epochMillis(changedAt, "changedAt")),
                Long.toString(SNAPSHOT_TTL_MILLIS),
                validOrderId.value(),
                Objects.requireNonNull(providerStatus).name());
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
            MembershipOrderTransitionResult result =
                    new MembershipOrderTransitionResult(outcome, status, stateVersion);
            signalDirtyIfApplied(result);
            return result;
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

    private static List<MembershipOrderSnapshot> boundedCoordinatorSnapshots(
            List<MembershipOrderSnapshot> snapshots) {
        Objects.requireNonNull(snapshots, "snapshots must not be null");
        List<MembershipOrderSnapshot> values = snapshots.stream()
                .map(Objects::requireNonNull)
                .toList();
        if (values.size() > GENERAL_PIPELINE_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "Membership order coordinator batch exceeds 192 snapshots.");
        }
        return values;
    }

    private static List<MembershipOrderSnapshotWriteCommand> boundedWriteCommands(
            List<MembershipOrderSnapshotWriteCommand> commands) {
        Objects.requireNonNull(commands, "commands must not be null");
        List<MembershipOrderSnapshotWriteCommand> values = commands.stream()
                .map(Objects::requireNonNull)
                .toList();
        if (values.size() > GENERAL_PIPELINE_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "Membership order mixed write batch exceeds 192 commands.");
        }
        return values;
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

    private static long epochMicros(OffsetDateTime value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return MembershipPaymentTime.toEpochMicros(value);
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
