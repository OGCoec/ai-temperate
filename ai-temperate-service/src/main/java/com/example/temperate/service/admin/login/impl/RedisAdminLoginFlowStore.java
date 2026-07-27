package com.example.temperate.service.admin.login.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.login.AdminLoginFlow;
import com.example.temperate.service.admin.login.AdminLoginFlowStore;
import com.example.temperate.service.admin.login.ProtectedAdminLoginAccess;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 使用独立管理员 Redis Key 保存十分钟登录 Flow，并通过 Lua 原子创建和消费。
 */
@Component
public final class RedisAdminLoginFlowStore implements AdminLoginFlowStore {

    private static final String CREATE_SCRIPT = """
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return 0
            end
            redis.call('HSET', KEYS[1],
              'flowCsrfId', ARGV[1],
              'challengeId', ARGV[2],
              'deviceId', ARGV[3],
              'createdAt', ARGV[4],
              'expiresAt', ARGV[5])
            redis.call('EXPIRE', KEYS[1], ARGV[6])
            return 1
            """;
    private static final String CONSUME_SCRIPT = """
            if redis.call('HGET', KEYS[1], 'flowCsrfId') ~= ARGV[1] then
              return 0
            end
            if redis.call('HGET', KEYS[1], 'challengeId') ~= ARGV[2] then
              return 0
            end
            if redis.call('HGET', KEYS[1], 'deviceId') ~= ARGV[3] then
              return 0
            end
            return redis.call('DEL', KEYS[1])
            """;

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;

    public RedisAdminLoginFlowStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
    }

    @Override
    public void create(AdminLoginFlow flow) {
        Objects.requireNonNull(flow);
        ProtectedAdminLoginAccess access = Objects.requireNonNull(flow.access());
        long ttl = Duration.between(flow.createdAt(), flow.expiresAt()).toSeconds();
        if (ttl <= 0L) {
            throw new IllegalArgumentException("Admin login flow TTL must be positive.");
        }
        try {
            Long result = redisTemplate.execute(
                    script(CREATE_SCRIPT, Long.class),
                    List.of(key(access)),
                    access.flowCsrfId().value(),
                    access.challengeId().value(),
                    access.deviceId().value(),
                    flow.createdAt().toString(),
                    flow.expiresAt().toString(),
                    Long.toString(ttl));
            if (result == null || result != 1L) {
                throw unavailable(null);
            }
        } catch (AdminException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public AdminLoginFlow getRequired(ProtectedAdminLoginAccess access, Instant now) {
        try {
            Map<String, String> values =
                    redisTemplate.<String, String>opsForHash().entries(key(access));
            if (values.isEmpty()) {
                throw expired();
            }
            if (!access.flowCsrfId().value().equals(values.get("flowCsrfId"))
                    || !access.challengeId().value().equals(values.get("challengeId"))
                    || !access.deviceId().value().equals(values.get("deviceId"))) {
                throw invalid();
            }
            Instant createdAt = Instant.parse(values.get("createdAt"));
            Instant expiresAt = Instant.parse(values.get("expiresAt"));
            if (!expiresAt.isAfter(now)) {
                throw expired();
            }
            return new AdminLoginFlow(access, createdAt, expiresAt);
        } catch (AdminException exception) {
            throw exception;
        } catch (DateTimeParseException | NullPointerException exception) {
            throw invalid();
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void consume(ProtectedAdminLoginAccess access) {
        try {
            Long result = redisTemplate.execute(
                    script(CONSUME_SCRIPT, Long.class),
                    List.of(key(access)),
                    access.flowCsrfId().value(),
                    access.challengeId().value(),
                    access.deviceId().value());
            if (result == null || result != 1L) {
                throw invalid();
            }
        } catch (AdminException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private String key(ProtectedAdminLoginAccess access) {
        return keyFactory.adminLoginFlowKey(access.flowId());
    }

    private static AdminException expired() {
        return new AdminException(
                AdminErrorCode.ADMIN_FLOW_EXPIRED,
                "Administrator login flow has expired.",
                null,
                true,
                false);
    }

    private static AdminException invalid() {
        return new AdminException(
                AdminErrorCode.ADMIN_FLOW_INVALID,
                "Administrator login flow is invalid.",
                null,
                true,
                false);
    }

    private static AdminException unavailable(Throwable cause) {
        return new AdminException(
                AdminErrorCode.ADMIN_INFRASTRUCTURE_UNAVAILABLE,
                "Administrator login flow storage is unavailable.",
                cause);
    }

    private static <T> DefaultRedisScript<T> script(String source, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(type);
        return script;
    }
}
