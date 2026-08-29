package com.example.temperate.service.bloom.impl;

import com.example.temperate.common.bloom.counting.CountingBloomPosition;
import com.example.temperate.service.bloom.VersionedCompositeCountingBloomEngine;
import com.example.temperate.service.bloom.VersionedCompositeCountingBloomEngine.CompositeRecord;
import com.example.temperate.service.bloom.VersionedCompositeCountingBloomEngine.Field;
import com.example.temperate.service.bloom.VersionedCompositeCountingBloomEngine.Generation;
import com.example.temperate.service.bloom.VersionedCompositeCountingBloomEngine.Namespace;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 该实现是来用 Redis String Bucket、分片 Set 幂等凭据和 Lua 状态机持久化双版本、双字段计数 Bloom。
 *
 * <p>所有计数器增删先在 Lua 内汇总相同偏移量的变化并完成全量上下限检查，再原子写入主字段、可选次字段和
 * Receipt；脚本返回前发生的任何业务拒绝都不会留下部分计数。</p>
 */
@Service
public final class RedisVersionedCompositeCountingBloomEngineImpl
        implements VersionedCompositeCountingBloomEngine {

    private static final DefaultRedisScript<Long> INITIALIZE_BUCKET_SCRIPT =
            new DefaultRedisScript<>(
                    "for index = 1, #KEYS do "
                            + "  redis.call('UNLINK', KEYS[index]) "
                            + "  redis.call('SETRANGE', KEYS[index], "
                            + "      tonumber(ARGV[index]) - 1, string.char(0)) "
                            + "end "
                            + "return #KEYS",
                    Long.class);

    private static final DefaultRedisScript<String> BEGIN_BUILD_SCRIPT =
            script("begin_build.lua", String.class);

    private static final DefaultRedisScript<Long> QUERY_SCRIPT =
            script("query.lua", Long.class);

    private static final DefaultRedisScript<Long> ADD_BATCH_SCRIPT =
            script("add_batch.lua", Long.class);

    private static final DefaultRedisScript<Long> REMOVE_BATCH_SCRIPT =
            script("remove_batch.lua", Long.class);

    private static final DefaultRedisScript<Long> MARK_READY_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('HGET', KEYS[1], 'state') ~= 'BUILDING' "
                            + "    or redis.call('HGET', KEYS[1], 'buildingGeneration') ~= ARGV[1] then "
                            + "  return 0 "
                            + "end "
                            + "redis.call('HSET', KEYS[1], 'state', 'READY') "
                            + "return 1",
                    Long.class);

    private static final DefaultRedisScript<Long> ACTIVATE_SCRIPT =
            script("activate.lua", Long.class);

    private static final DefaultRedisScript<Long> RENEW_LEASE_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end "
                            + "redis.call('PEXPIRE', KEYS[1], ARGV[2]) "
                            + "return 1",
                    Long.class);

    private static final DefaultRedisScript<Long> RELEASE_LEASE_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end "
                            + "redis.call('DEL', KEYS[1]) "
                            + "return 1",
                    Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisVersionedCompositeCountingBloomEngineImpl(
            StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
    }

    @Override
    public LookupResult lookup(
            Namespace namespace,
            Field field,
            String protectedIdentifier) {
        Objects.requireNonNull(namespace);
        Objects.requireNonNull(field);
        if (protectedIdentifier == null || protectedIdentifier.isBlank()) {
            throw new IllegalArgumentException("Protected Bloom identifier must not be blank");
        }
        List<CountingBloomPosition> positions =
                namespace.layout().positions(protectedIdentifier);
        List<String> arguments = baseConfigurationArguments(namespace);
        // Redis 元数据字段沿用既有 Email/Phone 名称以兼容已部署代次；通用接口只暴露主、次字段语义。
        arguments.add(field == Field.PRIMARY
                ? "activeEmailBucket:"
                : "activePhoneBucket:");
        arguments.add(Integer.toString(positions.size()));
        appendPositions(arguments, positions);
        Long result = redisTemplate.execute(
                QUERY_SCRIPT,
                List.of(namespace.controlKey()),
                arguments.toArray());
        if (result != null && result == -2L) {
            throw new IllegalStateException(
                    "Versioned composite Bloom active metadata is inconsistent");
        }
        if (result == null || result < 0L) {
            return LookupResult.UNAVAILABLE;
        }
        return result == 0L
                ? LookupResult.DEFINITELY_NOT_PRESENT
                : LookupResult.MAYBE_PRESENT;
    }

    @Override
    public MutationResult addAll(
            Namespace namespace,
            List<CompositeRecord> records) {
        return mutate(namespace, records, ADD_BATCH_SCRIPT, false);
    }

    @Override
    public MutationResult removeAll(
            Namespace namespace,
            List<CompositeRecord> records) {
        return mutate(namespace, records, REMOVE_BATCH_SCRIPT, true);
    }

    private MutationResult mutate(
            Namespace namespace,
            List<CompositeRecord> records,
            DefaultRedisScript<Long> script,
            boolean removal) {
        Objects.requireNonNull(namespace);
        if (records == null || records.isEmpty()) {
            return MutationResult.ALREADY_APPLIED;
        }
        if (records.size() > namespace.buildBatchSize()) {
            throw new IllegalArgumentException(
                    "Versioned composite Bloom batch exceeds configured boundary");
        }
        List<String> arguments = baseConfigurationArguments(namespace);
        arguments.add(Integer.toString(records.size()));
        for (CompositeRecord record : records) {
            appendRecord(namespace, arguments, Objects.requireNonNull(record));
        }
        Long result = redisTemplate.execute(
                script,
                List.of(namespace.controlKey()),
                arguments.toArray());
        if (result == null || result == -1L) {
            return MutationResult.UNAVAILABLE;
        }
        if (!removal && result == -2L) {
            return MutationResult.OVERFLOW;
        }
        if (!removal && result == -3L) {
            return MutationResult.CAPACITY_EXCEEDED;
        }
        if (removal && result == -4L) {
            return MutationResult.UNDERFLOW;
        }
        return result == 0L
                ? MutationResult.ALREADY_APPLIED
                : MutationResult.UPDATED;
    }

    @Override
    public boolean tryAcquireBuildLease(
            Namespace namespace,
            String leaseToken,
            Duration ttl) {
        Objects.requireNonNull(namespace);
        requireLease(leaseToken, ttl);
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                namespace.buildLeaseKey(), leaseToken, ttl));
    }

    @Override
    public boolean renewBuildLease(
            Namespace namespace,
            String leaseToken,
            Duration ttl) {
        Objects.requireNonNull(namespace);
        requireLease(leaseToken, ttl);
        Long result = redisTemplate.execute(
                RENEW_LEASE_SCRIPT,
                List.of(namespace.buildLeaseKey()),
                leaseToken,
                Long.toString(ttl.toMillis()));
        return result != null && result == 1L;
    }

    @Override
    public String beginBuild(
            Namespace namespace,
            Generation generation) {
        Objects.requireNonNull(namespace);
        validateGeneration(namespace, generation);
        cleanupAbandonedBuild(namespace);
        initializeGenerationBuckets(namespace, generation);
        writeGenerationMetadata(namespace, generation);

        Map<String, String> control = new LinkedHashMap<>();
        control.put("state", "BUILDING");
        control.put("buildingGeneration", generation.name());
        control.put("buildingMetaKey", generation.metaKey());
        control.put("capacity", Integer.toString(namespace.layout().capacity()));
        control.put("hashCount", Integer.toString(namespace.layout().hashCount()));
        control.put("counterBytes", Integer.toString(namespace.layout().counterBytes()));
        control.put("countersPerBucket", Integer.toString(
                namespace.layout().countersPerBucket()));
        control.put("bucketCount", Integer.toString(namespace.layout().bucketCount()));
        control.put("receiptShards", Integer.toString(namespace.receiptShards()));
        control.put("maximumElements", Integer.toString(namespace.maximumElements()));
        control.put("buildingCount", "0");
        for (int bucket = 0; bucket < namespace.layout().bucketCount(); bucket++) {
            String suffix = fixedNumber(bucket);
            control.put(
                    "buildingEmailBucket:" + suffix,
                    generation.primaryBucketKeys().get(bucket));
            control.put(
                    "buildingPhoneBucket:" + suffix,
                    generation.secondaryBucketKeys().get(bucket));
        }
        for (int shard = 0; shard < namespace.receiptShards(); shard++) {
            control.put(
                    "buildingReceipt:" + fixedNumber(shard),
                    generation.receiptKeys().get(shard));
        }
        List<String> arguments = new ArrayList<>(control.size() * 2);
        control.forEach((field, value) -> {
            arguments.add(field);
            arguments.add(value);
        });
        String previousGeneration = redisTemplate.execute(
                BEGIN_BUILD_SCRIPT,
                List.of(namespace.controlKey()),
                arguments.toArray());
        return previousGeneration == null || previousGeneration.isBlank()
                ? null
                : previousGeneration;
    }

    @Override
    public void markReady(Namespace namespace, String generation) {
        requireSuccessfulTransition(
                redisTemplate.execute(
                        MARK_READY_SCRIPT,
                        List.of(namespace.controlKey()),
                        generation),
                "BUILDING to READY");
    }

    @Override
    public void activate(Namespace namespace, String generation) {
        requireSuccessfulTransition(
                redisTemplate.execute(
                        ACTIVATE_SCRIPT,
                        List.of(namespace.controlKey()),
                        generation),
                "READY to ACTIVE");
    }

    @Override
    public void cleanupGeneration(Generation generation) {
        if (generation == null) {
            return;
        }
        unlinkInBatches(generationKeys(generation));
    }

    @Override
    public void markDegraded(Namespace namespace, String reason) {
        Objects.requireNonNull(namespace);
        if (reason == null || !reason.matches("^[A-Z0-9_]{1,64}$")) {
            throw new IllegalArgumentException("Bloom degraded reason is invalid");
        }
        redisTemplate.opsForHash().putAll(
                namespace.controlKey(),
                Map.of("state", "DEGRADED", "degradedReason", reason));
    }

    @Override
    public void releaseBuildLease(Namespace namespace, String leaseToken) {
        Objects.requireNonNull(namespace);
        if (leaseToken == null || leaseToken.isBlank()) {
            return;
        }
        redisTemplate.execute(
                RELEASE_LEASE_SCRIPT,
                List.of(namespace.buildLeaseKey()),
                leaseToken);
    }

    private void initializeGenerationBuckets(
            Namespace namespace,
            Generation generation) {
        List<String> keys = new ArrayList<>(namespace.layout().bucketCount() * 2);
        List<String> byteLengths = new ArrayList<>(namespace.layout().bucketCount() * 2);
        for (int bucket = 0; bucket < namespace.layout().bucketCount(); bucket++) {
            String byteLength = Integer.toString(
                    namespace.layout().bucketByteLength(bucket));
            keys.add(generation.primaryBucketKeys().get(bucket));
            byteLengths.add(byteLength);
            keys.add(generation.secondaryBucketKeys().get(bucket));
            byteLengths.add(byteLength);
        }
        if (keys.size() > 500) {
            throw new IllegalStateException(
                    "Versioned composite Bloom initialization exceeds the 500-Key Redis batch boundary");
        }
        redisTemplate.execute(
                INITIALIZE_BUCKET_SCRIPT, keys, byteLengths.toArray());
    }

    private void cleanupAbandonedBuild(Namespace namespace) {
        Map<Object, Object> control = redisTemplate.opsForHash().entries(
                namespace.controlKey());
        if (control == null || control.isEmpty()) {
            return;
        }
        String state = value(control.get("state"));
        String buildingGeneration = value(control.get("buildingGeneration"));
        String activeGeneration = value(control.get("activeGeneration"));
        if (buildingGeneration == null
                || buildingGeneration.equals(activeGeneration)
                || !("BUILDING".equals(state)
                || "READY".equals(state)
                || "DEGRADED".equals(state))) {
            return;
        }
        List<String> abandonedKeys = new ArrayList<>();
        addStoredKey(control, "buildingMetaKey", abandonedKeys);
        for (int bucket = 0; bucket < namespace.layout().bucketCount(); bucket++) {
            String suffix = fixedNumber(bucket);
            addStoredKey(control, "buildingEmailBucket:" + suffix, abandonedKeys);
            addStoredKey(control, "buildingPhoneBucket:" + suffix, abandonedKeys);
        }
        for (int shard = 0; shard < namespace.receiptShards(); shard++) {
            addStoredKey(control, "buildingReceipt:" + fixedNumber(shard), abandonedKeys);
        }
        if (!abandonedKeys.isEmpty()) {
            // 当前实例已经取得构建租约，只按控制元数据中的受限 Key 列表 UNLINK，禁止使用 Redis KEYS 扫描。
            unlinkInBatches(abandonedKeys);
        }
    }

    private void writeGenerationMetadata(
            Namespace namespace,
            Generation generation) {
        redisTemplate.opsForHash().putAll(
                generation.metaKey(),
                Map.of(
                        "capacity", Integer.toString(namespace.layout().capacity()),
                        "hashCount", Integer.toString(namespace.layout().hashCount()),
                        "counterBytes", Integer.toString(namespace.layout().counterBytes()),
                        "countersPerBucket", Integer.toString(
                                namespace.layout().countersPerBucket()),
                        "bucketCount", Integer.toString(namespace.layout().bucketCount()),
                        "receiptShards", Integer.toString(namespace.receiptShards()),
                        "maximumElements", Integer.toString(namespace.maximumElements())));
    }

    private Collection<String> generationKeys(Generation generation) {
        List<String> keys = new ArrayList<>(
                generation.primaryBucketKeys().size()
                        + generation.secondaryBucketKeys().size()
                        + generation.receiptKeys().size()
                        + 1);
        keys.add(generation.metaKey());
        keys.addAll(generation.primaryBucketKeys());
        keys.addAll(generation.secondaryBucketKeys());
        keys.addAll(generation.receiptKeys());
        return List.copyOf(keys);
    }

    private void unlinkInBatches(Collection<String> keys) {
        List<String> boundedKeys = List.copyOf(keys);
        for (int start = 0; start < boundedKeys.size(); start += 500) {
            int end = Math.min(start + 500, boundedKeys.size());
            // UNLINK 每批最多 500 个已知 Key；批次间允许短暂残留，并由重复清理收敛。
            redisTemplate.unlink(boundedKeys.subList(start, end));
        }
    }

    private void appendRecord(
            Namespace namespace,
            List<String> arguments,
            CompositeRecord record) {
        if (record.receiptShard() >= namespace.receiptShards()) {
            throw new IllegalArgumentException(
                    "Versioned composite Bloom receipt shard is out of range");
        }
        arguments.add(record.receiptIdentifier());
        arguments.add(fixedNumber(record.receiptShard()));
        appendPositions(
                arguments,
                namespace.layout().positions(record.primaryIdentifier()));
        if (record.secondaryIdentifier() == null) {
            arguments.add("0");
            return;
        }
        arguments.add("1");
        appendPositions(
                arguments,
                namespace.layout().positions(record.secondaryIdentifier()));
    }

    private static void appendPositions(
            List<String> arguments, List<CountingBloomPosition> positions) {
        for (CountingBloomPosition position : positions) {
            arguments.add(fixedNumber(position.bucketNumber()));
            arguments.add(Integer.toString(position.byteOffset()));
        }
    }

    private List<String> baseConfigurationArguments(Namespace namespace) {
        List<String> arguments = new ArrayList<>();
        arguments.add(Integer.toString(namespace.layout().capacity()));
        arguments.add(Integer.toString(namespace.layout().hashCount()));
        arguments.add(Integer.toString(namespace.layout().counterBytes()));
        arguments.add(Integer.toString(namespace.layout().countersPerBucket()));
        return arguments;
    }

    private static void validateGeneration(
            Namespace namespace,
            Generation generation) {
        Objects.requireNonNull(generation);
        if (generation.primaryBucketKeys().size()
                != namespace.layout().bucketCount()
                || generation.secondaryBucketKeys().size()
                != namespace.layout().bucketCount()
                || generation.receiptKeys().size()
                != namespace.receiptShards()) {
            throw new IllegalArgumentException(
                    "Versioned composite Bloom generation does not match namespace layout");
        }
    }

    private static void addStoredKey(
            Map<Object, Object> control,
            String field,
            List<String> keys) {
        String storedKey = value(control.get(field));
        if (storedKey != null && !storedKey.isBlank()) {
            keys.add(storedKey);
        }
    }

    private static String fixedNumber(int number) {
        return String.format(Locale.ROOT, "%04d", number);
    }

    private static String value(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * 从独立资源加载可审计的 Bloom Lua，避免把大段脚本重新内嵌到 Java 并掩盖服务端命令数量。
     */
    private static <T> DefaultRedisScript<T> script(String filename, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(
                "lua/bloom/versioned-composite/" + filename));
        script.setResultType(resultType);
        return script;
    }

    private static void requireLease(String leaseToken, Duration ttl) {
        if (leaseToken == null || leaseToken.isBlank()) {
            throw new IllegalArgumentException("Bloom build lease token must not be blank.");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Bloom build lease TTL must be positive.");
        }
    }

    private static void requireSuccessfulTransition(Long result, String transition) {
        if (result == null || result != 1L) {
            throw new IllegalStateException(
                    "Versioned composite Bloom state transition failed: " + transition);
        }
    }
}
