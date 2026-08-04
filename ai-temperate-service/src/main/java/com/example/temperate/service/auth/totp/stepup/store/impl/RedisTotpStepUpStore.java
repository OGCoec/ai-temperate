package com.example.temperate.service.auth.totp.stepup.store.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.totp.config.TotpProperties;
import com.example.temperate.service.auth.totp.management.TotpManagementAction;
import com.example.temperate.service.auth.totp.stepup.store.TotpStepUpStore;
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
 * 使用 Redis Lua 维护 TOTP 敏感操作的验证码流程标记和一次性复验凭证。
 *
 * <p>全部 Key 只包含 HMAC 标识；流程提升为凭证和凭证消费均为单脚本原子操作，防止相同第一因子结果
 * 被并发用于开启、轮换或关闭等不同动作。</p>
 */
@Component
public final class RedisTotpStepUpStore implements TotpStepUpStore {

    private static final RedisScript<Long> CREATE = script("create_totp_step_up.lua");
    private static final RedisScript<Long> REQUIRE = script("require_totp_step_up.lua");
    private static final RedisScript<Long> PROMOTE = script("promote_totp_step_up.lua");
    private static final RedisScript<List> RECORD_FAILURE = listScript(
            "record_totp_step_up_failure.lua");
    private static final RedisScript<Long> CONSUME = script(
            "consume_totp_step_up_proof.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final AuthSessionSecretProtector protector;
    private final TotpProperties properties;

    public RedisTotpStepUpStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            AuthSessionSecretProtector protector,
            TotpProperties properties) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.protector = Objects.requireNonNull(protector);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public void bindCodeFlow(
            String rawFlowToken,
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            Instant createdAt,
            Duration ttl) {
        create(
                keyFactory.totpStepUpFlowKey(
                        flowToken(rawFlowToken)),
                userId, deviceInstallationId, action, createdAt, ttl);
    }

    @Override
    public void requireCodeFlow(
            String rawFlowToken,
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            Instant now) {
        require(
                keyFactory.totpStepUpFlowKey(
                        flowToken(rawFlowToken)),
                userId, deviceInstallationId, action, now);
    }

    @Override
    public void promoteCodeFlowToProof(
            String rawFlowToken,
            String rawProofToken,
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            Instant now,
            Duration ttl) {
        HmacIdentifier deviceHash = device(deviceInstallationId);
        long expiresAt = now.plus(ttl).toEpochMilli();
        Long status = execute(
                PROMOTE,
                List.of(
                        keyFactory.totpStepUpFlowKey(
                                flowToken(rawFlowToken)),
                        keyFactory.totpStepUpProofKey(
                                proofToken(rawProofToken))),
                Long.toString(userId),
                deviceHash.value(),
                requireAction(action).name(),
                Long.toString(now.toEpochMilli()),
                Long.toString(expiresAt),
                Long.toString(ttl.toMillis()));
        if (status != 0L) {
            throw stepUpRequired();
        }
    }

    @Override
    public void createProof(
            String rawProofToken,
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            Instant createdAt,
            Duration ttl) {
        create(
                keyFactory.totpStepUpProofKey(
                        proofToken(rawProofToken)),
                userId, deviceInstallationId, action, createdAt, ttl);
    }

    @Override
    public void requireProof(
            String rawProofToken,
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            Instant now) {
        require(
                keyFactory.totpStepUpProofKey(
                        proofToken(rawProofToken)),
                userId, deviceInstallationId, action, now);
    }

    @Override
    public void recordProofFailure(
            String rawProofToken,
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            Instant now) {
        List<?> result = execute(
                RECORD_FAILURE,
                List.of(keyFactory.totpStepUpProofKey(
                        proofToken(rawProofToken))),
                Long.toString(userId),
                device(deviceInstallationId).value(),
                requireAction(action).name(),
                Long.toString(requireNow(now).toEpochMilli()),
                Integer.toString(properties.maxAttempts()));
        int status = listStatus(result);
        if (status == 3) {
            throw new LoginException(
                    LoginErrorCode.TOTP_ATTEMPTS_EXHAUSTED,
                    "TOTP security verification attempts were exhausted.");
        }
        if (status != 0) {
            throw stepUpRequired();
        }
    }

