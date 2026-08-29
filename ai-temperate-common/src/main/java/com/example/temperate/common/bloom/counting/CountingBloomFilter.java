package com.example.temperate.common.bloom.counting;

import cn.hutool.core.util.HashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 该类是来兼容旧版单 Key 计数 Bloom 调用，并为存量调用提供有界的原子增删查能力。
 * <p>
 * 基于 Redis String（1 Byte per counter）实现。
 * 每个 bucket 对应 String 中的一个字节（0~255），通过 Lua 脚本保证原子性。
 * 支持 add（插入）、delete（删除）、exists（查询）三大操作。
 * </p>
 *
 * <p>
 * 与普通布隆过滤器的区别：
 * </p>
 * <ul>
 * <li>普通布隆过滤器：每个 bucket 是 1 bit（0/1），不支持删除</li>
 * <li>计数布隆过滤器：每个 bucket 是 1 byte 计数器（0~255），支持安全删除</li>
 * </ul>
 */
@Slf4j
@Component
public class CountingBloomFilter {

    private static final int LEGACY_MAX_BATCH_SIZE = 100;

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 本地缓存过滤器的元数据（容量和哈希次数）
     * 使用 Caffeine 设置最大内存上限和随机过期时间，防止本地内存爆炸
     */
    private final Cache<String, FilterMeta> metaCache;

    private static class FilterMeta {
        final int capacity;
        final int hashCount;
        final int counterBytes;

        FilterMeta(int capacity, int hashCount) {
            this(capacity, hashCount, 1);
        }

        FilterMeta(int capacity, int hashCount, int counterBytes) {
            this.capacity = capacity;
            this.hashCount = hashCount;
            this.counterBytes = counterBytes;
        }
    }

    public CountingBloomFilter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;

        // 生成随机过期时间（例如 12 到 24 小时之间），防止缓存雪崩
        long randomExpireHours = 12L + ThreadLocalRandom.current().nextInt(12);

