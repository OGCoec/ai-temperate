package com.example.temperate.service.auth.totp.management.store.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.totp.config.TotpProperties;
import com.example.temperate.service.auth.totp.management.TotpManagementAction;
import com.example.temperate.service.auth.totp.management.store.TotpSetupSnapshot;
import com.example.temperate.service.auth.totp.management.store.TotpSetupStore;
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
 * 使用每用户一个 Redis Hash 暂存十分钟内尚未确认的新 TOTP 密钥密文。
 *
 * <p>新申请直接覆盖同用户旧 Hash，使旧二维码立即失效；读取必须同时匹配 setupToken HMAC 和设备 HMAC，
 * 密钥明文从不进入 Redis Key、Value 或日志。</p>
 */
@Component
public final class RedisTotpSetupStore implements TotpSetupStore {

    private static final RedisScript<Long> SAVE = longScript("save_totp_setup.lua");
    private static final RedisScript<List> GET = listScript("get_totp_setup.lua");
    private static final RedisScript<List> RECORD_FAILURE = listScript(
            "record_totp_setup_failure.lua");
    private static final RedisScript<Long> DELETE = longScript("delete_totp_setup.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final AuthSessionSecretProtector protector;
    private final TotpProperties properties;

    public RedisTotpSetupStore(
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
    public void save(
            long userId,
            String rawSetupToken,
            String deviceInstallationId,
            String encryptedSecret,
            TotpManagementAction action,
            boolean expectedEnabled,
            String expectedEncryptedSecret,
            Instant createdAt,
            Duration ttl) {
        requireState(
                userId,
                encryptedSecret,
                action,
                expectedEnabled,
                expectedEncryptedSecret,
                createdAt,
                ttl);
        Long status = execute(
                SAVE,
                List.of(setupKey(userId)),
                setupToken(rawSetupToken).value(),
                device(deviceInstallationId).value(),
                encryptedSecret,
                action.name(),
                Boolean.toString(expectedEnabled),
                expectedEncryptedSecret == null ? "" : expectedEncryptedSecret,
                Long.toString(createdAt.toEpochMilli()),
                Long.toString(createdAt.plus(ttl).toEpochMilli()),
                Long.toString(ttl.toMillis()));
        if (status != 0L) {
            throw unavailable(null);
        }
    }

    @Override
    public TotpSetupSnapshot getRequired(
            long userId,
            String rawSetupToken,
            String deviceInstallationId,
            Instant now) {
        List<?> result = execute(
                GET,
                List.of(setupKey(userId)),
                setupToken(rawSetupToken).value(),
                device(deviceInstallationId).value(),
                Long.toString(requireNow(now).toEpochMilli()));
        int status = status(result);
        if (status == 1) {
            throw expired();
        }
        if (status == 2) {
            throw superseded();
        }
        if (status != 0 || result.size() < 7) {
            throw unavailable(null);
        }
        try {
            return new TotpSetupSnapshot(
                    text(result.get(1)),
                    TotpManagementAction.valueOf(text(result.get(2))),
                    booleanValue(result.get(3)),
                    nullableText(result.get(4)),
                    Math.toIntExact(number(result.get(5))),
                    Instant.ofEpochMilli(number(result.get(6))));
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public int recordFailure(
            long userId,
            String rawSetupToken,
            String deviceInstallationId,
            Instant now) {
        List<?> result = execute(
                RECORD_FAILURE,
                List.of(setupKey(userId)),
                setupToken(rawSetupToken).value(),
                device(deviceInstallationId).value(),
                Long.toString(requireNow(now).toEpochMilli()),
                Integer.toString(properties.maxAttempts()));
        int status = status(result);
        if (status == 1) {
            throw expired();
        }
        if (status == 2) {
            throw superseded();
        }
        if (status == 3) {
            throw new LoginException(
                    LoginErrorCode.TOTP_ATTEMPTS_EXHAUSTED,
                    "TOTP setup attempts were exhausted.");
        }
        if (status != 0) {
            throw unavailable(null);
        }
        return Math.toIntExact(number(result.get(1)));
    }

    @Override
    public void delete(long userId, String rawSetupToken) {
        execute(
                DELETE,
                List.of(setupKey(userId)),
                setupToken(rawSetupToken).value());
    }

    @Override
    public void deleteForUser(long userId) {
        try {
            redisTemplate.unlink(setupKey(userId));
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private String setupKey(long userId) {
        return keyFactory.totpSetupKey(protector.totpUser(userId));
    }

    private HmacIdentifier setupToken(String rawSetupToken) {
        try {
            return protector.totpSetupToken(rawSetupToken);
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

    private static void requireState(
            long userId,
            String encryptedSecret,
            TotpManagementAction action,
            boolean expectedEnabled,
            String expectedEncryptedSecret,
            Instant createdAt,
            Duration ttl) {
        boolean expectedSecretPresent = expectedEncryptedSecret != null
                && !expectedEncryptedSecret.isBlank();
        if (userId <= 0
                || encryptedSecret == null
                || encryptedSecret.isBlank()
                || encryptedSecret.length() > 512
                || action == null
                || expectedEnabled != expectedSecretPresent
                || (expectedEncryptedSecret != null
                        && expectedEncryptedSecret.length() > 512)
                || createdAt == null
                || ttl == null
                || ttl.isZero()
                || ttl.isNegative()) {
            throw unavailable(null);
        }
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
        return Long.parseLong(text(value));
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value == null) {
            throw unavailable(null);
        }
        return value.toString();
    }

    private static String nullableText(Object value) {
        String result = text(value);
        return result.isEmpty() ? null : result;
    }

    private static boolean booleanValue(Object value) {
        return switch (text(value)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw unavailable(null);
        };
    }

    private static LoginException expired() {
        return new LoginException(
                LoginErrorCode.TOTP_SETUP_EXPIRED,
                "TOTP setup is missing or expired.");
    }

    private static LoginException superseded() {
        return new LoginException(
                LoginErrorCode.TOTP_SETUP_SUPERSEDED,
                "TOTP setup was replaced by a newer request.");
    }

    private static LoginException unavailable(Throwable cause) {
        return new LoginException(
                LoginErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                "TOTP setup storage is unavailable.",
                cause);
    }

    private static LoginException invalid(Throwable cause) {
        return new LoginException(
                LoginErrorCode.INVALID_INPUT,
                "TOTP setup request is invalid.",
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
