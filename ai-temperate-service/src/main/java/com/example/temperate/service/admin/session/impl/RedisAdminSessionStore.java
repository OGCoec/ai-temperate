package com.example.temperate.service.admin.session.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.security.AdminSecretProtector;
import com.example.temperate.service.admin.session.AdminSession;
import com.example.temperate.service.admin.session.AdminSessionStore;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 使用单个 Redis 7.4 Hash 和字段级 HEXPIRE 保存管理员设备会话。
 *
 * <p>创建与续期均由 Lua 原子执行；续期脚本只更新已经存在且设备摘要匹配的字段，因此 logout-all 后迟到请求
 * 无法重新创建会话。HSET 后立即在同一脚本内 HEXPIRE，避免覆盖字段清除既有字段 TTL。</p>
 */
@Component
public final class RedisAdminSessionStore implements AdminSessionStore {

    private static final String CREATE_SCRIPT = """
            local count = redis.call('HLEN', KEYS[1])
            if count >= tonumber(ARGV[4]) then
              return -1
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            local expiry = redis.call('HEXPIRE', KEYS[1], ARGV[3], 'FIELDS', 1, ARGV[1])
            if not expiry or expiry[1] ~= 1 then
              redis.call('HDEL', KEYS[1], ARGV[1])
              return -2
            end
            return 1
            """;
    private static final String TOUCH_SCRIPT = """
            local value = redis.call('HGET', KEYS[1], ARGV[1])
            if not value then
              return nil
            end
            local decoded, session = pcall(cjson.decode, value)
            if not decoded or session.schemaVersion ~= 1 or session.deviceDigest ~= ARGV[2] then
              redis.call('HDEL', KEYS[1], ARGV[1])
              return nil
            end
            session.lastSeenAt = ARGV[3]
            local updated = cjson.encode(session)
            redis.call('HSET', KEYS[1], ARGV[1], updated)
            local expiry = redis.call('HEXPIRE', KEYS[1], ARGV[4], 'FIELDS', 1, ARGV[1])
            if not expiry or expiry[1] ~= 1 then
              redis.call('HDEL', KEYS[1], ARGV[1])
              return nil
            end
            return updated
            """;
    private static final String TOUCH_WITH_PREAUTH_SCRIPT = """
            local value = redis.call('HGET', KEYS[1], ARGV[1])
            if not value then
              return nil
            end
            local decoded, session = pcall(cjson.decode, value)
            if not decoded or session.schemaVersion ~= 1 or session.deviceDigest ~= ARGV[2] then
              redis.call('HDEL', KEYS[1], ARGV[1])
              return nil
            end
            local preauth = redis.call('HMGET', KEYS[2],
              'schemaVersion', 'scope', 'authState', 'sessionType',
              'sessionRefDigest', 'deviceDigest')
            local alreadyBound = preauth[3] == 'AUTHENTICATED'
                and preauth[4] == ARGV[6]
                and preauth[5] == ARGV[7]
            local anonymousRecovery = ARGV[10] == '1'
                and preauth[3] == 'ANONYMOUS'
                and preauth[4] == 'NONE'
                and (not preauth[5] or preauth[5] == '')
            if preauth[1] ~= '4'
                or preauth[2] ~= ARGV[5]
                or preauth[6] ~= ARGV[8]
                or (not alreadyBound and not anonymousRecovery) then
              return '__PREAUTH_MISMATCH__'
            end
            session.lastSeenAt = ARGV[3]
            local updated = cjson.encode(session)
            redis.call('HSET', KEYS[1], ARGV[1], updated)
            local expiry = redis.call('HEXPIRE', KEYS[1], ARGV[4], 'FIELDS', 1, ARGV[1])
            if not expiry or expiry[1] ~= 1 then
              redis.call('HDEL', KEYS[1], ARGV[1])
              return nil
            end
            if anonymousRecovery then
              redis.call('HSET', KEYS[2],
                'authState', 'AUTHENTICATED',
                'sessionType', ARGV[6],
                'sessionRefDigest', ARGV[7])
            end
            redis.call('HSET', KEYS[2], 'lastSeenAt', ARGV[3])
            redis.call('PEXPIRE', KEYS[2], ARGV[9])
            return updated
            """;

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final AdminSecretProtector protector;
    private final ObjectMapper objectMapper;

