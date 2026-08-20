package com.example.temperate.service.auth.login.code.flow.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.login.code.flow.LoginCodeFlowSnapshot;
import com.example.temperate.service.auth.login.code.flow.LoginCodeFlowStore;
import com.example.temperate.service.auth.login.code.flow.LoginCodePurpose;
import com.example.temperate.service.auth.login.code.flow.ProtectedLoginCodeAccess;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.login.strategy.LoginStrategyType;
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
 * 使用 Redis 与 Lua 脚本保存登录验证码流程状态的实现。
 *
 * <p>流程访问校验、滑动过期、验证码领取、投递完成/补偿和验证失败计数均通过单个脚本原子完成，
 * 防止并发发送或验证请求绕过冷却期、重复消费验证码或留下不一致的流程状态。</p>
 */
@Component
public final class RedisLoginCodeFlowStore implements LoginCodeFlowStore {

    private static final long IDLE_TTL_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final long ABSOLUTE_TTL_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final long CODE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final long COOLDOWN_MILLIS = Duration.ofSeconds(60).toMillis();
    private static final int MAX_SENDS = 5;
    private static final int MAX_ATTEMPTS = 5;
    private static final RedisScript<Long> CREATE = longScript("create_login_code_flow.lua");
    private static final RedisScript<List> GET = listScript("get_login_code_flow.lua");
    private static final RedisScript<List> MARK_HUMAN =
            listScript("mark_login_code_human_verified.lua");
    private static final RedisScript<Long> ISSUE = longScript("issue_login_code.lua");
    private static final RedisScript<Long> CLAIM_DELIVERY =
            longScript("claim_login_code_delivery_attempt.lua");
    private static final RedisScript<Long> RELEASE_DELIVERY =
            longScript("release_login_code_delivery_for_retry.lua");
    private static final RedisScript<Long> MARK_SUCCESS =
            longScript("mark_login_code_delivery_success.lua");
    private static final RedisScript<Long> MARK_ACCEPTED =
            longScript("mark_login_code_delivery_accepted.lua");
    private static final RedisScript<Long> MARK_UNKNOWN =
            longScript("mark_login_code_delivery_unknown.lua");
    private static final RedisScript<String> FIND_UNKNOWN =
            stringScript("find_login_code_delivery_unknown.lua");
    private static final RedisScript<Long> COMPENSATE =
            longScript("compensate_login_code_delivery.lua");
    private static final RedisScript<List> VERIFY = listScript("verify_login_code.lua");
    private static final RedisScript<Long> DELETE = longScript("delete_login_code_flow.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;

    public RedisLoginCodeFlowStore(
            StringRedisTemplate redisTemplate, RedisKeyFactory keyFactory) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
    }

    @Override
    public void create(
            ProtectedLoginCodeAccess access,
            LoginStrategyType type,
            LoginCodePurpose purpose,
            String identifier,
            long userId,
            Instant createdAt) {
        ProtectedLoginCodeAccess valid = requireAccess(access);
        if (type != LoginStrategyType.EMAIL_CODE && type != LoginStrategyType.SMS_CODE) {
            throw error(LoginErrorCode.INVALID_INPUT, "Code login strategy is invalid.");
        }
        Long status = execute(CREATE,
                List.of(keyFactory.loginFlowKey(valid.flowId()),
                        keyFactory.loginChallengeKey(valid.challengeId())),
                "3", type.name(), Objects.requireNonNull(purpose).name(),
                requireText(identifier), Long.toString(userId),
                valid.deviceHash().value(), valid.challengeId().value(),
                Long.toString(createdAt.toEpochMilli()),
                Long.toString(IDLE_TTL_MILLIS), Long.toString(ABSOLUTE_TTL_MILLIS));
        if (status != 0L) {
            throw error(LoginErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "Login flow could not be created.");
        }
    }

    @Override
    public LoginCodeFlowSnapshot getRequired(
            ProtectedLoginCodeAccess access, Instant now) {
        ProtectedLoginCodeAccess valid = requireAccess(access);
        return snapshot(execute(GET, flowKeys(valid),
                valid.deviceHash().value(), valid.challengeId().value(),
                Long.toString(now.toEpochMilli()), Long.toString(IDLE_TTL_MILLIS)));
    }

    @Override
    public LoginCodeFlowSnapshot markHumanVerified(
            ProtectedLoginCodeAccess access, Instant now) {
        ProtectedLoginCodeAccess valid = requireAccess(access);
        List<?> result = execute(MARK_HUMAN, flowKeys(valid),
                valid.deviceHash().value(), valid.challengeId().value(),
                Long.toString(now.toEpochMilli()), Long.toString(IDLE_TTL_MILLIS));
        if (status(result) == 4) {
            throw error(LoginErrorCode.TURNSTILE_REJECTED,
                    "Turnstile challenge is invalid or was already consumed.");
        }
        return snapshot(result);
    }

