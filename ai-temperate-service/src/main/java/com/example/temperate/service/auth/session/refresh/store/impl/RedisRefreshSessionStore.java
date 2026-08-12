package com.example.temperate.service.auth.session.refresh.store.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.session.refresh.dto.command.NewRefreshSession;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionRevocation;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionSnapshot;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionValidation;
import com.example.temperate.service.auth.session.refresh.store.RefreshSessionStore;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 刷新会话的 Redis 持久化实现。
 *
 * <p>单个刷新会话 Hash、按用户维护的会话索引及其过期时间必须始终一致。跨键创建、校验续期、
 * CSRF 轮换和单会话撤销交给 Lua 脚本执行；用户级全量撤销使用受边界保护的 Pipeline 批量
 * UNLINK，以减少网络往返，同时明确接受其非原子和最终依赖重试与 TTL 的一致性边界。</p>
 *
 * <p>Lua 返回值和 Redis 字段都属于存储边界数据；转换为领域快照前会验证字段数量、类型、公共 ID
 * 与 HMAC 标识的规范形式，损坏数据不会直接进入认证流程。</p>
 */
@Component
public final class RedisRefreshSessionStore implements RefreshSessionStore {

    static final Duration REQUIRED_REFRESH_TTL = Duration.ofHours(3);
    private static final Pattern HMAC_ID = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CREATE_SCRIPT = listScript(
            "create_refresh_session.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> VALIDATE_ACCESS_SCRIPT = listScript(
            "validate_access_session.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> VALIDATE_BINDING_SCRIPT = listScript(
            "validate_session_binding.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> VALIDATE_ACCESS_WITH_PREAUTH_SCRIPT = listScript(
            "validate_access_session_with_preauth.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> VALIDATE_SCRIPT = listScript(
            "validate_refresh_session.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> BOOTSTRAP_SCRIPT = listScript(
            "update_refresh_session_csrf.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> VALIDATE_WITH_PREAUTH_SCRIPT = listScript(
            "validate_refresh_session_with_preauth.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> BOOTSTRAP_WITH_PREAUTH_SCRIPT = listScript(
            "update_refresh_session_csrf_with_preauth.lua");
    private static final RedisScript<Long> REVOKE_SCRIPT = longScript(
            "revoke_refresh_session.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory redisKeyFactory;
    private final PublicIdCodec publicIdCodec;
    private final Duration refreshTtl;
    private final int maxSessionsPerUser;
    private final int absoluteRevokeBound;

    public RedisRefreshSessionStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory redisKeyFactory,
            PublicIdCodec publicIdCodec,
            @Value("${app.auth-session.refresh-token-ttl:3h}") Duration refreshTtl,
            @Value("${app.auth-session.max-sessions-per-user:10}") int maxSessionsPerUser,
            @Value("${app.auth-session.absolute-revoke-bound:100}") int absoluteRevokeBound) {
        this.redisTemplate = Objects.requireNonNull(
                redisTemplate, "redisTemplate must not be null");
        this.redisKeyFactory = Objects.requireNonNull(
                redisKeyFactory, "redisKeyFactory must not be null");
        this.publicIdCodec = Objects.requireNonNull(
                publicIdCodec, "publicIdCodec must not be null");
        if (!REQUIRED_REFRESH_TTL.equals(refreshTtl)) {
            throw new IllegalArgumentException("Refresh token TTL must be exactly three hours.");
        }
        if (maxSessionsPerUser < 1 || maxSessionsPerUser > 100) {
            throw new IllegalArgumentException(
                    "Maximum sessions per user must be between 1 and 100.");
        }
        if (absoluteRevokeBound < maxSessionsPerUser || absoluteRevokeBound > 1000) {
            throw new IllegalArgumentException(
                    "Absolute session revoke bound must cover the session limit and not exceed 1000.");
        }
        this.refreshTtl = refreshTtl;
        this.maxSessionsPerUser = maxSessionsPerUser;
        this.absoluteRevokeBound = absoluteRevokeBound;
    }

