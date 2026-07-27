package com.example.temperate.service.risk.ip2location.store.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.ip2location.domain.Ip2LocationImportMode;
import com.example.temperate.service.risk.ip2location.domain.ProtectedIp2LocationKey;
import com.example.temperate.service.risk.ip2location.exception.Ip2LocationApiKeyCapacityExceededException;
import com.example.temperate.service.risk.ip2location.store.Ip2LocationApiKeyStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * 使用 Redis 7.4 Hash Field TTL 和有界 Lua 脚本维护 IP2Location 加密凭据与剩余额度。
 *
 * <p>两个 Hash 的同名字段由脚本同时写入、扣减和删除，避免凭据存在但额度缺失或反向孤儿；该实现不负责
 * 解密凭据，也不把密文或标识写入日志。</p>
 */
@Component
public final class RedisIp2LocationApiKeyStore implements Ip2LocationApiKeyStore {

    private static final RedisScript<List> WRITE_BATCH =
            listScript("lua/network-risk/ip2location_write_batch.lua");
    private static final RedisScript<List> ACQUIRE =
            listScript("lua/network-risk/ip2location_acquire.lua");
    private static final RedisScript<List> SCAN =
            listScript("lua/network-risk/ip2location_scan.lua");
    private static final RedisScript<Long> DELETE =
            longScript("lua/network-risk/ip2location_delete.lua");
    private static final int ACQUIRE_REPAIR_ATTEMPTS = 8;
    private static final int MAX_ACTIVE_KEYS = 100;

    private final StringRedisTemplate redisTemplate;
    private final String secretHashKey;
    private final String quotaHashKey;

    public RedisIp2LocationApiKeyStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
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
        List<String> arguments = new ArrayList<>(3 + keys.size() * 4);
        arguments.add(Objects.requireNonNull(mode).name());
        arguments.add(Integer.toString(keys.size()));
        arguments.add(Integer.toString(MAX_ACTIVE_KEYS));
        for (ProtectedIp2LocationKey key : keys) {
            arguments.add(key.keyId().value());
            arguments.add(key.encryptedEnvelope());
            arguments.add(Long.toString(initialQuota));
            arguments.add(Long.toString(key.expiresAt().toEpochMilli()));
        }
        List<?> result = redisTemplate.execute(
                WRITE_BATCH,
                List.of(secretHashKey, quotaHashKey),
                arguments.toArray());
        if (result == null || result.size() != 3) {
            throw new IllegalStateException("IP2Location batch write returned an invalid result.");
        }
        if (number(result.getFirst()).longValue() < 0L) {
            // 容量判断与写入处于同一个 Redis 原子脚本内，失败时脚本尚未改动任一 Hash 字段。
            throw new Ip2LocationApiKeyCapacityExceededException();
        }
        return new BatchWriteResult(
                number(result.get(0)).intValue(),
                number(result.get(1)).intValue(),
                number(result.get(2)).intValue());
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
