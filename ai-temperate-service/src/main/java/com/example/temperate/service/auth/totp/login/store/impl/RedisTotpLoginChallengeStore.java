package com.example.temperate.service.auth.totp.login.store.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.totp.config.TotpProperties;
import com.example.temperate.service.auth.totp.login.store.TotpLoginChallengeSnapshot;
import com.example.temperate.service.auth.totp.login.store.TotpLoginChallengeStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 使用 Redis Hash 和 Lua 原子维护 TOTP 登录挑战、失败次数及时间片防重放状态。
 *
 * <p>挑战和重放标记分别使用受 HMAC 保护的 Key；成功消费先原子领取时间片再删除挑战，确保并发请求最多
 * 只有一个能够进入会话签发阶段。Redis 异常统一 Fail Closed。</p>
 */
@Component
public final class RedisTotpLoginChallengeStore implements TotpLoginChallengeStore {

    private static final Duration REPLAY_TTL = Duration.ofSeconds(90);
    private static final RedisScript<Long> CREATE = longScript(
            "create_totp_login_challenge.lua");
    private static final RedisScript<List> GET = listScript(
            "get_totp_login_challenge.lua");
    private static final RedisScript<List> RECORD_FAILURE = listScript(
            "record_totp_login_failure.lua");
    private static final RedisScript<Long> CONSUME_SUCCESS = longScript(
            "consume_totp_login_success.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final TotpProperties properties;

    public RedisTotpLoginChallengeStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            TotpProperties properties) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public void create(
            HmacIdentifier flowId,
            HmacIdentifier deviceId,
            long userId,
            Instant createdAt,
            Duration ttl) {
        if (userId <= 0 || createdAt == null || ttl == null
                || ttl.isNegative() || ttl.isZero()) {
            throw unavailable(null);
        }
        long expiresAt = createdAt.plus(ttl).toEpochMilli();
        Long status = execute(
                CREATE,
                List.of(keyFactory.totpLoginChallengeKey(require(flowId))),
                "1",
                Long.toString(userId),
                require(deviceId).value(),
                Long.toString(createdAt.toEpochMilli()),
                Long.toString(expiresAt),
                Long.toString(ttl.toMillis()));
        if (status != 0L) {
            throw unavailable(null);
        }
    }

    @Override
    public TotpLoginChallengeSnapshot getRequired(
            HmacIdentifier flowId,
            HmacIdentifier deviceId,
            Instant now) {
        List<?> result = execute(
                GET,
                List.of(keyFactory.totpLoginChallengeKey(require(flowId))),
                require(deviceId).value(),
                Long.toString(requireNow(now).toEpochMilli()));
        int status = status(result);
        if (status != 0) {
            throw flowError(status);
        }
        if (result.size() < 4) {
            throw unavailable(null);
        }
        return new TotpLoginChallengeSnapshot(
                number(result.get(1)),
                Math.toIntExact(number(result.get(2))),
                Instant.ofEpochMilli(number(result.get(3))));
    }

    @Override
    public int recordFailure(
            HmacIdentifier flowId,
            HmacIdentifier deviceId,
            Instant now) {
        List<?> result = execute(
                RECORD_FAILURE,
                List.of(keyFactory.totpLoginChallengeKey(require(flowId))),
                require(deviceId).value(),
                Long.toString(requireNow(now).toEpochMilli()),
                Integer.toString(properties.maxAttempts()));
        int status = status(result);
        if (status == 3) {
            throw error(
                    LoginErrorCode.TOTP_ATTEMPTS_EXHAUSTED,
                    "TOTP login attempts were exhausted.");
        }
        if (status != 0) {
            throw flowError(status);
        }
        return Math.toIntExact(number(result.get(1)));
    }

    @Override
    public void consumeSuccessful(
            HmacIdentifier flowId,
            HmacIdentifier deviceId,
            HmacIdentifier replayId,
            Instant now) {
        Long status = execute(
                CONSUME_SUCCESS,
                List.of(
                        keyFactory.totpLoginChallengeKey(require(flowId)),
                        keyFactory.totpUsedTimeStepKey(require(replayId))),
                require(deviceId).value(),
                Long.toString(requireNow(now).toEpochMilli()),
                Long.toString(REPLAY_TTL.toMillis()));
        if (status == 4L) {
            throw error(
                    LoginErrorCode.TOTP_CODE_REPLAYED,
                    "TOTP code was already used.");
        }
        if (status != 0L) {
            throw flowError(Math.toIntExact(status));
        }
    }

    private <T> T execute(
            RedisScript<T> script,
            List<String> keys,
            Object... args) {
        try {
            T result = redisTemplate.execute(script, keys, args);
            if (result == null) {
                throw unavailable(null);
            }
            return result;
        } catch (LoginException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private static LoginException flowError(int status) {
        return switch (status) {
            case 1 -> error(
                    LoginErrorCode.TOTP_FLOW_EXPIRED,
                    "TOTP login flow is missing or expired.");
            case 2 -> error(
                    LoginErrorCode.TOTP_FLOW_FORBIDDEN,
                    "TOTP login flow is forbidden.");
            default -> unavailable(null);
        };
    }

    private static HmacIdentifier require(HmacIdentifier identifier) {
        if (identifier == null) {
            throw unavailable(null);
        }
        return identifier;
    }

    private static Instant requireNow(Instant now) {
        if (now == null) {
            throw unavailable(null);
        }
        return now;
    }

    private static int status(List<?> result) {
        if (result == null || result.isEmpty()) {
            throw unavailable(null);
        }
        return Math.toIntExact(number(result.getFirst()));
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof byte[] bytes) {
            return Long.parseLong(new String(bytes, StandardCharsets.UTF_8));
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static LoginException error(
            LoginErrorCode code,
            String message) {
        return new LoginException(code, message);
    }

    private static LoginException unavailable(Throwable cause) {
        return new LoginException(
                LoginErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                "TOTP login storage is unavailable.",
                cause);
    }

    private static RedisScript<Long> longScript(String name) {
        return script(name, Long.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RedisScript<List> listScript(String name) {
        return (RedisScript) script(name, List.class);
    }

    private static <T> RedisScript<T> script(String name, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/auth-totp/" + name));
        script.setResultType(resultType);
        return script;
    }
}
