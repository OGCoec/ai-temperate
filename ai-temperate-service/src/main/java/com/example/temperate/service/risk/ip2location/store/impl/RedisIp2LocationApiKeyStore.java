package com.example.temperate.service.risk.ip2location.store.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.ip2location.domain.Ip2LocationImportMode;
import com.example.temperate.service.risk.ip2location.domain.ProtectedIp2LocationKey;
import com.example.temperate.service.risk.ip2location.store.Ip2LocationApiKeyStore;
import com.example.temperate.service.risk.observability.NetworkRiskMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * 该实现是来使用 Redis 7.4 Hash Field TTL、单凭据 Lua 和有界 Pipeline 维护加密凭据与剩余额度。
 *
 * <p>两个 Hash 的同名字段由脚本同时写入、扣减和删除，避免凭据存在但额度缺失或反向孤儿；该实现不负责
 * 解密凭据，也不把密文或标识写入日志。</p>
 */
@Component
public final class RedisIp2LocationApiKeyStore implements Ip2LocationApiKeyStore {

    private static final RedisScript<String> WRITE_ONE =
            script("lua/network-risk/ip2location_write_one.lua", String.class);
    private static final RedisScript<List> ACQUIRE =
            listScript("lua/network-risk/ip2location_acquire.lua");
    private static final RedisScript<List> SCAN =
            listScript("lua/network-risk/ip2location_scan.lua");
    private static final RedisScript<Long> DELETE =
            longScript("lua/network-risk/ip2location_delete.lua");
    private static final int ACQUIRE_REPAIR_ATTEMPTS = 8;
    private static final int MAX_ACTIVE_KEYS = 100;
    private static final int PIPELINE_BATCH_SIZE = 50;

    private final StringRedisTemplate redisTemplate;
    private final NetworkRiskMetrics metrics;
    private final String secretHashKey;
    private final String quotaHashKey;