    @Override
    public RefreshSessionSnapshot create(NewRefreshSession session) {
        NewRefreshSession valid = requireNewSession(session);
        // 会话 Hash 与用户索引必须由同一脚本创建，才能保证后续续期和全量撤销都有完整索引可用。
        List<?> result = executeList(
                CREATE_SCRIPT,
                List.of(
                        redisKeyFactory.sessionRefreshTokenKey(valid.refreshTokenHash()),
                        redisKeyFactory.sessionUserIndexKey(valid.userId())),
                Long.toString(valid.userId()),
                valid.publicId(),
                valid.refreshTokenHash().value(),
                valid.deviceHash().value(),
                valid.csrfHash().value(),
                valid.email(),
                valid.phone(),
                Integer.toString(maxSessionsPerUser),
                Long.toString(refreshTtl.toMillis()));
        return switch (status(result)) {
            case 0 -> snapshot(result);
            case 1 -> throw new IllegalStateException("Refresh session already exists.");
            case 2 -> throw new IllegalStateException("Refresh session limit was reached.");
            default -> throw unavailable("Unexpected refresh session create result.");
        };
    }

    @Override
    public RefreshSessionValidation validateForAccess(
            HmacIdentifier refreshTokenHash,
            HmacIdentifier deviceHash,
            HmacIdentifier csrfHash) {
        HmacIdentifier validRefresh = requireIdentifier(
                "refresh token hash", refreshTokenHash);
        // 受保护请求只读取并校验会话与索引 TTL，禁止把普通 API 访问变成滑动续期。
        return validation(executeList(
                VALIDATE_ACCESS_SCRIPT,
                List.of(redisKeyFactory.sessionRefreshTokenKey(validRefresh)),
                requireIdentifier("device hash", deviceHash).value(),
                requireIdentifier("CSRF hash", csrfHash).value(),
                redisKeyFactory.sessionUserIndexKeyPrefix(),
                validRefresh.value()));
    }

    @Override
    public RefreshSessionValidation validateBinding(
            HmacIdentifier refreshTokenHash,
            HmacIdentifier deviceHash) {
        HmacIdentifier validRefresh = requireIdentifier(
                "refresh token hash", refreshTokenHash);
        // 该 Lua 是握手专用只读路径，不接受 CSRF，也绝不延长会话 TTL。
        return validation(executeList(
                VALIDATE_BINDING_SCRIPT,
                List.of(redisKeyFactory.sessionRefreshTokenKey(validRefresh)),
                requireIdentifier("device hash", deviceHash).value(),
                redisKeyFactory.sessionUserIndexKeyPrefix(),
                validRefresh.value()));
    }