    @Override
    public void issueCode(
            ProtectedLoginCodeAccess access,
            HmacIdentifier digest,
            HmacIdentifier operationId,
            Instant now) {
        ProtectedLoginCodeAccess valid = requireAccess(access);
        // operationId 将投递结果与本次发码绑定，异步回调只能确认或补偿自己创建的待投递状态。
        long result = execute(ISSUE,
                List.of(keyFactory.loginFlowKey(valid.flowId()),
                        codeKey(valid)),
                valid.deviceHash().value(), valid.challengeId().value(),
                Long.toString(now.toEpochMilli()), digest.value(), operationId.value(),
                Long.toString(CODE_TTL_MILLIS), Long.toString(COOLDOWN_MILLIS),
                Integer.toString(MAX_SENDS));
        switch ((int) result) {
            case 0 -> { return; }
            case 1 -> throw error(LoginErrorCode.LOGIN_FLOW_NOT_FOUND, "Login flow not found.");
            case 2 -> throw error(LoginErrorCode.LOGIN_FLOW_EXPIRED, "Login flow expired.");
            case 3 -> throw error(LoginErrorCode.LOGIN_FLOW_FORBIDDEN, "Login flow forbidden.");
            case 4 -> throw error(LoginErrorCode.HUMAN_VERIFICATION_REQUIRED,
                    "Human verification is required.");
            case 5 -> throw error(LoginErrorCode.VERIFICATION_COOLDOWN,
                    "Verification send cooldown is active.");
            case 6 -> throw error(LoginErrorCode.VERIFICATION_SEND_LIMIT,
                    "Verification send limit was reached.");
            default -> throw unavailable();
        }
    }

    @Override
    public boolean claimDeliveryAttempt(
            ProtectedLoginCodeAccess access,
            HmacIdentifier operationId,
            String messageId,
            int attemptNo) {
        ProtectedLoginCodeAccess valid = requireAccess(access);
        return execute(
                CLAIM_DELIVERY,
                List.of(keyFactory.loginFlowKey(valid.flowId()), codeKey(valid)),
                requireIdentifier(operationId).value(),
                Integer.toString(attemptNo),
                requireText(messageId)) == 1L;
    }

    @Override
    public boolean releaseDeliveryForRetry(
            ProtectedLoginCodeAccess access,
            HmacIdentifier operationId,
            String messageId) {
        ProtectedLoginCodeAccess valid = requireAccess(access);
        return execute(
                RELEASE_DELIVERY,
                List.of(keyFactory.loginFlowKey(valid.flowId()), codeKey(valid)),
                requireIdentifier(operationId).value(),
                requireText(messageId)) == 1L;
    }

    @Override
    public boolean markDeliverySucceeded(
            ProtectedLoginCodeAccess access, HmacIdentifier operationId) {
        ProtectedLoginCodeAccess valid = requireAccess(access);
        long result = execute(MARK_SUCCESS,
                List.of(keyFactory.loginFlowKey(valid.flowId()), codeKey(valid)),
                operationId.value(), Integer.toString(MAX_SENDS));
        return result == 1L;
    }

    @Override
    public boolean markDeliveryAccepted(
            ProtectedLoginCodeAccess access,
            HmacIdentifier operationId,
            String providerMessageId,
            String providerStatus) {
        ProtectedLoginCodeAccess valid = requireAccess(access);
        long result = execute(MARK_ACCEPTED,
                List.of(keyFactory.loginFlowKey(valid.flowId()), codeKey(valid)),
                requireIdentifier(operationId).value(), Integer.toString(MAX_SENDS),
                requireProviderText(providerMessageId), requireProviderText(providerStatus));
        return result == 1L;
    }

    @Override
    public boolean markDeliveryOutcomeUnknown(
            ProtectedLoginCodeAccess access,
            HmacIdentifier operationId,
            String safeReason) {
        ProtectedLoginCodeAccess valid = requireAccess(access);
        long result = execute(MARK_UNKNOWN,
                List.of(keyFactory.loginFlowKey(valid.flowId()), codeKey(valid)),
                requireIdentifier(operationId).value(), requireProviderText(safeReason));
        return result == 1L;
    }

    @Override
    public String findDeliveryOutcomeUnknown(
            ProtectedLoginCodeAccess access, HmacIdentifier operationId) {
        ProtectedLoginCodeAccess valid = requireAccess(access);
        String reason = execute(FIND_UNKNOWN,
                List.of(keyFactory.loginFlowKey(valid.flowId()), codeKey(valid)),
                requireIdentifier(operationId).value());
        return reason == null || reason.isBlank() ? null : requireProviderText(reason);
    }

    @Override
    public boolean finalizeDeliveryFailure(
            ProtectedLoginCodeAccess access, HmacIdentifier operationId) {
        return compensateDeliveryFailure(access, operationId);
    }

    @Override
    public boolean compensateDeliveryFailure(
            ProtectedLoginCodeAccess access, HmacIdentifier operationId) {
        ProtectedLoginCodeAccess valid = requireAccess(access);
        return execute(COMPENSATE,
                List.of(keyFactory.loginFlowKey(valid.flowId()), codeKey(valid)),
                operationId.value()) == 1L;
    }