    @Override
    public void consumeProof(
            String rawProofToken,
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            Instant now) {
        String key = keyFactory.totpStepUpProofKey(
                proofToken(rawProofToken));
        Long status = execute(
                CONSUME,
                List.of(key),
                Long.toString(userId),
                device(deviceInstallationId).value(),
                requireAction(action).name(),
                Long.toString(now.toEpochMilli()));
        if (status != 0L) {
            throw stepUpRequired();
        }
    }

    private void create(
            String key,
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            Instant createdAt,
            Duration ttl) {
        requireState(userId, createdAt, ttl);
        Long status = execute(
                CREATE,
                List.of(key),
                Long.toString(userId),
                device(deviceInstallationId).value(),
                requireAction(action).name(),
                Long.toString(createdAt.toEpochMilli()),
                Long.toString(createdAt.plus(ttl).toEpochMilli()),
                Long.toString(ttl.toMillis()));
        if (status != 0L) {
            throw unavailable(null);
        }
    }

    private void require(
            String key,
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            Instant now) {
        Long status = execute(
                REQUIRE,
                List.of(key),
                Long.toString(userId),
                device(deviceInstallationId).value(),
                requireAction(action).name(),
                Long.toString(requireNow(now).toEpochMilli()));
        if (status != 0L) {
            throw stepUpRequired();
        }
    }

    private <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
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

    private static void requireState(long userId, Instant createdAt, Duration ttl) {
        if (userId <= 0 || createdAt == null || ttl == null
                || ttl.isZero() || ttl.isNegative()) {
            throw unavailable(null);
        }
    }

    private static Instant requireNow(Instant now) {
        if (now == null) {
            throw unavailable(null);
        }
        return now;
    }

    private static TotpManagementAction requireAction(TotpManagementAction action) {
        return Objects.requireNonNull(action, "action must not be null");
    }

    private HmacIdentifier flowToken(String rawFlowToken) {
        try {
            return protector.totpStepUpFlowToken(rawFlowToken);
        } catch (IllegalArgumentException exception) {
            throw invalid(exception);
        }
    }

    private HmacIdentifier proofToken(String rawProofToken) {
        try {
            return protector.totpStepUpProofToken(rawProofToken);
        } catch (IllegalArgumentException exception) {
            throw invalid(exception);
        }
    }

    private HmacIdentifier device(String deviceInstallationId) {
        try {
            return protector.device(deviceInstallationId);
        } catch (IllegalArgumentException exception) {
            throw invalid(exception);
        }
    }

    private static int listStatus(List<?> result) {
        if (result == null || result.isEmpty()) {
            throw unavailable(null);
        }
        Object value = result.getFirst();
        if (value instanceof byte[] bytes) {
            value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        try {
            return Math.toIntExact(Long.parseLong(value.toString()));
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private static LoginException stepUpRequired() {
        return new LoginException(
                LoginErrorCode.TOTP_STEP_UP_REQUIRED,
                "TOTP security verification is missing or expired.");
    }

    private static LoginException unavailable(Throwable cause) {
        return new LoginException(
                LoginErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                "TOTP security verification storage is unavailable.",
                cause);
    }

    private static LoginException invalid(Throwable cause) {
        return new LoginException(
                LoginErrorCode.INVALID_INPUT,
                "TOTP security verification request is invalid.",
                cause);
    }

    private static RedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/auth-totp/" + name));
        script.setResultType(Long.class);
        return script;
    }

    private static RedisScript<List> listScript(String name) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/auth-totp/" + name));
        script.setResultType(List.class);
        return script;
    }
}