    @Override
    public RefreshSessionValidation validateForAccessWithPreAuth(
            HmacIdentifier refreshTokenHash,
            HmacIdentifier deviceHash,
            HmacIdentifier csrfHash,
            PreAuthSessionBinding preAuthBinding) {
        HmacIdentifier validRefresh = requireIdentifier(
                "refresh token hash", refreshTokenHash);
        PreAuthSessionBinding binding = requireUserPreAuthBinding(preAuthBinding);
        // 网络风控开启时，RT、用户索引和已认证 PreAuth 必须在同一次只读 Lua 中保持绑定。
        return validation(executeList(
                VALIDATE_ACCESS_WITH_PREAUTH_SCRIPT,
                List.of(
                        redisKeyFactory.sessionRefreshTokenKey(validRefresh),
                        redisKeyFactory.userPreAuthKey(binding.tokenDigest())),
                requireIdentifier("device hash", deviceHash).value(),
                requireIdentifier("CSRF hash", csrfHash).value(),
                redisKeyFactory.sessionUserIndexKeyPrefix(),
                validRefresh.value(),
                binding.scope().name(),
                binding.deviceDigest().value(),
                binding.sessionType().name(),
                binding.sessionRefDigest().value(),
                Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION)));
    }

    @Override
    public RefreshSessionValidation validateAndRenew(
            HmacIdentifier refreshTokenHash,
            HmacIdentifier deviceHash,
            HmacIdentifier csrfHash) {
        HmacIdentifier validRefresh = requireIdentifier(
                "refresh token hash", refreshTokenHash);
        // 仅在设备、CSRF 和用户索引全部匹配后才续期两个键，禁止拆成查询和 PEXPIRE 两次调用。
        return validation(executeList(
                VALIDATE_SCRIPT,
                List.of(redisKeyFactory.sessionRefreshTokenKey(validRefresh)),
                requireIdentifier("device hash", deviceHash).value(),
                requireIdentifier("CSRF hash", csrfHash).value(),
                Long.toString(refreshTtl.toMillis()),
                redisKeyFactory.sessionUserIndexKeyPrefix(),
                validRefresh.value()));
    }

    @Override
    public RefreshSessionValidation bootstrapAndRenew(
            HmacIdentifier refreshTokenHash,
            HmacIdentifier deviceHash,
            HmacIdentifier newCsrfHash) {
        HmacIdentifier validRefresh = requireIdentifier(
                "refresh token hash", refreshTokenHash);
        // 新 CSRF 摘要替换、会话续期与索引续期必须原子完成，成功返回即代表新绑定已经生效。
        return validation(executeList(
                BOOTSTRAP_SCRIPT,
                List.of(redisKeyFactory.sessionRefreshTokenKey(validRefresh)),
                requireIdentifier("device hash", deviceHash).value(),
                requireIdentifier("new CSRF hash", newCsrfHash).value(),
                Long.toString(refreshTtl.toMillis()),
                redisKeyFactory.sessionUserIndexKeyPrefix(),
                validRefresh.value()));
    }

    @Override
    public RefreshSessionValidation validateAndRenewWithPreAuth(
            HmacIdentifier refreshTokenHash,
            HmacIdentifier deviceHash,
            HmacIdentifier csrfHash,
            PreAuthSessionBinding preAuthBinding) {
        HmacIdentifier validRefresh =
                requireIdentifier("refresh token hash", refreshTokenHash);
        PreAuthSessionBinding binding = requireUserPreAuthBinding(preAuthBinding);
        // Refresh Session、用户索引和 PreAuth 只有在三者绑定都有效时才同时续期，防止半续期状态。
        return validation(executeList(
                VALIDATE_WITH_PREAUTH_SCRIPT,
                List.of(
                        redisKeyFactory.sessionRefreshTokenKey(validRefresh),
                        redisKeyFactory.userPreAuthKey(binding.tokenDigest())),
                requireIdentifier("device hash", deviceHash).value(),
                requireIdentifier("CSRF hash", csrfHash).value(),
                Long.toString(refreshTtl.toMillis()),
                redisKeyFactory.sessionUserIndexKeyPrefix(),
                validRefresh.value(),
                binding.scope().name(),
                binding.deviceDigest().value(),
                binding.sessionType().name(),
                binding.sessionRefDigest().value(),
                Long.toString(binding.ttl().toMillis()),
                binding.promoteAnonymous() ? "1" : "0",
                Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION)));
    }

    @Override
    public RefreshSessionValidation bootstrapAndRenewWithPreAuth(
            HmacIdentifier refreshTokenHash,
            HmacIdentifier deviceHash,
            HmacIdentifier newCsrfHash,
            PreAuthSessionBinding preAuthBinding) {
        HmacIdentifier validRefresh =
                requireIdentifier("refresh token hash", refreshTokenHash);
        PreAuthSessionBinding binding = requireUserPreAuthBinding(preAuthBinding);
        // 新 CSRF 绑定、Refresh Session 续期和 PreAuth 续期必须作为一个不可分割的 Redis 状态转换。
        return validation(executeList(
                BOOTSTRAP_WITH_PREAUTH_SCRIPT,
                List.of(
                        redisKeyFactory.sessionRefreshTokenKey(validRefresh),
                        redisKeyFactory.userPreAuthKey(binding.tokenDigest())),
                requireIdentifier("device hash", deviceHash).value(),
                requireIdentifier("new CSRF hash", newCsrfHash).value(),
                Long.toString(refreshTtl.toMillis()),
                redisKeyFactory.sessionUserIndexKeyPrefix(),
                validRefresh.value(),
                binding.scope().name(),
                binding.deviceDigest().value(),
                binding.sessionType().name(),
                binding.sessionRefDigest().value(),
                Long.toString(binding.ttl().toMillis()),
                binding.promoteAnonymous() ? "1" : "0",
                Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION)));
    }

    @Override
    public RefreshSessionRevocation revoke(
            HmacIdentifier refreshTokenHash,
            HmacIdentifier deviceHash,
            HmacIdentifier csrfHash) {
        HmacIdentifier validRefresh = requireIdentifier(
                "refresh token hash", refreshTokenHash);
        // 先校验设备和 CSRF，再同步删除会话与索引字段，防止未授权请求破坏其他设备的会话。
        long result = executeLong(
                REVOKE_SCRIPT,
                List.of(redisKeyFactory.sessionRefreshTokenKey(validRefresh)),
                validRefresh.value(),
                requireIdentifier("device hash", deviceHash).value(),
                requireIdentifier("CSRF hash", csrfHash).value(),
                redisKeyFactory.sessionUserIndexKeyPrefix(),
                Integer.toString(absoluteRevokeBound));
        return new RefreshSessionRevocation(switch (Math.toIntExact(result)) {
            case 1 -> RefreshSessionRevocation.Status.REVOKED;
            case 0 -> RefreshSessionRevocation.Status.MISSING_OR_EXPIRED;
            case -2 -> RefreshSessionRevocation.Status.DEVICE_MISMATCH;
            case -3 -> RefreshSessionRevocation.Status.CSRF_MISMATCH;
            case -4 -> RefreshSessionRevocation.Status.INDEX_BOUND_EXCEEDED;
            default -> throw unavailable("Unexpected refresh session revoke result.");
        });
    }

    @Override
    public int revokeAllForUser(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("Session user ID must be positive.");
        }
        SessionIndexBatch batch = readSessionIndex(userId);
        unlinkBatch(batch.keysToDelete());
        return batch.refreshTokenKeys().size();
    }

    /**
     * 在一个读取 Pipeline 中获取 v4 当前值和 v3 迁移期字段。
     *
     * <p>读取与删除被刻意拆成两个 Pipeline 阶段：Pipeline 能减少网络往返，但不具备事务原子性，
     * 因此删除阶段必须可重复执行，密码重置服务才可以在异常后安全重试。</p>
     */
    private SessionIndexBatch readSessionIndex(long userId) {
        String currentIndexKey = redisKeyFactory.sessionUserIndexKey(userId);
        String legacyIndexKey = redisKeyFactory.legacySessionUserIndexKey(userId);
        final List<Object> responses;
        try {
            responses = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                connection.hLen(rawKey(currentIndexKey));
                connection.hVals(rawKey(currentIndexKey));
                connection.hLen(rawKey(legacyIndexKey));
                connection.hKeys(rawKey(legacyIndexKey));
                return null;
            });
        } catch (RuntimeException exception) {
            throw unavailable("Redis refresh session index pipeline failed.", exception);
        }
        if (responses == null || responses.size() != 4) {
            throw unavailable("Redis refresh session index pipeline returned an invalid result.");
        }

        long currentCount = number(responses.get(0));
        long legacyCount = number(responses.get(2));
        if (currentCount < 0 || legacyCount < 0
                || currentCount > absoluteRevokeBound
                || legacyCount > absoluteRevokeBound
                || currentCount + legacyCount > absoluteRevokeBound) {
            throw unavailable("User session index exceeded its configured bound.");
        }

        List<String> currentKeys = textValues(responses.get(1));
        List<String> legacyTokenHashes = textValues(responses.get(3));
        if (currentKeys.size() > absoluteRevokeBound
                || legacyTokenHashes.size() > absoluteRevokeBound) {
            throw unavailable("User session index returned too many fields.");
        }

        String currentPrefix = redisKeyFactory.sessionRefreshTokenKeyPrefix();
        if (currentKeys.stream().anyMatch(key -> !isRefreshKey(key, currentPrefix))) {
            throw unavailable("Current refresh session index contains a malformed key.");
        }

        String legacyPrefix = redisKeyFactory.legacySessionRefreshTokenKeyPrefix();
        if (legacyTokenHashes.stream().anyMatch(hash -> !HMAC_ID.matcher(hash).matches())) {
            throw unavailable("Legacy refresh session index contains a malformed hash.");
        }
        List<String> legacyKeys = legacyTokenHashes.stream()
                .map(legacyPrefix::concat)
                .toList();

        List<String> refreshTokenKeys = Stream.concat(currentKeys.stream(), legacyKeys.stream())
                .distinct()
                .toList();
        if (refreshTokenKeys.size() > absoluteRevokeBound) {
            throw unavailable("User session index exceeded its configured bound.");
        }

        List<String> keysToDelete = Stream.concat(
                        refreshTokenKeys.stream(),
                        Stream.of(currentIndexKey, legacyIndexKey))
                .distinct()
                .toList();
        return new SessionIndexBatch(refreshTokenKeys, keysToDelete);
    }

    /**
     * 将所有 RT Key 和两个版本的用户索引放入一条多 Key UNLINK 命令。
     *
     * <p>这里使用 Pipeline API 统一发送批量命令，而不是在 Java 或 Lua 中逐个发起网络 I/O；
     * UNLINK 的异步内存回收不会改变 Key 的逻辑删除语义。</p>
     */
    private void unlinkBatch(List<String> keys) {
        final List<Object> responses;
        try {
            responses = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                connection.unlink(keys.stream()
                        .map(this::rawKey)
                        .toArray(byte[][]::new));
                return null;
            });
        } catch (RuntimeException exception) {
            throw unavailable("Redis refresh session unlink pipeline failed.", exception);
        }
        if (responses == null || responses.size() != 1 || number(responses.getFirst()) < 0) {
            throw unavailable("Redis refresh session unlink pipeline returned an invalid result.");
        }
    }

    private byte[] rawKey(String key) {
        byte[] serialized = redisTemplate.getStringSerializer().serialize(key);
        if (serialized == null) {
            throw unavailable("Redis session key serialization failed.");
        }
        return serialized;
    }

    private static boolean isRefreshKey(String key, String prefix) {
        return key.startsWith(prefix)
                && HMAC_ID.matcher(key.substring(prefix.length())).matches();
    }

    private static List<String> textValues(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            throw unavailable("Redis refresh session index values are malformed.");
        }
        return StreamSupport.stream(iterable.spliterator(), false)
                .map(RedisRefreshSessionStore::text)
                .toList();
    }

    private record SessionIndexBatch(
            List<String> refreshTokenKeys,
            List<String> keysToDelete) {
    }

    private RefreshSessionValidation validation(List<?> result) {
        return switch (status(result)) {
            case 0 -> new RefreshSessionValidation(
                    RefreshSessionValidation.Status.VALID, snapshot(result));
            case 1 -> new RefreshSessionValidation(
                    RefreshSessionValidation.Status.MISSING_OR_EXPIRED, null);
            case 2 -> new RefreshSessionValidation(
                    RefreshSessionValidation.Status.DEVICE_MISMATCH, null);
            case 3 -> new RefreshSessionValidation(
                    RefreshSessionValidation.Status.CSRF_MISMATCH, null);
            case 4 -> new RefreshSessionValidation(
                    RefreshSessionValidation.Status.INDEX_MISSING, null);
            case 5 -> new RefreshSessionValidation(
                    RefreshSessionValidation.Status.PREAUTH_MISMATCH, null);
            case 6 -> new RefreshSessionValidation(
                    RefreshSessionValidation.Status.TTL_INVARIANT_VIOLATION, null);
            default -> throw unavailable("Unexpected refresh session validation result.");
        };
    }

    private static PreAuthSessionBinding requireUserPreAuthBinding(
            PreAuthSessionBinding binding) {
        if (binding == null
                || binding.scope() != RiskScope.USER
                || binding.sessionType() != RiskSessionType.USER_REFRESH) {
            throw new IllegalArgumentException("User Refresh PreAuth binding is invalid.");
        }
        requireIdentifier("PreAuth token digest", binding.tokenDigest());
        requireIdentifier("PreAuth device digest", binding.deviceDigest());
        requireIdentifier("PreAuth session reference", binding.sessionRefDigest());
        return binding;
    }

    private NewRefreshSession requireNewSession(NewRefreshSession session) {
        if (session == null || session.userId() <= 0) {
            throw new IllegalArgumentException("Refresh session user is invalid.");
        }
        if (publicIdCodec.decode(session.publicId()) != session.userId()) {
            throw new IllegalArgumentException("Public ID does not match the internal user ID.");
        }
        requireIdentifier("refresh token hash", session.refreshTokenHash());
        requireIdentifier("device hash", session.deviceHash());
        requireIdentifier("CSRF hash", session.csrfHash());
        if (session.email() == null
                || !session.email().matches("^[^\\s@]+@[^\\s@]+$")
                || !session.email().equals(session.email().toLowerCase(Locale.ROOT))
                || session.phone() == null
                || !session.phone().matches("^\\+[1-9][0-9]{7,14}$")) {
            throw new IllegalArgumentException("Refresh session contact snapshots are invalid.");
        }
        return session;
    }

    private RefreshSessionSnapshot snapshot(List<?> result) {
        if (result.size() < 8) {
            throw unavailable("Refresh session snapshot is incomplete.");
        }
        try {
            // Redis 返回的是动态类型数组，必须逐项校验后再跨越存储边界构造认证领域对象。
            long userId = number(result.get(1));
            String publicId = text(result.get(2));
            String csrfHash = requireHmacText("CSRF hash", result.get(3));
            String email = text(result.get(4));
            String phone = text(result.get(5));
            String deviceHash = requireHmacText("device hash", result.get(6));
            Instant expiresAt = Instant.ofEpochMilli(number(result.get(7)));
            if (userId <= 0
                    || publicIdCodec.decode(publicId) != userId
                    || !email.matches("^[^\\s@]+@[^\\s@]+$")
                    || !phone.matches("^\\+[1-9][0-9]{7,14}$")) {
                throw unavailable("Refresh session snapshot is malformed.");
            }
            return new RefreshSessionSnapshot(
                    userId, publicId, csrfHash, email, phone, deviceHash, expiresAt);
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalStateException) {
                throw exception;
            }
            throw unavailable("Refresh session snapshot is malformed.", exception);
        }
    }

    private static HmacIdentifier requireIdentifier(String name, HmacIdentifier identifier) {
        if (identifier == null || !HMAC_ID.matcher(identifier.value()).matches()) {
            throw new IllegalArgumentException(name + " is required and must be canonical.");
        }
        return identifier;
    }

    private static String requireHmacText(String name, Object value) {
        String text = text(value);
        if (!HMAC_ID.matcher(text).matches()) {
            throw unavailable(name + " is malformed.");
        }
        return text;
    }

    private static int status(List<?> result) {
        if (result == null || result.isEmpty()) {
            throw unavailable("Redis refresh session script returned no status.");
        }
        return Math.toIntExact(number(result.getFirst()));
    }

    private Long executeLong(
            RedisScript<Long> script, List<String> keys, Object... arguments) {
        return execute(script, keys, arguments);
    }

    @SuppressWarnings("rawtypes")
    private List<?> executeList(
            RedisScript<List> script, List<String> keys, Object... arguments) {
        return execute(script, keys, arguments);
    }

    private <T> T execute(RedisScript<T> script, List<String> keys, Object... arguments) {
        try {
            T result = redisTemplate.execute(script, keys, arguments);
            if (result == null) {
                throw unavailable("Redis refresh session script returned no result.");
            }
            return result;
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalStateException) {
                throw exception;
            }
            // 统一映射为受控基础设施错误，避免把 Redis 键名、脚本参数或连接细节暴露给调用方。
            throw unavailable("Redis refresh session script execution failed.", exception);
        }
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(text(value));
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value == null) {
            throw unavailable("Redis refresh session field is missing.");
        }
        return value.toString();
    }

    private static IllegalStateException unavailable(String message) {
        return new IllegalStateException(message);
    }

    private static IllegalStateException unavailable(String message, Throwable cause) {
        return new IllegalStateException(message, cause);
    }

    private static RedisScript<Long> longScript(String fileName) {
        return script(fileName, Long.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RedisScript<List> listScript(String fileName) {
        return (RedisScript) script(fileName, List.class);
    }

    private static <T> RedisScript<T> script(String fileName, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/auth-session/" + fileName));
        script.setResultType(resultType);
        return script;
    }
}