    @Override
    public LoginCodeFlowSnapshot verifyCode(
            ProtectedLoginCodeAccess access, HmacIdentifier digest, Instant now) {
        ProtectedLoginCodeAccess valid = requireAccess(access);
        // 验证码比对、尝试次数递增和会话续期必须在同一脚本中完成，避免并发请求重复通过同一验证码。
        List<?> result = execute(VERIFY,
                List.of(keyFactory.loginFlowKey(valid.flowId()), codeKey(valid)),
                valid.deviceHash().value(), valid.challengeId().value(),
                Long.toString(now.toEpochMilli()), digest.value(),
                Integer.toString(MAX_ATTEMPTS), Long.toString(IDLE_TTL_MILLIS));
        int status = status(result);
        if (status == 5) {
            throw error(LoginErrorCode.VERIFICATION_CODE_EXPIRED,
                    "Verification code is missing or expired.");
        }
        if (status == 6 || status == 7) {
            throw error(LoginErrorCode.VERIFICATION_CODE_INVALID,
                    "Verification code is invalid.");
        }
        return snapshot(result);
    }

    @Override
    public void delete(ProtectedLoginCodeAccess access) {
        ProtectedLoginCodeAccess valid = requireAccess(access);
        execute(DELETE,
                List.of(keyFactory.loginFlowKey(valid.flowId()),
                        keyFactory.loginChallengeKey(valid.challengeId()), codeKey(valid)));
    }

    private List<String> flowKeys(ProtectedLoginCodeAccess access) {
        return List.of(keyFactory.loginFlowKey(access.flowId()),
                keyFactory.loginChallengeKey(access.challengeId()));
    }

    private String codeKey(ProtectedLoginCodeAccess access) {
        return keyFactory.loginCodeKey(access.codeId());
    }

    private static LoginCodeFlowSnapshot snapshot(List<?> result) {
        int status = status(result);
        if (status != 0) {
            throw switch (status) {
                case 1 -> error(LoginErrorCode.LOGIN_FLOW_NOT_FOUND, "Login flow not found.");
                case 2 -> error(LoginErrorCode.LOGIN_FLOW_EXPIRED, "Login flow expired.");
                case 3 -> error(LoginErrorCode.LOGIN_FLOW_FORBIDDEN, "Login flow forbidden.");
                case 4 -> error(LoginErrorCode.HUMAN_VERIFICATION_REQUIRED,
                        "Human verification is required.");
                default -> unavailable();
            };
        }
        if (result.size() < 9) {
            throw unavailable();
        }
        return new LoginCodeFlowSnapshot(
                LoginStrategyType.valueOf(text(result.get(1))),
                LoginCodePurpose.valueOf(text(result.get(2))),
                text(result.get(3)),
                number(result.get(4)),
                "1".equals(text(result.get(5))),
                Instant.ofEpochMilli(number(result.get(6))),
                Instant.ofEpochMilli(number(result.get(7))),
                Instant.ofEpochMilli(number(result.get(8))));
    }

    private static ProtectedLoginCodeAccess requireAccess(ProtectedLoginCodeAccess access) {
        if (access == null || access.flowId() == null || access.challengeId() == null
                || access.deviceHash() == null || access.codeId() == null) {
            throw error(LoginErrorCode.LOGIN_FLOW_FORBIDDEN, "Login flow is invalid.");
        }
        return access;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank() || value.length() > 254) {
            throw error(LoginErrorCode.INVALID_INPUT, "Login identifier is invalid.");
        }
        return value;
    }

    private static String requireProviderText(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.matches("^[A-Za-z0-9._:-]+$")) {
            throw error(LoginErrorCode.INVALID_INPUT, "Provider delivery diagnostic is invalid.");
        }
        return value;
    }

    private static HmacIdentifier requireIdentifier(HmacIdentifier identifier) {
        if (identifier == null) {
            throw error(LoginErrorCode.LOGIN_FLOW_FORBIDDEN, "Login flow is invalid.");
        }
        return identifier;
    }

    private <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
        try {
            T result = redisTemplate.execute(script, keys, args);
            if (result == null) throw unavailable();
            return result;
        } catch (LoginException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LoginException(LoginErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "Login flow storage is unavailable.", exception);
        }
    }

    private static int status(List<?> result) {
        if (result == null || result.isEmpty()) throw unavailable();
        return Math.toIntExact(number(result.getFirst()));
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue()
                : Long.parseLong(text(value));
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value == null) throw unavailable();
        return value.toString();
    }

    private static LoginException error(LoginErrorCode code, String message) {
        return new LoginException(code, message);
    }

    private static LoginException unavailable() {
        return error(LoginErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                "Login flow storage is unavailable.");
    }

    private static RedisScript<Long> longScript(String name) {
        return script(name, Long.class);
    }

    private static RedisScript<String> stringScript(String name) {
        return script(name, String.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RedisScript<List> listScript(String name) {
        return (RedisScript) script(name, List.class);
    }

    private static <T> RedisScript<T> script(String name, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/auth-login/" + name));
        script.setResultType(type);
        return script;
    }
}
