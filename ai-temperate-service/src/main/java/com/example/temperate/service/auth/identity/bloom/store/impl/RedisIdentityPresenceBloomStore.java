package com.example.temperate.service.auth.identity.bloom.store.impl;

import com.example.temperate.common.bloom.counting.CountingBloomLayout;
import com.example.temperate.common.bloom.counting.CountingBloomPosition;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceBloomSettings;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceDecision;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceKind;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceMutationResult;
import com.example.temperate.service.auth.identity.bloom.ProtectedIdentityPresenceRecord;
import com.example.temperate.service.auth.identity.bloom.store.IdentityPresenceBloomStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 使用 Redis String Bucket、分片 Set 幂等凭据和 Lua 状态机持久化身份计数布隆过滤器。
 *
 * <p>所有计数器增删先在 Lua 内汇总相同偏移量的变化并完成全量上下限检查，再原子写入邮箱、手机号与
 * 用户 ID 凭据；脚本返回前发生的任何业务拒绝都不会留下部分计数。</p>
 */
@Component
public final class RedisIdentityPresenceBloomStore
        implements IdentityPresenceBloomStore {

    private static final String BLOOM_DOMAIN = "bloom";
    private static final String EMAIL_OBJECT = "uli-email";
    private static final String PHONE_OBJECT = "uli-phone";

    private static final DefaultRedisScript<Long> INITIALIZE_BUCKET_SCRIPT =
            new DefaultRedisScript<>(
                    "for index = 1, #KEYS do "
                            + "  redis.call('DEL', KEYS[index]) "
                            + "  redis.call('SETRANGE', KEYS[index], "
                            + "      tonumber(ARGV[index]) - 1, string.char(0)) "
                            + "end "
                            + "return #KEYS",
                    Long.class);

    private static final DefaultRedisScript<String> BEGIN_BUILD_SCRIPT =
            new DefaultRedisScript<>(
                    "local previous = redis.call('HGET', KEYS[1], 'activeGeneration') "
                            + "    or redis.call('HGET', KEYS[1], 'previousActiveGeneration') "
                            + "redis.call('DEL', KEYS[1]) "
                            + "for i = 1, #ARGV, 2 do "
                            + "  redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1]) "
                            + "end "
                            + "if previous then "
                            + "  redis.call('HSET', KEYS[1], 'previousActiveGeneration', previous) "
                            + "  return previous "
                            + "end "
                            + "return ''",
                    String.class);

    private static final DefaultRedisScript<Long> QUERY_SCRIPT =
            new DefaultRedisScript<>(
                    "local function readCounter(key, offset, counterBytes) "
                            + "  if counterBytes == 1 then "
                            + "    local raw = redis.call('GETRANGE', key, offset, offset) "
                            + "    return (#raw == 0) and 0 or string.byte(raw) "
                            + "  end "
                            + "  local raw = redis.call('GETRANGE', key, offset, offset + 1) "
                            + "  return (#raw < 2) and 0 or (string.byte(raw, 1) * 256 + string.byte(raw, 2)) "
                            + "end "
                            + "if redis.call('HGET', KEYS[1], 'state') ~= 'ACTIVE' then return -1 end "
                            + "if redis.call('HGET', KEYS[1], 'capacity') ~= ARGV[1] "
                            + "    or redis.call('HGET', KEYS[1], 'hashCount') ~= ARGV[2] "
                            + "    or redis.call('HGET', KEYS[1], 'counterBytes') ~= ARGV[3] "
                            + "    or redis.call('HGET', KEYS[1], 'countersPerBucket') ~= ARGV[4] then "
                            + "  return -2 "
                            + "end "
                            + "local counterBytes = tonumber(ARGV[3]) "
                            + "local fieldPrefix = ARGV[5] "
                            + "local positionCount = tonumber(ARGV[6]) "
                            + "local cursor = 7 "
                            + "for i = 1, positionCount do "
                            + "  local bucketKey = redis.call('HGET', KEYS[1], fieldPrefix .. ARGV[cursor]) "
                            + "  if not bucketKey or redis.call('EXISTS', bucketKey) == 0 then return -2 end "
                            + "  local offset = tonumber(ARGV[cursor + 1]) "
                            + "  if readCounter(bucketKey, offset, counterBytes) == 0 then return 0 end "
                            + "  cursor = cursor + 2 "
                            + "end "
                            + "return 1",
                    Long.class);

    private static final DefaultRedisScript<Long> ADD_BATCH_SCRIPT =
            new DefaultRedisScript<>(
                    "local function readCounter(key, offset, counterBytes) "
                            + "  if counterBytes == 1 then "
                            + "    local raw = redis.call('GETRANGE', key, offset, offset) "
                            + "    return (#raw == 0) and 0 or string.byte(raw) "
                            + "  end "
                            + "  local raw = redis.call('GETRANGE', key, offset, offset + 1) "
                            + "  return (#raw < 2) and 0 or (string.byte(raw, 1) * 256 + string.byte(raw, 2)) "
                            + "end "
                            + "local function writeCounter(key, offset, value, counterBytes) "
                            + "  if counterBytes == 1 then "
                            + "    redis.call('SETRANGE', key, offset, string.char(value)) "
                            + "    return "
                            + "  end "
                            + "  redis.call('SETRANGE', key, offset, "
                            + "      string.char(math.floor(value / 256), value % 256)) "
                            + "end "
                            + "local state = redis.call('HGET', KEYS[1], 'state') "
                            + "local prefix "
                            + "if state == 'ACTIVE' then prefix = 'active' "
                            + "elseif state == 'BUILDING' or state == 'READY' then prefix = 'building' "
                            + "else return -1 end "
                            + "if redis.call('HGET', KEYS[1], 'capacity') ~= ARGV[1] "
                            + "    or redis.call('HGET', KEYS[1], 'hashCount') ~= ARGV[2] "
                            + "    or redis.call('HGET', KEYS[1], 'counterBytes') ~= ARGV[3] "
                            + "    or redis.call('HGET', KEYS[1], 'countersPerBucket') ~= ARGV[4] then "
                            + "  return -1 "
                            + "end "
                            + "local counterBytes = tonumber(ARGV[3]) "
                            + "local itemCount = tonumber(ARGV[5]) "
                            + "local hashCount = tonumber(ARGV[2]) "
                            + "local cursor = 6 "
                            + "local deltas = {} "
                            + "local receipts = {} "
                            + "local seenReceipts = {} "
                            + "local freshCount = 0 "
                            + "local function addPosition(kind, bucket, offset) "
                            + "  local bucketKey = redis.call('HGET', KEYS[1], "
                            + "      prefix .. kind .. 'Bucket:' .. bucket) "
                            + "  if not bucketKey or redis.call('EXISTS', bucketKey) == 0 then return false end "
                            + "  local locationKey = bucketKey .. '|' .. offset "
                            + "  local location = deltas[locationKey] "
                            + "  if location then location[3] = location[3] + 1 "
                            + "  else deltas[locationKey] = {bucketKey, tonumber(offset), 1} end "
                            + "  return true "
                            + "end "
                            + "for item = 1, itemCount do "
                            + "  local userId = ARGV[cursor] "
                            + "  local shard = ARGV[cursor + 1] "
                            + "  cursor = cursor + 2 "
                            + "  local receiptKey = redis.call('HGET', KEYS[1], "
                            + "      prefix .. 'Receipt:' .. shard) "
                            + "  if not receiptKey then return -1 end "
                            + "  local receiptIdentity = receiptKey .. '|' .. userId "
                            + "  local fresh = not seenReceipts[receiptIdentity] "
                            + "      and redis.call('SISMEMBER', receiptKey, userId) == 0 "
                            + "  local emailPositions = {} "
                            + "  for index = 1, hashCount do "
                            + "    emailPositions[index] = {ARGV[cursor], ARGV[cursor + 1]} "
                            + "    cursor = cursor + 2 "
                            + "  end "
                            + "  local phonePresent = ARGV[cursor] "
                            + "  cursor = cursor + 1 "
                            + "  local phonePositions = {} "
                            + "  if phonePresent == '1' then "
                            + "    for index = 1, hashCount do "
                            + "      phonePositions[index] = {ARGV[cursor], ARGV[cursor + 1]} "
                            + "      cursor = cursor + 2 "
                            + "    end "
                            + "  end "
                            + "  if fresh then "
                            + "    for index = 1, #emailPositions do "
                            + "      if not addPosition('Email', emailPositions[index][1], "
                            + "          emailPositions[index][2]) then return -1 end "
                            + "    end "
                            + "    for index = 1, #phonePositions do "
                            + "      if not addPosition('Phone', phonePositions[index][1], "
                            + "          phonePositions[index][2]) then return -1 end "
                            + "    end "
                            + "    freshCount = freshCount + 1 "
                            + "    seenReceipts[receiptIdentity] = true "
                            + "    receipts[freshCount] = {receiptKey, userId} "
                            + "  end "
                            + "end "
                            + "if freshCount == 0 then return 0 end "
                            + "local countField = prefix .. 'Count' "
                            + "local currentCount = tonumber(redis.call('HGET', KEYS[1], countField) or '0') "
                            + "local maximumElements = tonumber(redis.call('HGET', KEYS[1], 'maximumElements')) "
                            + "if currentCount + freshCount > maximumElements then "
                            + "  redis.call('HSET', KEYS[1], 'state', 'DEGRADED', "
                            + "      'degradedReason', 'CAPACITY_EXCEEDED') "
                            + "  return -3 "
                            + "end "
                            + "local maximumCounter = (counterBytes == 1) and 255 or 65535 "
                            + "for _, location in pairs(deltas) do "
                            + "  local current = readCounter(location[1], location[2], counterBytes) "
                            + "  if current + location[3] > maximumCounter then "
                            + "    redis.call('HSET', KEYS[1], 'state', 'DEGRADED', "
                            + "        'degradedReason', 'COUNTER_OVERFLOW') "
                            + "    return -2 "
                            + "  end "
                            + "  location[4] = current "
                            + "end "
                            + "for _, location in pairs(deltas) do "
                            + "  writeCounter(location[1], location[2], "
                            + "      location[4] + location[3], counterBytes) "
                            + "end "
                            + "for index = 1, #receipts do "
                            + "  redis.call('SADD', receipts[index][1], receipts[index][2]) "
                            + "end "
                            + "local updatedCount = redis.call('HINCRBY', KEYS[1], countField, freshCount) "
                            + "if updatedCount >= maximumElements then "
                            + "  redis.call('HSET', KEYS[1], 'state', 'DEGRADED', "
                            + "      'degradedReason', 'CAPACITY_EXCEEDED') "
                            + "  return -3 "
                            + "end "
                            + "return freshCount",
                    Long.class);

    private static final DefaultRedisScript<Long> REMOVE_BATCH_SCRIPT =
            new DefaultRedisScript<>(
                    "local function readCounter(key, offset, counterBytes) "
                            + "  if counterBytes == 1 then "
                            + "    local raw = redis.call('GETRANGE', key, offset, offset) "
                            + "    return (#raw == 0) and 0 or string.byte(raw) "
                            + "  end "
                            + "  local raw = redis.call('GETRANGE', key, offset, offset + 1) "
                            + "  return (#raw < 2) and 0 or (string.byte(raw, 1) * 256 + string.byte(raw, 2)) "
                            + "end "
                            + "local function writeCounter(key, offset, value, counterBytes) "
                            + "  if counterBytes == 1 then "
                            + "    redis.call('SETRANGE', key, offset, string.char(value)) "
                            + "    return "
                            + "  end "
                            + "  redis.call('SETRANGE', key, offset, "
                            + "      string.char(math.floor(value / 256), value % 256)) "
                            + "end "
                            + "local state = redis.call('HGET', KEYS[1], 'state') "
                            + "local prefix "
                            + "if state == 'ACTIVE' then prefix = 'active' "
                            + "elseif state == 'BUILDING' or state == 'READY' then prefix = 'building' "
                            + "else return -1 end "
                            + "if redis.call('HGET', KEYS[1], 'capacity') ~= ARGV[1] "
                            + "    or redis.call('HGET', KEYS[1], 'hashCount') ~= ARGV[2] "
                            + "    or redis.call('HGET', KEYS[1], 'counterBytes') ~= ARGV[3] "
                            + "    or redis.call('HGET', KEYS[1], 'countersPerBucket') ~= ARGV[4] then "
                            + "  return -1 "
                            + "end "
                            + "local counterBytes = tonumber(ARGV[3]) "
                            + "local itemCount = tonumber(ARGV[5]) "
                            + "local hashCount = tonumber(ARGV[2]) "
                            + "local cursor = 6 "
                            + "local deltas = {} "
                            + "local receipts = {} "
                            + "local seenReceipts = {} "
                            + "local removalCount = 0 "
                            + "local function addPosition(kind, bucket, offset) "
                            + "  local bucketKey = redis.call('HGET', KEYS[1], "
                            + "      prefix .. kind .. 'Bucket:' .. bucket) "
                            + "  if not bucketKey or redis.call('EXISTS', bucketKey) == 0 then return false end "
                            + "  local locationKey = bucketKey .. '|' .. offset "
                            + "  local location = deltas[locationKey] "
                            + "  if location then location[3] = location[3] + 1 "
                            + "  else deltas[locationKey] = {bucketKey, tonumber(offset), 1} end "
                            + "  return true "
                            + "end "
                            + "for item = 1, itemCount do "
                            + "  local userId = ARGV[cursor] "
                            + "  local shard = ARGV[cursor + 1] "
                            + "  cursor = cursor + 2 "
                            + "  local receiptKey = redis.call('HGET', KEYS[1], "
                            + "      prefix .. 'Receipt:' .. shard) "
                            + "  if not receiptKey then return -1 end "
                            + "  local receiptIdentity = receiptKey .. '|' .. userId "
                            + "  local removable = not seenReceipts[receiptIdentity] "
                            + "      and redis.call('SISMEMBER', receiptKey, userId) == 1 "
                            + "  local emailPositions = {} "
                            + "  for index = 1, hashCount do "
                            + "    emailPositions[index] = {ARGV[cursor], ARGV[cursor + 1]} "
                            + "    cursor = cursor + 2 "
                            + "  end "
                            + "  local phonePresent = ARGV[cursor] "
                            + "  cursor = cursor + 1 "
                            + "  local phonePositions = {} "
                            + "  if phonePresent == '1' then "
                            + "    for index = 1, hashCount do "
                            + "      phonePositions[index] = {ARGV[cursor], ARGV[cursor + 1]} "
                            + "      cursor = cursor + 2 "
                            + "    end "
                            + "  end "
                            + "  if removable then "
                            + "    for index = 1, #emailPositions do "
                            + "      if not addPosition('Email', emailPositions[index][1], "
                            + "          emailPositions[index][2]) then return -1 end "
                            + "    end "
                            + "    for index = 1, #phonePositions do "
                            + "      if not addPosition('Phone', phonePositions[index][1], "
                            + "          phonePositions[index][2]) then return -1 end "
                            + "    end "
                            + "    removalCount = removalCount + 1 "
                            + "    seenReceipts[receiptIdentity] = true "
                            + "    receipts[removalCount] = {receiptKey, userId} "
                            + "  end "
                            + "end "
                            + "if removalCount == 0 then return 0 end "
                            + "local countField = prefix .. 'Count' "
                            + "local currentCount = tonumber(redis.call('HGET', KEYS[1], countField) or '0') "
                            + "if currentCount < removalCount then "
                            + "  redis.call('HSET', KEYS[1], 'state', 'DEGRADED', "
                            + "      'degradedReason', 'COUNTER_UNDERFLOW') "
                            + "  return -4 "
                            + "end "
                            + "for _, location in pairs(deltas) do "
                            + "  local current = readCounter(location[1], location[2], counterBytes) "
                            + "  if current < location[3] then "
                            + "    redis.call('HSET', KEYS[1], 'state', 'DEGRADED', "
                            + "        'degradedReason', 'COUNTER_UNDERFLOW') "
                            + "    return -4 "
                            + "  end "
                            + "  location[4] = current "
                            + "end "
                            + "for _, location in pairs(deltas) do "
                            + "  writeCounter(location[1], location[2], "
                            + "      location[4] - location[3], counterBytes) "
                            + "end "
                            + "for index = 1, #receipts do "
                            + "  redis.call('SREM', receipts[index][1], receipts[index][2]) "
                            + "end "
                            + "redis.call('HINCRBY', KEYS[1], countField, -removalCount) "
                            + "return removalCount",
                    Long.class);

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
            new DefaultRedisScript<>(
                    "if redis.call('HGET', KEYS[1], 'state') ~= 'READY' "
                            + "    or redis.call('HGET', KEYS[1], 'buildingGeneration') ~= ARGV[1] then "
                            + "  return 0 "
                            + "end "
                            + "local bucketCount = tonumber(redis.call('HGET', KEYS[1], 'bucketCount')) "
                            + "local receiptShards = tonumber(redis.call('HGET', KEYS[1], 'receiptShards')) "
                            + "for number = 0, bucketCount - 1 do "
                            + "  local suffix = string.format('%04d', number) "
                            + "  local emailKey = redis.call('HGET', KEYS[1], "
                            + "      'buildingEmailBucket:' .. suffix) "
                            + "  local phoneKey = redis.call('HGET', KEYS[1], "
                            + "      'buildingPhoneBucket:' .. suffix) "
                            + "  if not emailKey or not phoneKey then return -1 end "
                            + "  redis.call('HSET', KEYS[1], 'activeEmailBucket:' .. suffix, emailKey) "
                            + "  redis.call('HSET', KEYS[1], 'activePhoneBucket:' .. suffix, phoneKey) "
                            + "end "
                            + "for number = 0, receiptShards - 1 do "
                            + "  local suffix = string.format('%04d', number) "
                            + "  local receiptKey = redis.call('HGET', KEYS[1], "
                            + "      'buildingReceipt:' .. suffix) "
                            + "  if not receiptKey then return -1 end "
                            + "  redis.call('HSET', KEYS[1], 'activeReceipt:' .. suffix, receiptKey) "
                            + "end "
                            + "local count = redis.call('HGET', KEYS[1], 'buildingCount') or '0' "
                            + "redis.call('HSET', KEYS[1], "
                            + "    'activeGeneration', ARGV[1], "
                            + "    'activeCount', count, "
                            + "    'state', 'ACTIVE') "
                            + "return 1",
                    Long.class);

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
    private final RedisKeyFactory keyFactory;
    private final IdentityPresenceBloomSettings settings;
    private final CountingBloomLayout layout;

    public RedisIdentityPresenceBloomStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            IdentityPresenceBloomSettings settings) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.settings = Objects.requireNonNull(settings);
        this.layout = new CountingBloomLayout(
                settings.capacity(),
                settings.hashCount(),
                settings.counterBytes(),
                settings.countersPerBucket());
    }

    @Override
    public IdentityPresenceDecision check(
            IdentityPresenceKind kind, HmacIdentifier protectedIdentifier) {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(protectedIdentifier);
        List<CountingBloomPosition> positions =
                layout.positions(protectedIdentifier.value());
        List<String> arguments = baseConfigurationArguments();
        arguments.add(kind == IdentityPresenceKind.EMAIL
                ? "activeEmailBucket:"
                : "activePhoneBucket:");
        arguments.add(Integer.toString(positions.size()));
        appendPositions(arguments, positions);
        Long result = redisTemplate.execute(
                QUERY_SCRIPT,
                List.of(keyFactory.identityPresenceBloomControlKey()),
                arguments.toArray());
        if (result != null && result == -2L) {
            throw new IllegalStateException("Identity Bloom active metadata is inconsistent.");
        }
        if (result == null || result < 0L) {
            return IdentityPresenceDecision.UNAVAILABLE;
        }
        return result == 0L
                ? IdentityPresenceDecision.DEFINITELY_ABSENT
                : IdentityPresenceDecision.POSSIBLY_PRESENT;
    }

    @Override
    public IdentityPresenceMutationResult add(ProtectedIdentityPresenceRecord record) {
        return addAll(List.of(Objects.requireNonNull(record)));
    }

    @Override
    public IdentityPresenceMutationResult addAll(
            List<ProtectedIdentityPresenceRecord> records) {
        if (records == null || records.isEmpty()) {
            return IdentityPresenceMutationResult.ALREADY_APPLIED;
        }
        if (records.size() > settings.buildBatchSize()) {
            throw new IllegalArgumentException("Identity Bloom batch exceeds configured boundary.");
        }
        List<String> arguments = baseConfigurationArguments();
        arguments.add(Integer.toString(records.size()));
        for (ProtectedIdentityPresenceRecord record : records) {
            appendRecord(arguments, Objects.requireNonNull(record));
        }
        Long result = redisTemplate.execute(
                ADD_BATCH_SCRIPT,
                List.of(keyFactory.identityPresenceBloomControlKey()),
                arguments.toArray());
        if (result == null || result == -1L) {
            return IdentityPresenceMutationResult.UNAVAILABLE;
        }
        if (result == -2L) {
            return IdentityPresenceMutationResult.OVERFLOW;
        }
        if (result == -3L) {
            return IdentityPresenceMutationResult.CAPACITY_EXCEEDED;
        }
        return result == 0L
                ? IdentityPresenceMutationResult.ALREADY_APPLIED
                : IdentityPresenceMutationResult.APPLIED;
    }

    @Override
    public IdentityPresenceMutationResult remove(ProtectedIdentityPresenceRecord record) {
        return removeAll(List.of(Objects.requireNonNull(record)));
    }

    @Override
    public IdentityPresenceMutationResult removeAll(
            List<ProtectedIdentityPresenceRecord> records) {
        if (records == null || records.isEmpty()) {
            return IdentityPresenceMutationResult.ALREADY_APPLIED;
        }
        if (records.size() > settings.buildBatchSize()) {
            throw new IllegalArgumentException("Identity Bloom batch exceeds configured boundary.");
        }
        List<String> arguments = baseConfigurationArguments();
        arguments.add(Integer.toString(records.size()));
        for (ProtectedIdentityPresenceRecord record : records) {
            appendRecord(arguments, Objects.requireNonNull(record));
        }
        Long result = redisTemplate.execute(
                REMOVE_BATCH_SCRIPT,
                List.of(keyFactory.identityPresenceBloomControlKey()),
                arguments.toArray());
        if (result == null || result == -1L) {
            return IdentityPresenceMutationResult.UNAVAILABLE;
        }
        if (result == -4L) {
            return IdentityPresenceMutationResult.UNDERFLOW;
        }
        return result == 0L
                ? IdentityPresenceMutationResult.ALREADY_APPLIED
                : IdentityPresenceMutationResult.APPLIED;
    }

    @Override
    public boolean tryAcquireBuildLease(String leaseToken, Duration ttl) {
        requireLease(leaseToken, ttl);
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                keyFactory.identityPresenceBloomBuildLockKey(), leaseToken, ttl));
    }

    @Override
    public boolean renewBuildLease(String leaseToken, Duration ttl) {
        requireLease(leaseToken, ttl);
        Long result = redisTemplate.execute(
                RENEW_LEASE_SCRIPT,
                List.of(keyFactory.identityPresenceBloomBuildLockKey()),
                leaseToken,
                Long.toString(ttl.toMillis()));
        return result != null && result == 1L;
    }

    @Override
    public String beginBuild(String generation) {
        Objects.requireNonNull(generation);
        cleanupAbandonedBuild();
        initializeGenerationBuckets(generation);
        writeGenerationMetadata(generation);

        Map<String, String> control = new LinkedHashMap<>();
        control.put("state", "BUILDING");
        control.put("buildingGeneration", generation);
        control.put("capacity", Integer.toString(settings.capacity()));
        control.put("hashCount", Integer.toString(settings.hashCount()));
        control.put("counterBytes", Integer.toString(settings.counterBytes()));
        control.put("countersPerBucket", Integer.toString(settings.countersPerBucket()));
        control.put("bucketCount", Integer.toString(layout.bucketCount()));
        control.put("receiptShards", Integer.toString(settings.receiptShards()));
        control.put("maximumElements", Integer.toString(settings.maximumElements()));
        control.put("buildingCount", "0");
        for (int bucket = 0; bucket < layout.bucketCount(); bucket++) {
            String suffix = fixedNumber(bucket);
            control.put(
                    "buildingEmailBucket:" + suffix,
                    bucketKey(IdentityPresenceKind.EMAIL, generation, bucket));
            control.put(
                    "buildingPhoneBucket:" + suffix,
                    bucketKey(IdentityPresenceKind.PHONE, generation, bucket));
        }
        for (int shard = 0; shard < settings.receiptShards(); shard++) {
            control.put(
                    "buildingReceipt:" + fixedNumber(shard),
                    keyFactory.identityPresenceBloomReceiptKey(generation, shard));
        }
        List<String> arguments = new ArrayList<>(control.size() * 2);
        control.forEach((field, value) -> {
            arguments.add(field);
            arguments.add(value);
        });
        String previousGeneration = redisTemplate.execute(
                BEGIN_BUILD_SCRIPT,
                List.of(keyFactory.identityPresenceBloomControlKey()),
                arguments.toArray());
        return previousGeneration == null || previousGeneration.isBlank()
                ? null
                : previousGeneration;
    }

    @Override
    public void markReady(String generation) {
        requireSuccessfulTransition(
                redisTemplate.execute(
                        MARK_READY_SCRIPT,
                        List.of(keyFactory.identityPresenceBloomControlKey()),
                        generation),
                "BUILDING to READY");
    }

    @Override
    public void activate(String generation) {
        requireSuccessfulTransition(
                redisTemplate.execute(
                        ACTIVATE_SCRIPT,
                        List.of(keyFactory.identityPresenceBloomControlKey()),
                        generation),
                "READY to ACTIVE");
    }

    @Override
    public void cleanupGeneration(String generation) {
        if (generation == null || generation.isBlank()) {
            return;
        }
        Collection<String> keys = generationKeys(generation);
        redisTemplate.unlink(keys);
    }

    @Override
    public void markDegraded(String reason) {
        if (reason == null || !reason.matches("^[A-Z0-9_]{1,64}$")) {
            throw new IllegalArgumentException("Bloom degraded reason is invalid.");
        }
        redisTemplate.opsForHash().putAll(
                keyFactory.identityPresenceBloomControlKey(),
                Map.of("state", "DEGRADED", "degradedReason", reason));
    }

    @Override
    public void releaseBuildLease(String leaseToken) {
        if (leaseToken == null || leaseToken.isBlank()) {
            return;
        }
        redisTemplate.execute(
                RELEASE_LEASE_SCRIPT,
                List.of(keyFactory.identityPresenceBloomBuildLockKey()),
                leaseToken);
    }

    private void initializeGenerationBuckets(String generation) {
        List<String> keys = new ArrayList<>(layout.bucketCount() * 2);
        List<String> byteLengths = new ArrayList<>(layout.bucketCount() * 2);
        for (int bucket = 0; bucket < layout.bucketCount(); bucket++) {
            String byteLength = Integer.toString(layout.bucketByteLength(bucket));
            keys.add(bucketKey(IdentityPresenceKind.EMAIL, generation, bucket));
            byteLengths.add(byteLength);
            keys.add(bucketKey(IdentityPresenceKind.PHONE, generation, bucket));
            byteLengths.add(byteLength);
        }
        if (keys.size() > 500) {
            throw new IllegalStateException(
                    "Identity Bloom initialization exceeds the 500-Key Redis batch boundary.");
        }
        redisTemplate.execute(
                INITIALIZE_BUCKET_SCRIPT, keys, byteLengths.toArray());
    }

    private void cleanupAbandonedBuild() {
        Map<Object, Object> control = redisTemplate.opsForHash().entries(
                keyFactory.identityPresenceBloomControlKey());
        if (control == null || control.isEmpty()) {
            return;
        }
        String state = value(control.get("state"));
        String buildingGeneration = value(control.get("buildingGeneration"));
        String activeGeneration = value(control.get("activeGeneration"));
        if (buildingGeneration != null
                && !buildingGeneration.equals(activeGeneration)
                && ("BUILDING".equals(state)
                || "READY".equals(state)
                || "DEGRADED".equals(state))) {
            // 构建租约已经由当前实例取得，遗留代次不再可能被合法写入，可按已知 Key 列表安全 UNLINK。
            cleanupGeneration(buildingGeneration);
        }
    }

    private void writeGenerationMetadata(String generation) {
        redisTemplate.opsForHash().putAll(
                keyFactory.identityPresenceBloomMetaKey(generation),
                Map.of(
                        "capacity", Integer.toString(settings.capacity()),
                        "hashCount", Integer.toString(settings.hashCount()),
                        "counterBytes", Integer.toString(settings.counterBytes()),
                        "countersPerBucket", Integer.toString(settings.countersPerBucket()),
                        "bucketCount", Integer.toString(layout.bucketCount()),
                        "receiptShards", Integer.toString(settings.receiptShards()),
                        "maximumElements", Integer.toString(settings.maximumElements())));
    }

    private Collection<String> generationKeys(String generation) {
        List<String> keys = new ArrayList<>(
                layout.bucketCount() * 2 + settings.receiptShards() + 1);
        keys.add(keyFactory.identityPresenceBloomMetaKey(generation));
        for (int bucket = 0; bucket < layout.bucketCount(); bucket++) {
            keys.add(bucketKey(IdentityPresenceKind.EMAIL, generation, bucket));
            keys.add(bucketKey(IdentityPresenceKind.PHONE, generation, bucket));
        }
        for (int shard = 0; shard < settings.receiptShards(); shard++) {
            keys.add(keyFactory.identityPresenceBloomReceiptKey(generation, shard));
        }
        return List.copyOf(keys);
    }

    private void appendRecord(
            List<String> arguments, ProtectedIdentityPresenceRecord record) {
        arguments.add(Long.toString(record.userId()));
        arguments.add(fixedNumber(receiptShard(record.userId())));
        appendPositions(arguments, layout.positions(record.protectedEmail().value()));
        if (record.protectedPhone() == null) {
            arguments.add("0");
            return;
        }
        arguments.add("1");
        appendPositions(arguments, layout.positions(record.protectedPhone().value()));
    }

    private static void appendPositions(
            List<String> arguments, List<CountingBloomPosition> positions) {
        for (CountingBloomPosition position : positions) {
            arguments.add(fixedNumber(position.bucketNumber()));
            arguments.add(Integer.toString(position.byteOffset()));
        }
    }

    private List<String> baseConfigurationArguments() {
        List<String> arguments = new ArrayList<>();
        arguments.add(Integer.toString(settings.capacity()));
        arguments.add(Integer.toString(settings.hashCount()));
        arguments.add(Integer.toString(settings.counterBytes()));
        arguments.add(Integer.toString(settings.countersPerBucket()));
        return arguments;
    }

    private String bucketKey(
            IdentityPresenceKind kind, String generation, int bucketNumber) {
        return keyFactory.bucketKey(
                BLOOM_DOMAIN,
                kind == IdentityPresenceKind.EMAIL ? EMAIL_OBJECT : PHONE_OBJECT,
                generation,
                bucketNumber);
    }

    private int receiptShard(long userId) {
        return Long.hashCode(userId) & (settings.receiptShards() - 1);
    }

    private static String fixedNumber(int number) {
        return String.format(Locale.ROOT, "%04d", number);
    }

    private static String value(Object value) {
        return value == null ? null : value.toString();
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
            throw new IllegalStateException("Identity Bloom state transition failed: " + transition);
        }
    }
}
