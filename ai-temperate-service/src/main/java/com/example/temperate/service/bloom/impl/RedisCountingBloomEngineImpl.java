package com.example.temperate.service.bloom.impl;

import com.example.temperate.common.bloom.counting.CountingBloomPosition;
import com.example.temperate.service.bloom.CountingBloomEngine;
import com.example.temperate.service.bloom.CountingBloomEngine.BuildFence;
import com.example.temperate.service.bloom.CountingBloomNamespace;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 该实现是来用 Redis Lua 原子维护通用计数 Bloom 的全部位置和 Receipt，并将异常统一降级为不可用而非“肯定不存在”。
 */
@Service
public final class RedisCountingBloomEngineImpl implements CountingBloomEngine {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RedisCountingBloomEngineImpl.class);
    private static final String RECEIPT_SENTINEL =
            "__ait_counting_bloom_receipt__";
    private static final RedisScript<Long> QUERY = script("query.lua");
    private static final RedisScript<Long> ADD = script("add.lua");
    private static final RedisScript<Long> REMOVE = script("remove.lua");
    private static final RedisScript<Long> BEGIN_MUTATION =
            script("begin-positive-mutation.lua");
    private static final RedisScript<Long> FINISH_MUTATION =
            script("finish-positive-mutation.lua");
    private static final RedisScript<Long> BUILD_UNLINK =
            script("build-unlink.lua");
    private static final RedisScript<Long> BEGIN_BUILD =
            script("begin-build.lua");
    private static final RedisScript<Long> INITIALIZE_BUCKET =
            script("initialize-bucket.lua");
    private static final RedisScript<Long> INITIALIZE_RECEIPT =
            script("initialize-receipt.lua");
    private static final RedisScript<Long> DELETE_RECOVERED_MUTATIONS =
            script("delete-recovered-mutations.lua");
    private static final RedisScript<Long> MARK_READY =
            script("mark-ready.lua");
    private static final RedisScript<Long> ACTIVATE = script("activate.lua");
    private static final RedisScript<Long> MARK_BUILD_DEGRADED =
            script("mark-build-degraded.lua");
    private static final RedisScript<Long> RENEW_LEADER =
            script("renew-leader.lua");

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    public RedisCountingBloomEngineImpl(
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    public LookupResult lookup(
            CountingBloomNamespace namespace,
            String protectedIdentifier) {
        try {
            Long result = redisTemplate.execute(
                    QUERY,
                    queryKeys(namespace),
                    positionArguments(namespace, protectedIdentifier, false));
            LookupResult mapped = switch (result == null ? -1 : result.intValue()) {
                case 0 -> LookupResult.DEFINITELY_NOT_PRESENT;
                case 1 -> LookupResult.MAYBE_PRESENT;
                default -> LookupResult.UNAVAILABLE;
            };
            counter("lookup", mapped.name().toLowerCase());
            return mapped;
        } catch (RuntimeException exception) {
            counter("lookup", "redis_failure");
            LOGGER.warn(
                    "event=counting_bloom_lookup_unavailable traceId={} cause={}",
                    traceId(),
                    exception.getClass().getSimpleName());
            return LookupResult.UNAVAILABLE;
        }
    }

    @Override
    public UpdateResult add(
            CountingBloomNamespace namespace,
            String protectedIdentifier) {
        return update(namespace, protectedIdentifier, ADD, "add");
    }

    @Override
    public long addBatch(
            CountingBloomNamespace namespace,
            List<String> protectedIdentifiers) {
        return addBatchAndCountUpdates(namespace, protectedIdentifiers, null);
    }

    @Override
    public long addBuildBatch(
            CountingBloomNamespace namespace,
            List<String> protectedIdentifiers,
            BuildFence fence) {
        Objects.requireNonNull(fence, "fence");
        return addBatchAndCountUpdates(namespace, protectedIdentifiers, fence);
    }

    private long addBatchAndCountUpdates(
            CountingBloomNamespace namespace,
            List<String> protectedIdentifiers,
            BuildFence fence) {
        if (protectedIdentifiers == null
                || protectedIdentifiers.isEmpty()
                || protectedIdentifiers.size() > 500) {
            throw new IllegalArgumentException("Counting Bloom batch must contain 1 to 500 elements");
        }
        if (protectedIdentifiers.stream().anyMatch(RECEIPT_SENTINEL::equals)) {
            throw new IllegalArgumentException(
                    "Counting Bloom batch contains the reserved receipt sentinel");
        }
        try {
            if (fence != null) {
                renewBuildFence(fence);
            }
            // 一个批次在同一 Pipeline 中排队 Lua；循环只组装命令，不产生逐元素网络 RTT。
            List<Object> responses = redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (String identifier : protectedIdentifiers) {
                        operations.execute(
                                ADD,
                                updateKeys(namespace, identifier, fence),
                                positionArguments(
                                        namespace,
                                        identifier,
                                        true,
                                        fence));
                    }
                    return null;
                }
            });
            if (responses.stream().anyMatch(value -> value instanceof Number number
                    && number.longValue() == -4L)) {
                throw new BuildFenceLostException();
            }
            if (responses.size() != protectedIdentifiers.size()
                    || responses.stream().anyMatch(value -> !(value instanceof Number number)
                    || number.longValue() < 1L)) {
                degradeForBatchFailure(namespace, fence, "batch_add_failed");
                throw new IllegalStateException("Counting Bloom batch add did not fully apply");
            }
            counter("add_batch", "updated");
            return responses.stream()
                    .filter(value -> value instanceof Number number
                            && number.longValue() == 1L)
                    .count();
        } catch (BuildFenceLostException exception) {
            counter("add_batch", "fence_lost");
            throw exception;
        } catch (RuntimeException exception) {
            degradeForBatchFailure(namespace, fence, "batch_add_exception");
            throw exception;
        }
    }

    @Override
    public UpdateResult remove(
            CountingBloomNamespace namespace,
            String protectedIdentifier) {
        return update(namespace, protectedIdentifier, REMOVE, "remove");
    }

    @Override
    public void beginPositiveMutation(
            CountingBloomNamespace namespace,
            String mutationId,
            String protectedIdentifier) {
        try {
            Long result = redisTemplate.execute(
                    BEGIN_MUTATION,
                    List.of(namespace.metaKey(), namespace.positiveMutationKey()),
                    mutationId,
                    protectedIdentifier);
            if (result == null || result != 1L) {
                throw new IllegalStateException("Counting Bloom metadata is unavailable");
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Counting Bloom positive mutation could not begin", exception);
        }
    }

    @Override
    public void finishPositiveMutation(
            CountingBloomNamespace namespace,
            String mutationId,
            boolean safeToComplete) {
        try {
            redisTemplate.execute(
                    FINISH_MUTATION,
                    List.of(namespace.metaKey(), namespace.positiveMutationKey()),
                    mutationId,
                    safeToComplete ? "1" : "0");
        } catch (RuntimeException exception) {
            markDegraded(namespace, "positive_mutation_finish_failed");
        }
    }

    @Override
    public void initializeBuilding(
            CountingBloomNamespace namespace,
            BuildFence fence) {
        Objects.requireNonNull(fence, "fence");
        List<String> keys = new ArrayList<>();
        keys.add(namespace.metaKey());
        keys.addAll(namespace.bucketKeys());
        keys.addAll(namespace.receiptKeys());
        for (int offset = 0; offset < keys.size(); offset += 500) {
            renewBuildFence(fence);
            List<String> unlinkKeys = new ArrayList<>();
            unlinkKeys.add(fence.leaderKey());
            unlinkKeys.addAll(keys.subList(
                    offset, Math.min(keys.size(), offset + 500)));
            requireBuildWrite(redisTemplate.execute(
                    BUILD_UNLINK,
                    List.copyOf(unlinkKeys),
                    fence.leaseValue()));
        }
        renewBuildFence(fence);
        requireBuildWrite(redisTemplate.execute(
                BEGIN_BUILD,
                List.of(
                        fence.leaderKey(),
                        namespace.metaKey(),
                        namespace.positiveMutationKey()),
                fence.leaseValue(),
                Long.toString(fence.epoch()),
                Integer.toString(namespace.layout().capacity()),
                Integer.toString(namespace.layout().hashCount()),
                Integer.toString(namespace.layout().counterBytes()),
                Integer.toString(namespace.layout().countersPerBucket()),
                Integer.toString(namespace.layout().bucketCount())));
        renewBuildFence(fence);
        List<Object> initialized = redisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            public Object execute(RedisOperations operations) {
                for (int bucket = 0; bucket < namespace.bucketKeys().size(); bucket++) {
                    // StringRedisTemplate 的 Lua 参数使用字符串序列化；布局整数只在 Redis 边界编码。
                    operations.execute(
                            INITIALIZE_BUCKET,
                            List.of(
                                    fence.leaderKey(),
                                    namespace.metaKey(),
                                    namespace.bucketKeys().get(bucket)),
                            fence.leaseValue(),
                            Long.toString(fence.epoch()),
                            Integer.toString(namespace.layout().bucketByteLength(bucket)));
                }
                return null;
            }
        });
        if (containsFenceLoss(initialized)) {
            throw new BuildFenceLostException();
        }
        if (initialized.size() != namespace.bucketKeys().size()
                || initialized.stream().anyMatch(
                        value -> !(value instanceof Number number)
                                || number.longValue() <= 0L)) {
            markBuildDegraded(namespace, fence, "bucket_initialization_failed");
            throw new IllegalStateException("Counting Bloom buckets were not initialized");
        }
        initializeReceiptShards(namespace, fence);
    }

    @Override
    public long recoverPositiveMutations(
            CountingBloomNamespace namespace,
            BuildFence fence) {
        renewBuildFence(fence);
        long newlyAdded = 0L;
        List<String> mutationIds = new ArrayList<>(500);
        List<String> identifiers = new ArrayList<>(500);
        // HSCAN 只产生有界批次；扫描期间新加入或未被本轮看到的 mutation 会令最终激活失败并留给下一轮，不能被误删。
        try (Cursor<Map.Entry<Object, Object>> cursor =
                     redisTemplate.opsForHash().scan(
                             namespace.positiveMutationKey(),
                             ScanOptions.scanOptions().count(500).build())) {
            while (cursor.hasNext()) {
                Map.Entry<Object, Object> entry = cursor.next();
                mutationIds.add(entry.getKey().toString());
                identifiers.add(entry.getValue().toString());
                if (mutationIds.size() == 500) {
                    newlyAdded += recoverMutationBatch(
                            namespace, fence, mutationIds, identifiers);
                    mutationIds.clear();
                    identifiers.clear();
                }
            }
            if (!mutationIds.isEmpty()) {
                newlyAdded += recoverMutationBatch(
                        namespace, fence, mutationIds, identifiers);
            }
        }
        return newlyAdded;
    }

    private long recoverMutationBatch(
            CountingBloomNamespace namespace,
            BuildFence fence,
            List<String> mutationIds,
            List<String> identifiers) {
        // 重建快照可能尚未看到刚提交或即将提交的行；先重放摘要最多只增加假阳性，绝不能留下假阴性。
        long newlyAdded = addBatchAndCountUpdates(
                namespace,
                List.copyOf(new LinkedHashSet<>(identifiers)),
                fence);
        renewBuildFence(fence);
        List<Object> arguments = new ArrayList<>(mutationIds.size() + 2);
        arguments.add(fence.leaseValue());
        arguments.add(Long.toString(fence.epoch()));
        arguments.addAll(mutationIds);
        Long remaining = redisTemplate.execute(
                DELETE_RECOVERED_MUTATIONS,
                List.of(
                        fence.leaderKey(),
                        namespace.metaKey(),
                        namespace.positiveMutationKey()),
                arguments.toArray());
        if (remaining != null && remaining == -4L) {
            throw new BuildFenceLostException();
        }
        if (remaining == null || remaining < 0L) {
            markBuildDegraded(
                    namespace, fence, "mutation_recovery_delete_failed");
            throw new IllegalStateException(
                    "Counting Bloom recovered mutations could not be acknowledged");
        }
        return newlyAdded;
    }

    @Override
    public boolean validateAndActivate(
            CountingBloomNamespace namespace,
            long minimumElementCount,
            long maximumElementCount,
            BuildFence fence) {
        try {
            renewBuildFence(fence);
            Object countValue = redisTemplate.opsForHash().get(
                    namespace.metaKey(), "element_count");
            long actualCount = countValue == null
                    ? -1L : Long.parseLong(countValue.toString());
            // 重建期间并发 mutation 可以在分页游标之外合法增加元素，因此只要求不低于构建确认数且不超过规划上限。
            if (actualCount < minimumElementCount || actualCount > maximumElementCount) {
                markBuildDegraded(namespace, fence, "element_count_mismatch");
                return false;
            }
            for (int bucket = 0; bucket < namespace.bucketKeys().size(); bucket++) {
                renewBuildFence(fence);
                Long length = redisTemplate.opsForValue().size(namespace.bucketKeys().get(bucket));
                if (length == null || length != namespace.layout().bucketByteLength(bucket)) {
                    markBuildDegraded(namespace, fence, "bucket_length_mismatch");
                    return false;
                }
            }
            if (!receiptShardsValid(namespace, fence)) {
                markBuildDegraded(namespace, fence, "receipt_validation_failed");
                return false;
            }
            renewBuildFence(fence);
            Long ready = redisTemplate.execute(
                    MARK_READY,
                    List.of(fence.leaderKey(), namespace.metaKey()),
                    fence.leaseValue(),
                    Long.toString(fence.epoch()),
                    Long.toString(minimumElementCount),
                    Long.toString(maximumElementCount));
            if (ready != null && ready == -4L) {
                throw new BuildFenceLostException();
            }
            if (ready == null || ready != 1L) {
                return false;
            }
            // READY→ACTIVE 与 pending mutation 检查必须在单个 Lua 中完成，防止并发创建在检查后被错误覆盖为 ACTIVE。
            Long activated = redisTemplate.execute(
                    ACTIVATE,
                    List.of(
                            fence.leaderKey(),
                            namespace.metaKey(),
                            namespace.positiveMutationKey()),
                    fence.leaseValue(),
                    Long.toString(fence.epoch()));
            if (activated != null && activated == -4L) {
                throw new BuildFenceLostException();
            }
            if (activated != null && activated == 1L) {
                return true;
            }
            markBuildDegraded(namespace, fence, "positive_mutation_pending");
            return false;
        } catch (BuildFenceLostException exception) {
            counter("activation", "fence_lost");
            throw exception;
        } catch (RuntimeException exception) {
            markBuildDegraded(namespace, fence, "activation_validation_failed");
            return false;
        }
    }

    @Override
    public void markDegraded(CountingBloomNamespace namespace, String reason) {
        try {
            redisTemplate.opsForHash().putAll(namespace.metaKey(), Map.of(
                    "state", "DEGRADED",
                    "reason", safeReason(reason)));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "event=counting_bloom_degrade_write_failed traceId={} cause={}",
                    traceId(),
                    exception.getClass().getSimpleName());
        }
        counter("state", "degraded");
    }

    @Override
    public void markBuildDegraded(
            CountingBloomNamespace namespace,
            BuildFence fence,
            String reason) {
        try {
            Long result = redisTemplate.execute(
                    MARK_BUILD_DEGRADED,
                    List.of(fence.leaderKey(), namespace.metaKey()),
                    fence.leaseValue(),
                    Long.toString(fence.epoch()),
                    safeReason(reason));
            if (result != null && result == 1L) {
                counter("state", "degraded");
            } else if (result != null && result == -4L) {
                counter("state", "fence_lost");
            }
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "event=counting_bloom_build_degrade_write_failed traceId={} cause={}",
                    traceId(),
                    exception.getClass().getSimpleName());
        }
    }

    private UpdateResult update(
            CountingBloomNamespace namespace,
            String identifier,
            RedisScript<Long> script,
            String operation) {
        try {
            if (RECEIPT_SENTINEL.equals(identifier)) {
                throw new IllegalArgumentException(
                        "Counting Bloom identifier uses the reserved receipt sentinel");
            }
            Long result = redisTemplate.execute(
                    script,
                    updateKeys(namespace, identifier),
                    positionArguments(namespace, identifier, true));
            if (result != null && result == -1L) {
                counter(operation, "counter_boundary_violation");
            } else if (result != null && result < 0L) {
                counter(operation, "metadata_invalid");
            }
            UpdateResult mapped = switch (result == null ? -3 : result.intValue()) {
                case 1 -> UpdateResult.UPDATED;
                case 2 -> UpdateResult.ALREADY_APPLIED;
                default -> UpdateResult.UNAVAILABLE;
            };
            counter(operation, mapped.name().toLowerCase());
            return mapped;
        } catch (RuntimeException exception) {
            markDegraded(namespace, operation + "_exception");
            return UpdateResult.UNAVAILABLE;
        }
    }

    private static List<String> queryKeys(CountingBloomNamespace namespace) {
        List<String> keys = new ArrayList<>(1 + namespace.bucketKeys().size());
        keys.add(namespace.metaKey());
        keys.addAll(namespace.bucketKeys());
        return List.copyOf(keys);
    }

    private void initializeReceiptShards(
            CountingBloomNamespace namespace,
            BuildFence fence) {
        for (int offset = 0; offset < namespace.receiptKeys().size(); offset += 500) {
            renewBuildFence(fence);
            List<String> batch = namespace.receiptKeys().subList(
                    offset, Math.min(namespace.receiptKeys().size(), offset + 500));
            List<Object> responses = redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (String key : batch) {
                        operations.execute(
                                INITIALIZE_RECEIPT,
                                List.of(fence.leaderKey(), namespace.metaKey(), key),
                                fence.leaseValue(),
                                Long.toString(fence.epoch()));
                    }
                    return null;
                }
            });
            if (containsFenceLoss(responses)) {
                throw new BuildFenceLostException();
            }
            if (responses.size() != batch.size()
                    || responses.stream().anyMatch(value -> !(value instanceof Number number)
                    || number.longValue() != 1L)) {
                markBuildDegraded(namespace, fence, "receipt_initialization_failed");
                throw new IllegalStateException(
                        "Counting Bloom receipt shards were not initialized");
            }
        }
    }

    private boolean receiptShardsValid(
            CountingBloomNamespace namespace,
            BuildFence fence) {
        for (int offset = 0; offset < namespace.receiptKeys().size(); offset += 500) {
            renewBuildFence(fence);
            List<String> batch = namespace.receiptKeys().subList(
                    offset, Math.min(namespace.receiptKeys().size(), offset + 500));
            List<Object> responses = redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (String key : batch) {
                        operations.opsForSet().isMember(key, RECEIPT_SENTINEL);
                    }
                    return null;
                }
            });
            if (responses.size() != batch.size()
                    || responses.stream().anyMatch(value -> !Boolean.TRUE.equals(value))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> updateKeys(
            CountingBloomNamespace namespace,
            String protectedIdentifier) {
        return updateKeys(namespace, protectedIdentifier, null);
    }

    private static List<String> updateKeys(
            CountingBloomNamespace namespace,
            String protectedIdentifier,
            BuildFence fence) {
        List<String> keys = new ArrayList<>(2 + namespace.bucketKeys().size());
        keys.add(namespace.metaKey());
        int receiptShard = Math.floorMod(
                protectedIdentifier.hashCode(), namespace.receiptKeys().size());
        keys.add(namespace.receiptKeys().get(receiptShard));
        keys.addAll(namespace.bucketKeys());
        if (fence != null) {
            keys.add(fence.leaderKey());
        }
        return List.copyOf(keys);
    }

    private static Object[] positionArguments(
            CountingBloomNamespace namespace,
            String protectedIdentifier,
            boolean includeIdentifier) {
        return positionArguments(
                namespace, protectedIdentifier, includeIdentifier, null);
    }

    private static Object[] positionArguments(
            CountingBloomNamespace namespace,
            String protectedIdentifier,
            boolean includeIdentifier,
            BuildFence fence) {
        List<CountingBloomPosition> positions =
                namespace.layout().positions(protectedIdentifier);
        List<Object> arguments = new ArrayList<>(positions.size() * 2 + 1);
        if (includeIdentifier) {
            arguments.add(protectedIdentifier);
        }
        arguments.add(Integer.toString(namespace.layout().capacity()));
        arguments.add(Integer.toString(namespace.layout().hashCount()));
        arguments.add(Integer.toString(namespace.layout().counterBytes()));
        arguments.add(Integer.toString(namespace.layout().countersPerBucket()));
        arguments.add(Integer.toString(namespace.layout().bucketCount()));
        for (CountingBloomPosition position : positions) {
            // Lua 的 KEYS 从一开始：查询首个 Bucket 位于 2，更新首个 Bucket 位于 3。
            // 位置仍以整数完成 Java 计算，只在进入 StringRedisTemplate 时编码；Lua 使用 tonumber 恢复数值。
            arguments.add(Integer.toString(
                    position.bucketNumber() + (includeIdentifier ? 3 : 2)));
            arguments.add(Integer.toString(position.byteOffset()));
        }
        if (fence != null) {
            arguments.add("BUILD_FENCE");
            arguments.add(fence.leaseValue());
            arguments.add(Long.toString(fence.epoch()));
        }
        return arguments.toArray();
    }

    private void renewBuildFence(BuildFence fence) {
        Long renewed = redisTemplate.execute(
                RENEW_LEADER,
                List.of(fence.leaderKey()),
                fence.leaseValue(),
                Long.toString(fence.leaseTtl().toMillis()));
        if (renewed == null || renewed != 1L) {
            throw new BuildFenceLostException();
        }
    }

    private void degradeForBatchFailure(
            CountingBloomNamespace namespace,
            BuildFence fence,
            String reason) {
        if (fence == null) {
            markDegraded(namespace, reason);
        } else {
            markBuildDegraded(namespace, fence, reason);
        }
    }

    private static boolean containsFenceLoss(List<Object> responses) {
        return responses.stream().anyMatch(value -> value instanceof Number number
                && number.longValue() == -4L);
    }

    private static void requireBuildWrite(Long result) {
        if (result != null && result == -4L) {
            throw new BuildFenceLostException();
        }
        if (result == null || result < 0L) {
            throw new IllegalStateException("Counting Bloom fenced build write failed");
        }
    }

    private static String safeReason(String reason) {
        if (reason == null || !reason.matches("[a-z0-9_]{1,64}")) {
            return "unspecified_failure";
        }
        return reason;
    }

    private void counter(String operation, String result) {
        meterRegistry.counter(
                "counting.bloom.operation",
                "operation", operation,
                "result", result).increment();
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank() ? "background" : value;
    }

    private static RedisScript<Long> script(String fileName) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(
                "redis-scripts/counting-bloom/" + fileName));
        script.setResultType(Long.class);
        return script;
    }

    /** 该异常用于阻止旧 Leader 在租约失效后继续修改固定 v1 共享结构，不触碰新 Leader 的状态。 */
    private static final class BuildFenceLostException extends IllegalStateException {

        private BuildFenceLostException() {
            super("Counting Bloom build fence was lost");
        }
    }
}
