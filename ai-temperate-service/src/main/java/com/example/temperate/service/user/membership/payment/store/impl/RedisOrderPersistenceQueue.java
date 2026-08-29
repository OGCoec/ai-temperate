package com.example.temperate.service.user.membership.payment.store.impl;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.persistence.OrderPersistToken;
import com.example.temperate.service.user.membership.payment.store.OrderPersistenceQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 该实现是来通过 dirty/processing ZSet 管理会员订单版本化持久化任务，并以精确领取分值隔离超时 Worker。
 *
 * <p>数据库成功后完成脚本只会删除版本相等的终态快照；更高版本已出现时，旧令牌只能清理自身。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class RedisOrderPersistenceQueue implements OrderPersistenceQueue {

    // complete 需要逐个校验不同订单 Hash 的版本和终态，因此硬上限固定为 100，不能随通用 Redis 批次放大。
    private static final int MAXIMUM_BATCH = 100;
    private static final RedisScript<List> CLAIM = listScript("order_persist_claim.lua");
    private static final RedisScript<Long> RECOVER = longScript("order_persist_recover.lua");
    private static final RedisScript<Long> REQUEUE = longScript("order_persist_requeue.lua");
    private static final RedisScript<Long> COMPLETE = longScript("order_persist_complete.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;

    public RedisOrderPersistenceQueue(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
    }

    @Override
    public long dirtySize() {
        return zsetSize(keyFactory.orderPersistenceDirtyKey());
    }

    @Override
    public long processingSize() {
        return zsetSize(keyFactory.orderPersistenceProcessingKey());
    }

    @Override
    public List<OrderPersistToken> claim(int maximum, long claimedAtEpochMillis) {
        requireEpochMillis(claimedAtEpochMillis, "claim time");
        List<String> members = executeList(
                CLAIM,
                List.of(
                        keyFactory.orderPersistenceDirtyKey(),
                        keyFactory.orderPersistenceProcessingKey()),
                Integer.toString(requireBatch(maximum)),
                Long.toString(claimedAtEpochMillis));
        try {
            return members.stream()
                    .map(member -> OrderPersistToken.claimed(member, claimedAtEpochMillis))
                    .toList();
        } catch (IllegalArgumentException exception) {
            throw unavailable("Redis order persistence token is malformed.", exception);
        }
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
                        keyFactory.orderPersistenceDirtyKey(),
                        keyFactory.orderPersistenceProcessingKey()),
                Long.toString(cutoffEpochMillis),
                Integer.toString(requireBatch(maximum)),
                Long.toString(readyAtEpochMillis)));
    }

    @Override
    public int requeue(
            Collection<OrderPersistToken> tokens,
            long readyAtEpochMillis) {
        List<OrderPersistToken> valid = bounded(tokens);
        if (valid.isEmpty()) {
            return 0;
        }
        requireEpochMillis(readyAtEpochMillis, "requeue time");
        List<Object> arguments = new ArrayList<>();
        arguments.add(Integer.toString(valid.size()));
        arguments.add(Long.toString(readyAtEpochMillis));
        valid.forEach(token -> {
            arguments.add(token.member());
            arguments.add(Long.toString(token.claimedAtEpochMillis()));
        });
        return Math.toIntExact(executeLong(
                REQUEUE,
                List.of(
                        keyFactory.orderPersistenceDirtyKey(),
                        keyFactory.orderPersistenceProcessingKey()),
                arguments.toArray()));
    }

    @Override
    public int complete(Collection<OrderPersistToken> tokens) {
        List<OrderPersistToken> valid = bounded(tokens);
        if (valid.isEmpty()) {
            return 0;
        }
        List<String> keys = new ArrayList<>();
        keys.add(keyFactory.orderPersistenceProcessingKey());
        keys.add(keyFactory.orderPersistenceDirtyKey());
        List<Object> arguments = new ArrayList<>();
        arguments.add(Integer.toString(valid.size()));
        valid.forEach(token -> {
            keys.add(keyFactory.membershipOrderSnapshotKey(
                    new MembershipOrderRedisId(token.orderId())));
            arguments.add(token.member());
            arguments.add(Long.toString(token.claimedAtEpochMillis()));
            arguments.add(Long.toString(token.stateVersion()));
        });
        return Math.toIntExact(executeLong(COMPLETE, keys, arguments.toArray()));
    }

    private static List<OrderPersistToken> bounded(
            Collection<OrderPersistToken> tokens) {
        Objects.requireNonNull(tokens, "tokens must not be null");
        List<OrderPersistToken> values = tokens.stream()
                .map(Objects::requireNonNull)
                .distinct()
                .toList();
        if (values.size() > MAXIMUM_BATCH) {
            throw new IllegalArgumentException("Order persistence batch exceeds 100 tokens.");
        }
        return values;
    }

    private static int requireBatch(int value) {
        if (value < 1 || value > MAXIMUM_BATCH) {
            throw new IllegalArgumentException(
                    "Order persistence batch must be between 1 and 100.");
        }
        return value;
    }

    private static void requireEpochMillis(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private long executeLong(
            RedisScript<Long> script,
            List<String> keys,
            Object... arguments) {
        try {
            Long result = redisTemplate.execute(script, keys, arguments);
            if (result == null || result < 0) {
                throw unavailable("Redis order persistence script returned an invalid count.");
            }
            return result;
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis order persistence script failed.", exception);
        }
    }

    private long zsetSize(String key) {
        try {
            Long size = redisTemplate.opsForZSet().zCard(key);
            if (size == null || size < 0L) {
                throw unavailable("Redis order persistence ZSet size is invalid.");
            }
            return size;
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis order persistence ZSet size failed.", exception);
        }
    }

    private List<String> executeList(
            RedisScript<List> script,
            List<String> keys,
            Object... arguments) {
        try {
            List<?> result = redisTemplate.execute(script, keys, arguments);
            if (result == null || result.size() > MAXIMUM_BATCH) {
                throw unavailable("Redis order persistence claim result is invalid.");
            }
            return result.stream().map(RedisOrderPersistenceQueue::text).toList();
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis order persistence claim failed.", exception);
        }
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
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