        // 构建 Caffeine 缓存，最大 1000 个过滤器的配置
        this.metaCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(randomExpireHours, TimeUnit.MINUTES)
                .build();
    }

    // ========================== 初始化 ==========================

    /**
     * 初始化计数布隆过滤器并保存元数据
     * <p>
     * 在 Redis 中预分配 capacity 个字节的空间，并保存配置（capacity 和 hashCount）。
     * </p>
     *
     * @param key       过滤器 Redis key
     * @param capacity  过滤器容量（bucket 总数）
     * @param hashCount 哈希函数个数 k
     * @return true: 初始化成功或已存在
     */
    public Boolean init(String key, int capacity, int hashCount) {
        return init(key, capacity, hashCount, 1);
    }

    public Boolean init(String key, int capacity, int hashCount, int counterBytes) {
        if (capacity < 1) {
            throw new IllegalArgumentException(
                    "[计数布隆过滤器] capacity 必须 >= 1，当前值: " + capacity);
        }
        if (capacity < 200) {
            throw new IllegalArgumentException(
                    "[计数布隆过滤器] capacity 太小（当前值: " + capacity + "），建议设置为 200 以上以保证过滤器精度");
        }
        if (hashCount < 4 || hashCount > 25) {
            throw new IllegalArgumentException(
                    "[计数布隆过滤器] hashCount 必须在 [4, 25] 范围内，当前值: " + hashCount);
        }
        if (counterBytes != 1 && counterBytes != 2) {
            throw new IllegalArgumentException("[计数布隆过滤器] counterBytes 只支持 1 或 2，当前值: " + counterBytes);
        }
        String metaKey = key + ":meta";

        // ===== 配置变更检测：防止修改参数后与已有数据不匹配导致 false-negative =====
        Object oldCapStr = stringRedisTemplate.opsForHash().get(metaKey, "capacity");
        Object oldHashStr = stringRedisTemplate.opsForHash().get(metaKey, "hashCount");
        Object oldCounterBytesStr = stringRedisTemplate.opsForHash().get(metaKey, "counterBytes");

        if (oldCapStr != null && oldHashStr != null) {
            int oldCapacity = Integer.parseInt(oldCapStr.toString());
            int oldHashCount = Integer.parseInt(oldHashStr.toString());
            int oldCounterBytes = oldCounterBytesStr == null ? 1 : Integer.parseInt(oldCounterBytesStr.toString());
            boolean configChanged = (oldCapacity != capacity)
                    || (oldHashCount != hashCount)
                    || (oldCounterBytes != counterBytes);
            if (configChanged) {
                log.warn("[计数布隆过滤器] 检测到配置变更（key={}），" +
                        "旧: capacity={}, hashCount={} → 新: capacity={}, hashCount={}，" +
                        "将清空旧数据并按新配置重新初始化",
                        key, oldCapacity, oldHashCount, capacity, hashCount);
                // 配置与历史数据不兼容，必须强制清除旧数据并重建
                // 否则旧 bucket 是按旧 hashCount 写入的，新 hashCount 查询会产生 false-negative
                stringRedisTemplate.delete(key);
                stringRedisTemplate.delete(metaKey);
                metaCache.invalidate(key);
                // 此处不能复用 reinit() 避免递归，直接继续下面的初始化逻辑即可
            } else {
                log.info("[计数布隆过滤器] 配置未变更（key={}），capacity={}, hashCount={}，跳过重建",
                        key, capacity, hashCount);
            }
        } else {
            log.info("[计数布隆过滤器] 首次初始化（key={}），capacity={}, hashCount={}",
                    key, capacity, hashCount);
        }

        // 保存元数据到 Redis（覆盖写，无论是否已存在）
        stringRedisTemplate.opsForHash().put(metaKey, "capacity", String.valueOf(capacity));
        stringRedisTemplate.opsForHash().put(metaKey, "hashCount", String.valueOf(hashCount));
        stringRedisTemplate.opsForHash().put(metaKey, "counterBytes", String.valueOf(counterBytes));

        // 更新本地元数据缓存
        metaCache.put(key, new FilterMeta(capacity, hashCount, counterBytes));

        // 预分配 String 空间（key 不存在时才分配，避免清除已有数据）
        String luaInit = "if redis.call('EXISTS', KEYS[1]) == 0 then " +
                "  redis.call('SETRANGE', KEYS[1], tonumber(ARGV[1]) - 1, string.char(0)) " +
                "  return 1 " +
                "else " +
                "  return 0 " +
                "end";
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(luaInit, Long.class);
        Long result = stringRedisTemplate.execute(
                script,
                Collections.singletonList(key),
                String.valueOf(capacity * counterBytes));
        return result != null; // 无论返回 1（新建）还是 0（已存在），初始化都算成功完成
    }

    /**
     * 强制重新初始化（清空所有计数器和元数据）
     *
     * @param key       过滤器 Redis key
     * @param capacity  过滤器容量
     * @param hashCount 哈希函数个数 k
     */
    public void reinit(String key, int capacity, int hashCount) {
        reinit(key, capacity, hashCount, 1);
    }

    public void reinit(String key, int capacity, int hashCount, int counterBytes) {
        stringRedisTemplate.delete(key);
        stringRedisTemplate.delete(key + ":meta");
        metaCache.invalidate(key);
        init(key, capacity, hashCount, counterBytes);
    }

    // ========================== 元数据加载 ==========================

    /**
     * 获取过滤器的元数据配置，先查本地缓存，没有再查 Redis
     */
    private FilterMeta getMeta(String key) {
        return metaCache.get(key, k -> {
            String metaKey = key + ":meta";
            Object capStr = stringRedisTemplate.opsForHash().get(metaKey, "capacity");
            Object hashStr = stringRedisTemplate.opsForHash().get(metaKey, "hashCount");
            Object counterBytesStr = stringRedisTemplate.opsForHash().get(metaKey, "counterBytes");

            if (capStr == null || hashStr == null) {
                throw new IllegalStateException("计数布隆过滤器 [" + key + "] 尚未初始化，找不到元数据配置！请先调用 init() 方法。");
            }
            int counterBytes = counterBytesStr == null ? 1 : Integer.parseInt(counterBytesStr.toString());
            return new FilterMeta(Integer.parseInt(capStr.toString()), Integer.parseInt(hashStr.toString()), counterBytes);
        });
    }

    // ========================== Lua 脚本 ==========================

    /**
     * ADD 脚本：对 k 个 bucket 的 counter 各 +1（上限 255，防溢出）
     * KEYS[1] = 过滤器 key
     * ARGV[1..k] = bucket 偏移量（字节索引）
     */
    private static final String LUA_ADD = "for i=1,#ARGV do " +
            "  local offset = tonumber(ARGV[i]) " +
            "  local cur = redis.call('GETRANGE', KEYS[1], offset, offset) " +
            "  local val = (cur == '' or #cur == 0) and 0 or string.byte(cur) " +
            "  if val < 255 then " +
            "    redis.call('SETRANGE', KEYS[1], offset, string.char(val + 1)) " +
            "  end " +
            "end " +
            "return 1";

    /**
     * ADD_ALL 脚本：批量添加 N 个元素（一次 Redis 往返）
     * <p>
     * KEYS[1] = 过滤器 key
     * ARGV[1] = hashCount（每个元素占用的 bucket 数 k）
     * ARGV[2 .. N*k+1] = 所有元素的 bucket 偏移量（按每 k 个一组排列）
     * <p>
     * 对每组 k 个 bucket 的 counter 各 +1（上限 255，防溢出）
     * 返回成功添加的元素数量
     */
    private static final String LUA_ADD_ALL = "local k = tonumber(ARGV[1]) " +
            "local total = #ARGV - 1 " +
            "local count = 0 " +
            "for i = 0, total / k - 1 do " +
            "  for j = 1, k do " +
            "    local offset = tonumber(ARGV[1 + i * k + j]) " +
            "    local cur = redis.call('GETRANGE', KEYS[1], offset, offset) " +
            "    local val = (cur == '' or #cur == 0) and 0 or string.byte(cur) " +
            "    if val < 255 then " +
            "      redis.call('SETRANGE', KEYS[1], offset, string.char(val + 1)) " +
            "    end " +
            "  end " +
            "  count = count + 1 " +
            "end " +
            "return count";

    private static final String LUA_ADD_2BYTE = "for i=1,#ARGV do " +
            "  local offset = tonumber(ARGV[i]) " +
            "  local cur = redis.call('GETRANGE', KEYS[1], offset, offset + 1) " +
            "  local val = (#cur < 2) and 0 or (string.byte(cur, 1) * 256 + string.byte(cur, 2)) " +
            "  if val < 65535 then " +
            "    val = val + 1 " +
            "    redis.call('SETRANGE', KEYS[1], offset, string.char(math.floor(val / 256), val % 256)) " +
            "  end " +
            "end " +
            "return 1";

    private static final String LUA_ADD_IF_ABSENT = "local offsets = {} " +
            "local vals = {} " +
            "local missing = 0 " +
            "for i=1,#ARGV do " +
            "  local offset = tonumber(ARGV[i]) " +
            "  offsets[i] = offset " +
            "  local cur = redis.call('GETRANGE', KEYS[1], offset, offset) " +
            "  local val = (cur == '' or #cur == 0) and 0 or string.byte(cur) " +
            "  vals[i] = val " +
            "  if val == 0 then missing = 1 end " +
            "end " +
            "if missing == 0 then return 0 end " +
            "for i=1,#ARGV do " +
            "  if vals[i] < 255 then " +
            "    redis.call('SETRANGE', KEYS[1], offsets[i], string.char(vals[i] + 1)) " +
            "  end " +
            "end " +
            "return 1";

    private static final String LUA_ADD_IF_ABSENT_2BYTE = "local offsets = {} " +
            "local vals = {} " +
            "local missing = 0 " +
            "for i=1,#ARGV do " +
            "  local offset = tonumber(ARGV[i]) " +
            "  offsets[i] = offset " +
            "  local cur = redis.call('GETRANGE', KEYS[1], offset, offset + 1) " +
            "  local val = (#cur < 2) and 0 or (string.byte(cur, 1) * 256 + string.byte(cur, 2)) " +
            "  vals[i] = val " +
            "  if val == 0 then missing = 1 end " +
            "end " +
            "if missing == 0 then return 0 end " +
            "for i=1,#ARGV do " +
            "  if vals[i] < 65535 then " +
            "    local val = vals[i] + 1 " +
            "    redis.call('SETRANGE', KEYS[1], offsets[i], string.char(math.floor(val / 256), val % 256)) " +
            "  end " +
            "end " +
            "return 1";

    private static final String LUA_ADD_ALL_2BYTE = "local k = tonumber(ARGV[1]) " +
            "local total = #ARGV - 1 " +
            "local count = 0 " +
            "for i = 0, total / k - 1 do " +
            "  for j = 1, k do " +
            "    local offset = tonumber(ARGV[1 + i * k + j]) " +
            "    local cur = redis.call('GETRANGE', KEYS[1], offset, offset + 1) " +
            "    local val = (#cur < 2) and 0 or (string.byte(cur, 1) * 256 + string.byte(cur, 2)) " +
            "    if val < 65535 then " +
            "      val = val + 1 " +
            "      redis.call('SETRANGE', KEYS[1], offset, string.char(math.floor(val / 256), val % 256)) " +
            "    end " +
            "  end " +
            "  count = count + 1 " +
            "end " +
            "return count";

    /**
     * DELETE 脚本：先检查 k 个 counter 是否全部 ≥ 1，然后各 -1
     * 如果任意 counter 已经为 0，则整体不执行删除（返回 0）
     * KEYS[1] = 过滤器 key
     * ARGV[1..k] = bucket 偏移量
     */
    private static final String LUA_DELETE = "local offsets = {} " +
            "local vals = {} " +
            "for i=1,#ARGV do " +
            "  offsets[i] = tonumber(ARGV[i]) " +
            "  local cur = redis.call('GETRANGE', KEYS[1], offsets[i], offsets[i]) " +
            "  vals[i] = (cur == '' or #cur == 0) and 0 or string.byte(cur) " +
            "  if vals[i] == 0 then return 0 end " +
            "end " +
            "for i=1,#ARGV do " +
            "  redis.call('SETRANGE', KEYS[1], offsets[i], string.char(vals[i] - 1)) " +
            "end " +
            "return 1";

    /**
     * DELETE_ALL 脚本：批量安全删除 N 个元素（一次 Redis 往返）
     * <p>
     * KEYS[1] = 过滤器 key
     * ARGV[1] = hashCount k
     * ARGV[2 .. N*k+1] = 所有元素的 bucket 偏移量（按每 k 个一组排列）
     * <p>
     * 第一轮：预检所有元素的所有 bucket，任一 counter=0 → return 0（整批拒绝）
     * 第二轮：统一对所有 bucket 的 counter -1
     * 返回成功删除的元素数量
     */
    private static final String LUA_DELETE_ALL = "local k = tonumber(ARGV[1]) " +
            "local total = #ARGV - 1 " +
            "local offsets = {} " +
            "local vals = {} " +
            // 第一轮：预检所有 bucket 的 counter 都 >= 1
            "for i = 2, #ARGV do " +
            "  local offset = tonumber(ARGV[i]) " +
            "  offsets[i-1] = offset " +
            "  local cur = redis.call('GETRANGE', KEYS[1], offset, offset) " +
            "  vals[i-1] = (cur == '' or #cur == 0) and 0 or string.byte(cur) " +
            "  if vals[i-1] == 0 then return 0 end " +
            "end " +
            // 第二轮：统一 -1
            "for i = 1, total do " +
            "  redis.call('SETRANGE', KEYS[1], offsets[i], string.char(vals[i] - 1)) " +
            "end " +
            "return total / k";

    private static final String LUA_DELETE_2BYTE = "local offsets = {} " +
            "local vals = {} " +
            "for i=1,#ARGV do " +
            "  offsets[i] = tonumber(ARGV[i]) " +
            "  local cur = redis.call('GETRANGE', KEYS[1], offsets[i], offsets[i] + 1) " +
            "  vals[i] = (#cur < 2) and 0 or (string.byte(cur, 1) * 256 + string.byte(cur, 2)) " +
            "  if vals[i] == 0 then return 0 end " +
            "end " +
            "for i=1,#ARGV do " +
            "  local val = vals[i] - 1 " +
            "  redis.call('SETRANGE', KEYS[1], offsets[i], string.char(math.floor(val / 256), val % 256)) " +
            "end " +
            "return 1";

    private static final String LUA_DELETE_ALL_2BYTE = "local k = tonumber(ARGV[1]) " +
            "local total = #ARGV - 1 " +
            "local offsets = {} " +
            "local vals = {} " +
            "for i = 2, #ARGV do " +
            "  local offset = tonumber(ARGV[i]) " +
            "  offsets[i-1] = offset " +
            "  local cur = redis.call('GETRANGE', KEYS[1], offset, offset + 1) " +
            "  vals[i-1] = (#cur < 2) and 0 or (string.byte(cur, 1) * 256 + string.byte(cur, 2)) " +
            "  if vals[i-1] == 0 then return 0 end " +
            "end " +
            "for i = 1, total do " +
            "  local val = vals[i] - 1 " +
            "  redis.call('SETRANGE', KEYS[1], offsets[i], string.char(math.floor(val / 256), val % 256)) " +
            "end " +
            "return total / k";

    /**
     * EXISTS 脚本：所有 k 个 counter ≥ 1 则返回 1（可能存在），否则返回 0（一定不存在）
     * KEYS[1] = 过滤器 key
     * ARGV[1..k] = bucket 偏移量
     */
    private static final String LUA_EXISTS = "for i=1,#ARGV do " +
            "  local offset = tonumber(ARGV[i]) " +
            "  local cur = redis.call('GETRANGE', KEYS[1], offset, offset) " +
            "  local val = (cur == '' or #cur == 0) and 0 or string.byte(cur) " +
            "  if val == 0 then return 0 end " +
            "end " +
            "return 1";

    /**
     * EXISTS_ALL 脚本：批量检查 N 个元素是否全部可能存在（一次 Redis 往返）
     * <p>
     * KEYS[1] = 过滤器 key
     * ARGV[1] = hashCount（每个元素占用的 bucket 数 k）
     * ARGV[2 .. N*k+1] = 所有元素的 bucket 偏移量（按每 k 个一组排列）
     * <p>
     * 对每组 k 个 bucket 逐一检查 counter，任一组存在 counter=0 → 返回 0（该元素一定不存在）
     * 全部通过 → 返回 1
     */
    private static final String LUA_EXISTS_ALL = "local k = tonumber(ARGV[1]) " +
            "local total = #ARGV - 1 " +
            "for i = 0, total / k - 1 do " +
            "  for j = 1, k do " +
            "    local offset = tonumber(ARGV[1 + i * k + j]) " +
            "    local cur = redis.call('GETRANGE', KEYS[1], offset, offset) " +
            "    local val = (cur == '' or #cur == 0) and 0 or string.byte(cur) " +
            "    if val == 0 then return 0 end " +
            "  end " +
            "end " +
            "return 1";

    private static final String LUA_EXISTS_2BYTE = "for i=1,#ARGV do " +
            "  local offset = tonumber(ARGV[i]) " +
            "  local cur = redis.call('GETRANGE', KEYS[1], offset, offset + 1) " +
            "  local val = (#cur < 2) and 0 or (string.byte(cur, 1) * 256 + string.byte(cur, 2)) " +
            "  if val == 0 then return 0 end " +
            "end " +
            "return 1";

    private static final String LUA_EXISTS_ALL_2BYTE = "local k = tonumber(ARGV[1]) " +
            "local total = #ARGV - 1 " +
            "for i = 0, total / k - 1 do " +
            "  for j = 1, k do " +
            "    local offset = tonumber(ARGV[1 + i * k + j]) " +
            "    local cur = redis.call('GETRANGE', KEYS[1], offset, offset + 1) " +
            "    local val = (#cur < 2) and 0 or (string.byte(cur, 1) * 256 + string.byte(cur, 2)) " +
            "    if val == 0 then return 0 end " +
            "  end " +
            "end " +
            "return 1";

    // ========================== 哈希函数 ==========================

    /**
     * 使用 k 个不同种子计算元素的 k 个 bucket 索引
     * <p>
     * 采用 double hashing 方案：hash_i = (h1 + i * h2) % capacity
     * 其中 h1 = murmurHash(item), h2 = fnvHash(item)
     * 这种方式只需 2 次哈希即可生成任意 k 个均匀分布的 bucket
     * </p>
     *
     * @param item      元素字符串
     * @param capacity  过滤器总容量（bucket 数量）
     * @param hashCount 哈希函数个数 k
     * @return k 个 bucket 偏移量
     */
    private long[] getBuckets(String item, int capacity, int hashCount) {
        long[] buckets = new long[hashCount];

        // 两个独立的基础哈希值
        int h1 = HashUtil.murmur32(item.getBytes(StandardCharsets.UTF_8));
        int h2 = HashUtil.fnvHash(item);

        for (int i = 0; i < hashCount; i++) {
            // double hashing：(h1 + i * h2) mod capacity
            long combined = ((long) h1 + (long) i * h2) % capacity;
            // 确保非负
            buckets[i] = (combined < 0) ? combined + capacity : combined;
        }
        return buckets;
    }

    // ========================== 核心操作 ==========================

    /**
     * 添加元素到计数布隆过滤器
     *
     * @param key  过滤器 Redis key
     * @param item 要添加的元素
     * @return 是否添加成功
     */
    public Boolean add(String key, String item) {
        FilterMeta meta = getMeta(key);
        long[] buckets = getBuckets(item, meta.capacity, meta.hashCount);
        String[] args = toStringArray(buckets, meta.counterBytes);

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(
                meta.counterBytes == 2 ? LUA_ADD_2BYTE : LUA_ADD,
                Long.class);
        Long result = stringRedisTemplate.execute(script, Collections.singletonList(key), (Object[]) args);
        return result != null && result == 1L;
    }

    /**
     * 添加元素（long 类型 ID）
     */
    public Boolean add(String key, long id) {
        return add(key, String.valueOf(id));
    }

    /**
     * 添加元素（byte[] 类型 ID）
     */
    public Boolean add(String key, byte[] id) {
        return add(key, new String(id, StandardCharsets.UTF_8));
    }

    public Boolean addIfAbsent(String key, String item) {
        FilterMeta meta = getMeta(key);
        long[] buckets = getBuckets(item, meta.capacity, meta.hashCount);
        String[] args = toStringArray(buckets, meta.counterBytes);

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(
                meta.counterBytes == 2 ? LUA_ADD_IF_ABSENT_2BYTE : LUA_ADD_IF_ABSENT,
                Long.class);
        Long result = stringRedisTemplate.execute(script, Collections.singletonList(key), (Object[]) args);
        return result != null && result == 1L;
    }

    public Boolean addIfAbsentLong(String key, Long id) {
        if (id == null) {
            return false;
        }
        return addIfAbsent(key, String.valueOf(id));
    }

    /**
     * 批量添加多个元素到计数布隆过滤器（一次 Redis 往返）
     * <p>
     * 将所有元素的 k 个 bucket 偏移量拼接到一次 Lua 调用中执行，
     * 避免 N 次独立调用带来的 N 次网络 RTT。
     *
     * @param key   过滤器 Redis key
     * @param items 要添加的元素列表（String）
     * @return 成功添加的元素数量
     */
    @Deprecated(forRemoval = true)
    public long addAllItems(String key, List<String> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        requireLegacyBatch(items);
        FilterMeta meta = getMeta(key);

        // ARGV[0] = hashCount, ARGV[1..N*k] = 所有 bucket 偏移量
        String[] args = new String[1 + items.size() * meta.hashCount];
        args[0] = String.valueOf(meta.hashCount);

        int idx = 1;
        for (String item : items) {
            long[] buckets = getBuckets(item, meta.capacity, meta.hashCount);
            for (long b : buckets) {
                args[idx++] = String.valueOf(b * meta.counterBytes);
            }
        }

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(
                meta.counterBytes == 2 ? LUA_ADD_ALL_2BYTE : LUA_ADD_ALL,
                Long.class);
        Long result = stringRedisTemplate.execute(script, Collections.singletonList(key), (Object[]) args);
        return result != null ? result : 0;
    }

    /**
     * 批量添加多个元素（byte[] 类型 ID 列表）
     */
    @Deprecated(forRemoval = true)
    public long addAll(String key, List<byte[]> ids) {
        requireLegacyBatch(ids);
        List<String> items = new ArrayList<>(ids.size());
        for (byte[] id : ids) {
            items.add(new String(id, StandardCharsets.UTF_8));
        }
        return addAllItems(key, items);
    }

    /**
     * 批量添加多个元素（Long 类型 ID 列表）
     */
    @Deprecated(forRemoval = true)
    public long addAllLongs(String key, List<Long> ids) {
        requireLegacyBatch(ids);
        List<String> items = new ArrayList<>(ids.size());
        for (Long id : ids) {
            items.add(String.valueOf(id));
        }
        return addAllItems(key, items);
    }

    /**
     * 从计数布隆过滤器中删除元素
     * <p>
     * 安全删除：只有当所有 k 个 counter 都 ≥ 1 时才执行 -1。
     * 如果任一 counter 为 0，说明该元素可能不存在，返回 false 且不做任何修改。
     * </p>
     *
     * @param key  过滤器 Redis key
     * @param item 要删除的元素
     * @return true: 删除成功, false: 元素不存在（counter 已为 0）
     */
    public Boolean delete(String key, String item) {
        FilterMeta meta = getMeta(key);
        long[] buckets = getBuckets(item, meta.capacity, meta.hashCount);
        String[] args = toStringArray(buckets, meta.counterBytes);

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(
                meta.counterBytes == 2 ? LUA_DELETE_2BYTE : LUA_DELETE,
                Long.class);
        Long result = stringRedisTemplate.execute(script, Collections.singletonList(key), (Object[]) args);
        return result != null && result == 1L;
    }

    /**
     * 删除元素（long 类型 ID）
     */
    public Boolean delete(String key, long id) {
        return delete(key, String.valueOf(id));
    }

    /**
     * 删除元素（byte[] 类型 ID）
     */
    public Boolean delete(String key, byte[] id) {
        return delete(key, new String(id, StandardCharsets.UTF_8));
    }

    /**
     * 批量安全删除多个元素（一次 Redis 往返）
     * <p>
     * 先预检所有元素的所有 bucket counter 均 ≥ 1，
     * 全部通过后再统一 -1。任一元素不存在则整批拒绝（返回 0）。
     *
     * @param key   过滤器 Redis key
     * @param items 要删除的元素列表（String）
     * @return 成功删除的元素数量，0 表示整批失败
     */
    @Deprecated(forRemoval = true)
    public long deleteAllItems(String key, List<String> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        requireLegacyBatch(items);
        FilterMeta meta = getMeta(key);

        String[] args = new String[1 + items.size() * meta.hashCount];
        args[0] = String.valueOf(meta.hashCount);

        int idx = 1;
        for (String item : items) {
            long[] buckets = getBuckets(item, meta.capacity, meta.hashCount);
            for (long b : buckets) {
                args[idx++] = String.valueOf(b * meta.counterBytes);
            }
        }

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(
                meta.counterBytes == 2 ? LUA_DELETE_ALL_2BYTE : LUA_DELETE_ALL,
                Long.class);
        Long result = stringRedisTemplate.execute(script, Collections.singletonList(key), (Object[]) args);
        return result != null ? result : 0;
    }

    /**
     * 批量安全删除多个元素（byte[] 类型 ID 列表）
     */
    @Deprecated(forRemoval = true)
    public long deleteAll(String key, List<byte[]> ids) {
        requireLegacyBatch(ids);
        List<String> items = new ArrayList<>(ids.size());
        for (byte[] id : ids) {
            items.add(new String(id, StandardCharsets.UTF_8));
        }
        return deleteAllItems(key, items);
    }

    /**
     * 批量安全删除多个元素（Long 类型 ID 列表）
     */
    @Deprecated(forRemoval = true)
    public long deleteAllLongs(String key, List<Long> ids) {
        requireLegacyBatch(ids);
        List<String> items = new ArrayList<>(ids.size());
        for (Long id : ids) {
            items.add(String.valueOf(id));
        }
        return deleteAllItems(key, items);
    }

    /**
     * 检查元素是否可能存在于过滤器中
     *
     * @param key  过滤器 Redis key
     * @param item 要检查的元素
     * @return true: 可能存在, false: 一定不存在
     */
    public Boolean exists(String key, String item) {
        FilterMeta meta = getMeta(key);
        long[] buckets = getBuckets(item, meta.capacity, meta.hashCount);
        String[] args = toStringArray(buckets, meta.counterBytes);

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(
                meta.counterBytes == 2 ? LUA_EXISTS_2BYTE : LUA_EXISTS,
                Long.class);
        Long result = stringRedisTemplate.execute(script, Collections.singletonList(key), (Object[]) args);
        return result != null && result == 1L;
    }

    /**
     * 检查元素是否可能存在（long 类型 ID）
     */
    public Boolean exists(String key, long id) {
        return exists(key, String.valueOf(id));
    }

    /**
     * 检查元素是否可能存在（byte[] 类型 ID）
     */
    public Boolean exists(String key, byte[] id) {
        return exists(key, new String(id, StandardCharsets.UTF_8));
    }

    /**
     * 批量检查多个元素是否全部可能存在于过滤器中（一次 Redis 往返）
     * <p>
     * 将所有元素的 k 个 bucket 偏移量拼接到一次 Lua 调用中执行，
     * 避免 N 次独立调用带来的 N 次网络 RTT。
     *
     * @param key   过滤器 Redis key
     * @param items 要检查的元素列表（String）
     * @return true: 所有元素可能存在, false: 至少有一个一定不存在
     */
    @Deprecated(forRemoval = true)
    public Boolean existsAllItems(String key, List<String> items) {
        if (items == null || items.isEmpty()) {
            return true;
        }
        requireLegacyBatch(items);
        FilterMeta meta = getMeta(key);

        // ARGV[0] = hashCount, ARGV[1..N*k] = 所有 bucket 偏移量
        String[] args = new String[1 + items.size() * meta.hashCount];
        args[0] = String.valueOf(meta.hashCount);

        int idx = 1;
        for (String item : items) {
            long[] buckets = getBuckets(item, meta.capacity, meta.hashCount);
            for (long b : buckets) {
                args[idx++] = String.valueOf(b * meta.counterBytes);
            }
        }

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(
                meta.counterBytes == 2 ? LUA_EXISTS_ALL_2BYTE : LUA_EXISTS_ALL,
                Long.class);
        Long result = stringRedisTemplate.execute(script, Collections.singletonList(key), (Object[]) args);
        return result != null && result == 1L;
    }

    /**
     * 批量检查多个元素是否全部可能存在（byte[] 类型 ID 列表）
     *
     * @param key 过滤器 Redis key
     * @param ids 要检查的 ID 列表（byte[]）
     * @return true: 所有元素可能存在, false: 至少有一个一定不存在
     */
    @Deprecated(forRemoval = true)
    public Boolean existsAll(String key, List<byte[]> ids) {
        requireLegacyBatch(ids);
        List<String> items = new ArrayList<>(ids.size());
        for (byte[] id : ids) {
            items.add(new String(id, StandardCharsets.UTF_8));
        }
        return existsAllItems(key, items);
    }

    /**
     * 批量检查多个元素是否全部可能存在（long 类型 ID 列表）
     *
     * @param key 过滤器 Redis key
     * @param ids 要检查的 ID 列表（Long）
     * @return true: 所有元素可能存在, false: 至少有一个一定不存在
     */
    @Deprecated(forRemoval = true)
    public Boolean existsAllLongs(String key, List<Long> ids) {
        requireLegacyBatch(ids);
        List<String> items = new ArrayList<>(ids.size());
        for (Long id : ids) {
            items.add(String.valueOf(id));
        }
        return existsAllItems(key, items);
    }

    // ========================== 辅助操作 ==========================

    /**
     * 判断过滤器 key 是否存在于 Redis
     */
    public Boolean isExists(String key) {
        return stringRedisTemplate.hasKey(key);
    }

    /**
     * 删除整个过滤器及其元数据
     */
    public Boolean deleteFilter(String key) {
        metaCache.invalidate(key);
        stringRedisTemplate.delete(key + ":meta");
        return stringRedisTemplate.delete(key);
    }

    // ========================== 内部工具 ==========================

    /**
     * 旧实现仍以整批 Lua 独占 Redis，因此必须在构造参数和脚本之前拒绝无界输入。
     */
    private static void requireLegacyBatch(List<?> items) {
        if (items == null) {
            throw new IllegalArgumentException("Legacy Counting Bloom batch must not be null");
        }
        if (items.size() > LEGACY_MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "Legacy Counting Bloom batch must not exceed "
                            + LEGACY_MAX_BATCH_SIZE
                            + " items");
        }
    }

    /**
     * long[] → String[]（作为 Lua 脚本 ARGV 参数）
     */
    private String[] toStringArray(long[] buckets) {
        return toStringArray(buckets, 1);
    }

    private String[] toStringArray(long[] buckets, int counterBytes) {
        String[] args = new String[buckets.length];
        for (int i = 0; i < buckets.length; i++) {
            args[i] = String.valueOf(buckets[i] * counterBytes);
        }
        return args;
    }
}