    public RedisIp2LocationApiKeyStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            NetworkRiskMetrics metrics) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.metrics = Objects.requireNonNull(metrics);
        RedisKeyFactory validFactory = Objects.requireNonNull(keyFactory);
        this.secretHashKey = validFactory.ip2LocationSecretHashKey();
        this.quotaHashKey = validFactory.ip2LocationQuotaHashKey();
    }

    @Override
    public BatchWriteResult writeBatch(
            List<ProtectedIp2LocationKey> keys,
            long initialQuota,
            Ip2LocationImportMode mode) {
        if (keys == null || keys.isEmpty() || keys.size() > 500) {
            throw new IllegalArgumentException("IP2Location batch size must be between 1 and 500.");
        }
        if (initialQuota <= 0) {
            throw new IllegalArgumentException("IP2Location initial quota must be positive.");
        }
        Ip2LocationImportMode validMode = Objects.requireNonNull(mode);
        int created = 0;
        int updated = 0;
        int duplicate = 0;
        int capacityRejected = 0;
        for (int start = 0; start < keys.size(); start += PIPELINE_BATCH_SIZE) {
            List<ProtectedIp2LocationKey> batch = keys.subList(
                    start, Math.min(start + PIPELINE_BATCH_SIZE, keys.size()));
            List<Object> responses = executeWritePipeline(
                    batch, initialQuota, validMode);
            for (Object response : responses) {
                switch (text(response)) {
                    case "CREATED" -> {
                        created++;
                        recordResult("created");
                    }
                    case "UPDATED" -> {
                        updated++;
                        recordResult("updated");
                    }
                    case "DUPLICATE" -> {
                        duplicate++;
                        recordResult("duplicate");
                    }
                    case "CAPACITY_REJECTED" -> {
                        capacityRejected++;
                        recordResult("capacity_rejected");
                    }
                    default -> throw new IllegalStateException(
                            "IP2Location credential write returned an invalid result.");
                }
            }
        }
        return new BatchWriteResult(created, updated, duplicate, capacityRejected);
    }

    private List<Object> executeWritePipeline(
            List<ProtectedIp2LocationKey> batch,
            long initialQuota,
            Ip2LocationImportMode mode) {
        // 每个凭据仍由一条 Lua 原子对齐两个 Hash；Pipeline 只按输入顺序减少网络往返。
        long startedNanos = System.nanoTime();
        try {
            List<Object> responses = redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                public Object execute(RedisOperations operations) {
                    for (ProtectedIp2LocationKey key : batch) {
                        ProtectedIp2LocationKey valid = Objects.requireNonNull(key);
                        operations.execute(
                                WRITE_ONE,
                                List.of(secretHashKey, quotaHashKey),
                                mode.name(),
                                Integer.toString(MAX_ACTIVE_KEYS),
                                valid.keyId().value(),
                                valid.encryptedEnvelope(),
                                Long.toString(initialQuota),
                                Long.toString(valid.expiresAt().toEpochMilli()));
                    }
                    return null;
                }
            });
            if (responses == null || responses.size() != batch.size()) {
                throw new IllegalStateException(
                        "IP2Location credential pipeline returned an invalid result count.");
            }
            if (responses.stream().map(RedisIp2LocationApiKeyStore::text)
                    .anyMatch(outcome -> !isWriteOutcome(outcome))) {
                throw new IllegalStateException(
                        "IP2Location credential pipeline returned an invalid outcome.");
            }
            recordPipeline("success", startedNanos, batch.size());
            return responses;
        } catch (RuntimeException failure) {
            recordPipeline("failed", startedNanos, batch.size());
            throw failure;
        }
    }

    private void recordPipeline(String outcome, long startedNanos, int itemCount) {
        try {
            metrics.ip2LocationRedis(
                    Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos)),
                    "write_pipeline",
                    outcome,
                    itemCount);
        } catch (RuntimeException ignoredFailure) {
            // 观测失败不得改变已经由 Redis 接受的部分批次结果。
        }
    }

    private void recordResult(String outcome) {
        try {
            metrics.ip2LocationResult(outcome);
        } catch (RuntimeException ignoredFailure) {
            // 结果指标不参与容量裁决，也不得阻断严格按顺序解析 Pipeline 返回值。
        }
    }

    private static boolean isWriteOutcome(String outcome) {
        return switch (outcome) {
            case "CREATED", "UPDATED", "DUPLICATE", "CAPACITY_REJECTED" -> true;
            default -> false;
        };
    }

    @Override
    public Optional<AcquiredEnvelope> acquire() {
        List<?> result = redisTemplate.execute(
                ACQUIRE,
                List.of(secretHashKey, quotaHashKey),
                Integer.toString(ACQUIRE_REPAIR_ATTEMPTS));
        if (result == null || result.isEmpty() || number(result.get(0)).longValue() == 0L) {
            return Optional.empty();
        }
        if (result.size() != 4) {
            throw new IllegalStateException("IP2Location quota acquire returned an invalid result.");
        }
        return Optional.of(new AcquiredEnvelope(
                HmacIdentifier.fromProtectedValue(text(result.get(1))),
                text(result.get(2)),
                number(result.get(3)).longValue()));
    }

    @Override
    public EncryptedPage scan(long cursor, int size) {
        if (cursor < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("IP2Location scan boundary is invalid.");
        }
        List<?> raw = redisTemplate.execute(
                SCAN,
                List.of(secretHashKey, quotaHashKey),
                Long.toString(cursor),
                Integer.toString(size));
        if (raw == null || raw.isEmpty() || (raw.size() - 1) % 3 != 0) {
            throw new IllegalStateException("IP2Location scan returned an invalid result.");
        }
        long nextCursor = number(raw.getFirst()).longValue();
        List<EncryptedEntry> result = new ArrayList<>((raw.size() - 1) / 3);
        for (int index = 1; index < raw.size(); index += 3) {
            result.add(new EncryptedEntry(
                    HmacIdentifier.fromProtectedValue(text(raw.get(index))),
                    text(raw.get(index + 1)),
                    number(raw.get(index + 2)).longValue()));
        }
        return new EncryptedPage(nextCursor, List.copyOf(result));
    }

    @Override
    public long delete(List<HmacIdentifier> keyIds) {
        if (keyIds == null || keyIds.isEmpty() || keyIds.size() > 100) {
            throw new IllegalArgumentException("IP2Location delete size must be between 1 and 100.");
        }
        List<String> arguments = keyIds.stream()
                .map(HmacIdentifier::value)
                .toList();
        Long result = redisTemplate.execute(
                DELETE,
                List.of(secretHashKey, quotaHashKey),
                arguments.toArray());
        if (result == null) {
            throw new IllegalStateException("IP2Location delete returned no result.");
        }
        return result;
    }

    private static Number number(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        return Long.parseLong(text(value));
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return Objects.toString(value, "");
    }

    private static RedisScript<Long> longScript(String path) {
        return script(path, Long.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RedisScript<List> listScript(String path) {
        return (RedisScript) script(path, List.class);
    }

    private static <T> RedisScript<T> script(String path, Class<T> resultType) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            String source = StreamUtils.copyToString(
                    resource.getInputStream(),
                    StandardCharsets.UTF_8);
            DefaultRedisScript<T> script = new DefaultRedisScript<>();
            script.setScriptText(source);
            script.setResultType(resultType);
            return script;
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