    public RedisAdminSessionStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            AdminSecretProtector protector,
            ObjectMapper objectMapper) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.protector = Objects.requireNonNull(protector);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public AdminSession create(
            String rawToken,
            String deviceInstallationId,
            Instant now,
            Duration ttl,
            int maximumSessions) {
        HmacIdentifier tokenId = protector.sessionToken(rawToken);
        HmacIdentifier deviceId = protector.sessionDevice(deviceInstallationId);
        AdminSession session = new AdminSession(
                AdminSession.CURRENT_SCHEMA_VERSION,
                deviceId.value(),
                now,
                now);
        Long result;
        try {
            result = redisTemplate.execute(
                    script(CREATE_SCRIPT, Long.class),
                    List.of(keyFactory.adminSessionTokensKey()),
                    tokenId.value(),
                    objectMapper.writeValueAsString(session),
                    Long.toString(requireSeconds(ttl)),
                    Integer.toString(maximumSessions));
        } catch (JsonProcessingException exception) {
            throw unavailable(exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (result != null && result == -1L) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_SESSION_LIMIT_REACHED,
                    "Administrator session limit has been reached.");
        }
        if (result == null || result != 1L) {
            throw unavailable(null);
        }
        return session;
    }

    @Override
    public AdminSession touch(
            String rawToken,
            String deviceInstallationId,
            Instant now,
            Duration ttl) {
        HmacIdentifier tokenId = protector.sessionToken(rawToken);
        HmacIdentifier deviceId = protector.sessionDevice(deviceInstallationId);
        String value;
        try {
            value = redisTemplate.execute(
                    script(TOUCH_SCRIPT, String.class),
                    List.of(keyFactory.adminSessionTokensKey()),
                    tokenId.value(),
                    deviceId.value(),
                    now.toString(),
                    Long.toString(requireSeconds(ttl)));
            if (value == null) {
                throw invalidSession();
            }
            AdminSession session = objectMapper.readValue(value, AdminSession.class);
            if (session.schemaVersion() != AdminSession.CURRENT_SCHEMA_VERSION
                    || !deviceId.value().equals(session.deviceDigest())) {
                throw invalidSession();
            }
            return session;
        } catch (AdminException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw invalidSession();
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public AdminSession touchWithPreAuth(
            String rawToken,
            String deviceInstallationId,
            Instant now,
            Duration ttl,
            PreAuthSessionBinding preAuthBinding) {
        PreAuthSessionBinding binding = requireAdminPreAuthBinding(preAuthBinding);
        HmacIdentifier tokenId = protector.sessionToken(rawToken);
        HmacIdentifier deviceId = protector.sessionDevice(deviceInstallationId);
        String value;
        try {
            // 管理员会话字段 TTL 与对应 PreAuth Key TTL 在同一个脚本中成功或一起保持不变。
            value = redisTemplate.execute(
                    script(TOUCH_WITH_PREAUTH_SCRIPT, String.class),
                    List.of(
                            keyFactory.adminSessionTokensKey(),
                            keyFactory.adminPreAuthKey(binding.tokenDigest())),
                    tokenId.value(),
                    deviceId.value(),
                    now.toString(),
                    Long.toString(requireSeconds(ttl)),
                    binding.scope().name(),
                    binding.sessionType().name(),
                    binding.sessionRefDigest().value(),
                    binding.deviceDigest().value(),
                    Long.toString(binding.ttl().toMillis()),
                    binding.promoteAnonymous() ? "1" : "0");
            if ("__PREAUTH_MISMATCH__".equals(value)) {
                throw new AdminException(
                        AdminErrorCode.ADMIN_PREAUTH_REQUIRED,
                        "Administrator PreAuth is missing or no longer bound to this session.",
                        null,
                        false,
                        false);
            }
            if (value == null) {
                throw invalidSession();
            }
            AdminSession session = objectMapper.readValue(value, AdminSession.class);
            if (session.schemaVersion() != AdminSession.CURRENT_SCHEMA_VERSION
                    || !deviceId.value().equals(session.deviceDigest())) {
                throw invalidSession();
            }
            return session;
        } catch (AdminException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw invalidSession();
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void delete(String rawToken) {
        try {
            redisTemplate.opsForHash().delete(
                    keyFactory.adminSessionTokensKey(),
                    protector.sessionToken(rawToken).value());
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void deleteAll() {
        byte[] key = keyFactory.adminSessionTokensKey().getBytes(StandardCharsets.UTF_8);
        try {
            // 单个有界 Hash 使用 UNLINK 立即完成逻辑删除，后台异步回收内存且不逐字段产生网络 I/O。
            redisTemplate.execute(
                    (RedisCallback<Long>) connection -> connection.unlink(key));
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private static long requireSeconds(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.toSeconds() <= 0) {
            throw new IllegalArgumentException("Admin session TTL must be positive.");
        }
        return ttl.toSeconds();
    }

    private static PreAuthSessionBinding requireAdminPreAuthBinding(
            PreAuthSessionBinding binding) {
        if (binding == null
                || binding.scope() != RiskScope.ADMIN
                || binding.sessionType() != RiskSessionType.ADMIN_SESSION) {
            throw new IllegalArgumentException("Administrator PreAuth binding is invalid.");
        }
        return binding;
    }

    private static AdminException invalidSession() {
        return new AdminException(
                AdminErrorCode.ADMIN_SESSION_INVALID,
                "Administrator session is invalid.",
                null,
                false,
                true);
    }

    private static AdminException unavailable(Throwable cause) {
        return new AdminException(
                AdminErrorCode.ADMIN_INFRASTRUCTURE_UNAVAILABLE,
                "Administrator session storage is unavailable.",
                cause);
    }

    private static <T> DefaultRedisScript<T> script(String source, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(type);
        return script;
    }
}
