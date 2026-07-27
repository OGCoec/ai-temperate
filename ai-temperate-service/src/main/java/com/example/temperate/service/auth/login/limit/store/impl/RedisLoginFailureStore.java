package com.example.temperate.service.auth.login.limit.store.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.auth.login.limit.dto.ProtectedLoginAttempt;
import com.example.temperate.service.auth.login.limit.enums.LoginLimitDecision;
import com.example.temperate.service.auth.login.limit.enums.LoginFailureBucket;
import com.example.temperate.service.auth.login.limit.exception.LoginRateLimitInfrastructureException;
import com.example.temperate.service.auth.login.limit.store.LoginFailureStore;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 使用 Redis Lua 脚本维护登录失败窗口和封禁状态的实现。
 *
 * <p>失败计数递增、阈值判断与封禁写入必须在同一原子脚本中执行，避免并发失败请求同时越过阈值；
 * 密码和验证码失败桶独立计数，但共用受保护主体的封禁键。</p>
 */
@Component
public final class RedisLoginFailureStore implements LoginFailureStore {

    private static final Duration MAXIMUM_FAILURE_WINDOW = Duration.ofHours(1);
    private static final Duration MAXIMUM_BLOCK_DURATION = Duration.ofDays(1);
    private static final int MAXIMUM_FAILURE_THRESHOLD = 20;
    private static final RedisScript<Long> CHECK_SCRIPT = script("check_login_limit.lua");
    private static final RedisScript<Long> RECORD_SCRIPT = script("record_login_failure.lua");
    private static final RedisScript<Long> CLEAR_SCRIPT = script("clear_login_failures.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory redisKeyFactory;
    private final long failureWindowMillis;
    private final int maximumFailures;
    private final long blockDurationMillis;

    public RedisLoginFailureStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory redisKeyFactory,
            @Value("${app.auth-session.login-limit.window:5m}") Duration failureWindow,
            @Value("${app.auth-session.login-limit.max-failures:5}") int maximumFailures,
            @Value("${app.auth-session.login-limit.block-duration:2h}") Duration blockDuration) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.redisKeyFactory = Objects.requireNonNull(redisKeyFactory, "redisKeyFactory must not be null");
        long validFailureWindowMillis = requireMilliseconds(
                "Login failure window", failureWindow, MAXIMUM_FAILURE_WINDOW);
        if (maximumFailures < 1 || maximumFailures > MAXIMUM_FAILURE_THRESHOLD) {
            throw new IllegalArgumentException(
                    "Login maximum failures must be between 1 and 20.");
        }
        long validBlockDurationMillis = requireMilliseconds(
                "Login block duration", blockDuration, MAXIMUM_BLOCK_DURATION);
        this.failureWindowMillis = validFailureWindowMillis;
        this.maximumFailures = maximumFailures;
        this.blockDurationMillis = validBlockDurationMillis;
    }

    @Override
    public LoginLimitDecision check(
            ProtectedLoginAttempt attempt, LoginFailureBucket bucket) {
        ProtectedLoginAttempt valid = requireAttempt(attempt);
        long result = execute(
                CHECK_SCRIPT,
                List.of(
                        redisKeyFactory.loginBlockKey(valid.actorHash()),
                        redisKeyFactory.globalDeviceBlockKey(valid.globalDeviceHash())));
        return decision(result, "check");
    }

    @Override
    public LoginLimitDecision recordFailure(
            ProtectedLoginAttempt attempt, LoginFailureBucket bucket) {
        ProtectedLoginAttempt valid = requireAttempt(attempt);
        String failureKey = Objects.requireNonNull(bucket) == LoginFailureBucket.PASSWORD
                ? redisKeyFactory.loginPasswordFailureKey(valid.actorHash())
                : redisKeyFactory.loginCodeFailureKey(valid.actorHash());
        // 脚本将计数递增与封禁决定合并，不能改为读-改-写，否则并发失败会丢失计数。
        long result = execute(
                RECORD_SCRIPT,
                List.of(
                        failureKey,
                        redisKeyFactory.loginBlockKey(valid.actorHash()),
                        redisKeyFactory.globalDeviceBlockKey(valid.globalDeviceHash())),
                Long.toString(failureWindowMillis),
                Integer.toString(maximumFailures),
                Long.toString(blockDurationMillis));
        return decision(result, "failure record");
    }

    @Override
    public void clearFailures(ProtectedLoginAttempt attempt) {
        ProtectedLoginAttempt valid = requireAttempt(attempt);
        long result = execute(
                CLEAR_SCRIPT,
                List.of(
                        redisKeyFactory.loginPasswordFailureKey(valid.actorHash()),
                        redisKeyFactory.loginCodeFailureKey(valid.actorHash())));
        if (result < 0 || result > 2) {
            throw unavailable("Unexpected login failure clear result.");
        }
    }

    private long execute(
            RedisScript<Long> script, List<String> keys, Object... arguments) {
        Long result;
        try {
            result = redisTemplate.execute(script, keys, arguments);
        } catch (RuntimeException exception) {
            throw unavailable("Redis login limit script execution failed.", exception);
        }
        if (result == null) {
            throw unavailable("Redis login limit script returned no result.");
        }
        return result;
    }

    private static LoginLimitDecision decision(long result, String operation) {
        if (result == 0L) {
            return LoginLimitDecision.ALLOWED;
        }
        if (result == 1L) {
            return LoginLimitDecision.BLOCKED;
        }
        throw unavailable("Unexpected login limit " + operation + " result.");
    }

    private static ProtectedLoginAttempt requireAttempt(ProtectedLoginAttempt attempt) {
        if (attempt == null
                || attempt.identifierHash() == null
                || attempt.actorHash() == null
                || attempt.globalDeviceHash() == null) {
            throw new IllegalArgumentException("Protected login attempt is required.");
        }
        return attempt;
    }

    private static long requireMilliseconds(
            String name, Duration value, Duration maximum) {
        if (value == null || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    name + " must be at least one millisecond and within its maximum.");
        }
        long milliseconds;
        try {
            milliseconds = value.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " is too large.", exception);
        }
        if (milliseconds < 1L) {
            throw new IllegalArgumentException(name + " must be at least one millisecond.");
        }
        return milliseconds;
    }

    private static RedisScript<Long> script(String fileName) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/auth-login/" + fileName));
        script.setResultType(Long.class);
        return script;
    }

    private static LoginRateLimitInfrastructureException unavailable(String message) {
        return new LoginRateLimitInfrastructureException(message);
    }

    private static LoginRateLimitInfrastructureException unavailable(
            String message, Throwable cause) {
        return new LoginRateLimitInfrastructureException(message, cause);
    }
}
