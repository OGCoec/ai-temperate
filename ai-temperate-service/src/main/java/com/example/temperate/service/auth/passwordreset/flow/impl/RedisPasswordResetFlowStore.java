package com.example.temperate.service.auth.passwordreset.flow.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.passwordreset.PasswordResetErrorCode;
import com.example.temperate.service.auth.passwordreset.PasswordResetException;
import com.example.temperate.service.auth.passwordreset.flow.PasswordResetFlowSnapshot;
import com.example.temperate.service.auth.passwordreset.flow.PasswordResetFlowStore;
import com.example.temperate.service.auth.passwordreset.flow.ProtectedPasswordResetAccess;
import com.example.temperate.service.registration.enums.VerificationChannel;
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
 * 使用 Redis 与 Lua 实现密码重置状态机、风险控制和一次性凭据的存储实现。
 *
 * <p>验证码签发、发送频率限制、验证码验证、找回凭据创建和领取均在单个脚本内完成，防止并发重置请求
 * 重复使用验证码、绕过风险限制或同时领取同一找回凭据。</p>
 */
@Component
public final class RedisPasswordResetFlowStore implements PasswordResetFlowStore {

    private static final long IDLE_TTL = Duration.ofMinutes(10).toMillis();
    private static final long ABSOLUTE_TTL = Duration.ofMinutes(30).toMillis();
    private static final long CODE_TTL = Duration.ofMinutes(5).toMillis();
    private static final long FORGET_TTL = Duration.ofMinutes(5).toMillis();
    private static final long COOLDOWN = Duration.ofSeconds(60).toMillis();
    private static final long WINDOW = Duration.ofMinutes(5).toMillis();
    private static final long BLOCK = Duration.ofHours(2).toMillis();
    private static final RedisScript<Long> CREATE = loginLong("create_login_code_flow.lua");
    private static final RedisScript<List> GET = loginList("get_login_code_flow.lua");
    private static final RedisScript<List> MARK_HUMAN =
            loginList("mark_login_code_human_verified.lua");
    private static final RedisScript<Long> ISSUE = resetLong("issue_password_reset_code.lua");
    private static final RedisScript<Long> CLAIM_DELIVERY =
            resetLong("claim_password_reset_delivery_attempt.lua");
    private static final RedisScript<Long> RELEASE_DELIVERY =
            resetLong("release_password_reset_delivery_for_retry.lua");
    private static final RedisScript<Long> MARK_SUCCESS =
            loginLong("mark_login_code_delivery_success.lua");
    private static final RedisScript<Long> MARK_ACCEPTED =
            loginLong("mark_login_code_delivery_accepted.lua");
    private static final RedisScript<Long> MARK_UNKNOWN =
            resetLong("mark_password_reset_delivery_unknown.lua");
    private static final RedisScript<String> FIND_UNKNOWN =
            resetString("find_password_reset_delivery_unknown.lua");
    private static final RedisScript<Long> COMPENSATE =
            resetLong("compensate_password_reset_delivery.lua");
    private static final RedisScript<List> VERIFY =
            resetList("verify_password_reset_code.lua");
    private static final RedisScript<List> CLAIM = resetList("claim_forget_token.lua");
    private static final RedisScript<Long> CONSUME = resetLong("consume_forget_token.lua");
    private static final RedisScript<Long> RELEASE = resetLong("release_forget_token.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;

    public RedisPasswordResetFlowStore(
            StringRedisTemplate redisTemplate, RedisKeyFactory keyFactory) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
    }

    @Override
    public boolean isBlocked(HmacIdentifier deviceHash, HmacIdentifier globalDeviceHash) {
        if (deviceHash == null || globalDeviceHash == null) {
            throw error(PasswordResetErrorCode.RESET_FLOW_FORBIDDEN, "找回密码流程无效。");
        }
        try {
            Boolean blocked = redisTemplate.hasKey(keyFactory.passwordResetBlockKey(deviceHash));
            Boolean globalBlocked =
                    redisTemplate.hasKey(keyFactory.globalDeviceBlockKey(globalDeviceHash));
            if (blocked == null || globalBlocked == null) {
                throw unavailable();
            }
            return blocked || globalBlocked;
        } catch (PasswordResetException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PasswordResetException(
                    PasswordResetErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "找回密码服务暂时不可用。", exception);
        }
    }

    @Override
    public void create(
            ProtectedPasswordResetAccess access,
            VerificationChannel channel,
            String identifier,
            long userId,
            Instant createdAt) {
        ProtectedPasswordResetAccess valid = requireAccess(access);
        Long result = execute(CREATE,
                List.of(keyFactory.passwordResetFlowKey(valid.flowId()),
                        keyFactory.passwordResetChallengeKey(valid.challengeId())),
                "2", channel.name(), identifier, Long.toString(userId),
                valid.deviceHash().value(), valid.challengeId().value(),
                Long.toString(createdAt.toEpochMilli()),
                Long.toString(IDLE_TTL), Long.toString(ABSOLUTE_TTL));
        if (result != 0L) throw unavailable();
    }

    @Override
    public PasswordResetFlowSnapshot getRequired(
            ProtectedPasswordResetAccess access, Instant now) {
        ProtectedPasswordResetAccess valid = requireAccess(access);
        return snapshot(execute(GET, flowKeys(valid),
                valid.deviceHash().value(), valid.challengeId().value(),
                Long.toString(now.toEpochMilli()), Long.toString(IDLE_TTL)));
    }

    @Override
    public PasswordResetFlowSnapshot markHumanVerified(
            ProtectedPasswordResetAccess access, Instant now) {
        ProtectedPasswordResetAccess valid = requireAccess(access);
        List<?> result = execute(MARK_HUMAN, flowKeys(valid),
                valid.deviceHash().value(), valid.challengeId().value(),
                Long.toString(now.toEpochMilli()), Long.toString(IDLE_TTL));
        if (status(result) == 4) {
            throw error(PasswordResetErrorCode.TURNSTILE_REJECTED,
                    "人机验证已失效，请重新开始。");
        }
        return snapshot(result);
    }

    @Override
    public void issueCode(
            ProtectedPasswordResetAccess access,
            HmacIdentifier digest,
            HmacIdentifier operationId,
            Instant now) {
        ProtectedPasswordResetAccess valid = requireAccess(access);
        // 发码脚本同时检查设备和目标维度风险键，并以 operationId 保留可精确补偿的待投递状态。
        long result = execute(ISSUE,
                List.of(
                        keyFactory.passwordResetFlowKey(valid.flowId()),
                        keyFactory.passwordResetCodeKey(valid.codeId()),
                        keyFactory.passwordResetSendRiskKey(valid.deviceHash()),
                        keyFactory.passwordResetTargetSendKey(valid.targetHash()),
                        keyFactory.passwordResetBlockKey(valid.deviceHash()),
                        keyFactory.globalDeviceBlockKey(valid.globalDeviceHash())),
                valid.deviceHash().value(), valid.challengeId().value(),
                Long.toString(now.toEpochMilli()), digest.value(), operationId.value(),
                Long.toString(CODE_TTL), Long.toString(COOLDOWN), "5",
                Long.toString(WINDOW), Long.toString(BLOCK));
        switch ((int) result) {
            case 0 -> { return; }
            case 1 -> throw error(PasswordResetErrorCode.RESET_FLOW_NOT_FOUND, "找回密码流程不存在。");
            case 2 -> throw error(PasswordResetErrorCode.RESET_FLOW_EXPIRED, "找回密码流程已过期。");
            case 3 -> throw error(PasswordResetErrorCode.RESET_FLOW_FORBIDDEN, "找回密码流程无效。");
            case 4 -> throw error(PasswordResetErrorCode.HUMAN_VERIFICATION_REQUIRED, "请先完成人机验证。");
            case 5 -> throw error(PasswordResetErrorCode.VERIFICATION_COOLDOWN, "验证码发送过于频繁。");
            case 6 -> throw error(PasswordResetErrorCode.VERIFICATION_SEND_LIMIT, "该目标发送次数过多。");
            case 7 -> throw error(PasswordResetErrorCode.RESET_BLOCKED, "找回密码暂时被限制。");
            default -> throw unavailable();
        }
    }

    @Override
    public boolean claimDeliveryAttempt(
            ProtectedPasswordResetAccess access,
            HmacIdentifier operationId,
            String messageId,
            int attemptNo) {
        ProtectedPasswordResetAccess valid = requireAccess(access);
        return execute(
                CLAIM_DELIVERY,
                List.of(
                        keyFactory.passwordResetFlowKey(valid.flowId()),
                        keyFactory.passwordResetCodeKey(valid.codeId())),
                requireIdentifier(operationId).value(),
                Integer.toString(attemptNo),
                requireText(messageId)) == 1L;
    }

    @Override
    public boolean releaseDeliveryForRetry(
            ProtectedPasswordResetAccess access,
            HmacIdentifier operationId,
            String messageId) {
        ProtectedPasswordResetAccess valid = requireAccess(access);
        return execute(
                RELEASE_DELIVERY,
                List.of(
                        keyFactory.passwordResetFlowKey(valid.flowId()),
                        keyFactory.passwordResetCodeKey(valid.codeId())),
                requireIdentifier(operationId).value(),
                requireText(messageId)) == 1L;
    }

    @Override
    public boolean markDeliverySucceeded(
            ProtectedPasswordResetAccess access, HmacIdentifier operationId) {
        ProtectedPasswordResetAccess valid = requireAccess(access);
        return execute(MARK_SUCCESS,
                List.of(keyFactory.passwordResetFlowKey(valid.flowId()),
                        keyFactory.passwordResetCodeKey(valid.codeId())),
                operationId.value(), "5") == 1L;
    }

    @Override
    public boolean markDeliveryAccepted(
            ProtectedPasswordResetAccess access,
            HmacIdentifier operationId,
            String providerMessageId,
            String providerStatus) {
        ProtectedPasswordResetAccess valid = requireAccess(access);
        return execute(MARK_ACCEPTED,
                List.of(keyFactory.passwordResetFlowKey(valid.flowId()),
                        keyFactory.passwordResetCodeKey(valid.codeId())),
                requireIdentifier(operationId).value(), "5",
                requireProviderText(providerMessageId), requireProviderText(providerStatus)) == 1L;
    }

    @Override
    public boolean markDeliveryOutcomeUnknown(
            ProtectedPasswordResetAccess access,
            HmacIdentifier operationId,
            String safeReason) {
        ProtectedPasswordResetAccess valid = requireAccess(access);
        return execute(MARK_UNKNOWN,
                List.of(keyFactory.passwordResetFlowKey(valid.flowId()),
                        keyFactory.passwordResetCodeKey(valid.codeId())),
                requireIdentifier(operationId).value(), requireProviderText(safeReason)) == 1L;
    }

    @Override
    public String findDeliveryOutcomeUnknown(
            ProtectedPasswordResetAccess access, HmacIdentifier operationId) {
        ProtectedPasswordResetAccess valid = requireAccess(access);
        String reason = execute(FIND_UNKNOWN,
                List.of(keyFactory.passwordResetFlowKey(valid.flowId()),
                        keyFactory.passwordResetCodeKey(valid.codeId())),
                requireIdentifier(operationId).value());
        return reason == null || reason.isBlank() ? null : requireProviderText(reason);
    }

    @Override
    public boolean finalizeDeliveryFailure(
            ProtectedPasswordResetAccess access, HmacIdentifier operationId) {
        return compensateDeliveryFailure(access, operationId);
    }

    @Override
    public boolean compensateDeliveryFailure(
            ProtectedPasswordResetAccess access, HmacIdentifier operationId) {
        ProtectedPasswordResetAccess valid = requireAccess(access);
        return execute(COMPENSATE,
                List.of(
                        keyFactory.passwordResetFlowKey(valid.flowId()),
                        keyFactory.passwordResetCodeKey(valid.codeId()),
                        keyFactory.passwordResetSendRiskKey(valid.deviceHash()),
                        keyFactory.passwordResetTargetSendKey(valid.targetHash())),
                operationId.value()) == 1L;
    }

    @Override
    public long verifyAndCreateForgetToken(
            ProtectedPasswordResetAccess access,
            HmacIdentifier digest,
            HmacIdentifier forgetTokenHash,
            Instant now) {
        ProtectedPasswordResetAccess valid = requireAccess(access);
        // 验证、失败计数与一次性找回凭据创建必须原子完成，避免并发请求都获得有效找回凭据。
        List<?> result = execute(VERIFY,
                List.of(
                        keyFactory.passwordResetFlowKey(valid.flowId()),
                        keyFactory.passwordResetCodeKey(valid.codeId()),
                        keyFactory.passwordResetVerifyRiskKey(valid.deviceHash()),
                        keyFactory.passwordResetBlockKey(valid.deviceHash()),
                        keyFactory.passwordResetForgetKey(forgetTokenHash),
                        keyFactory.globalDeviceBlockKey(valid.globalDeviceHash())),
                valid.deviceHash().value(), valid.challengeId().value(),
                Long.toString(now.toEpochMilli()), digest.value(), "5", "10",
                Long.toString(WINDOW), Long.toString(BLOCK), Long.toString(FORGET_TTL));
        return switch (status(result)) {
            case 0 -> number(result.get(1));
            case 1 -> throw error(PasswordResetErrorCode.RESET_FLOW_NOT_FOUND, "找回密码流程不存在。");
            case 2 -> throw error(PasswordResetErrorCode.RESET_FLOW_EXPIRED, "找回密码流程已过期。");
            case 3 -> throw error(PasswordResetErrorCode.RESET_FLOW_FORBIDDEN, "找回密码流程无效。");
            case 4 -> throw error(PasswordResetErrorCode.HUMAN_VERIFICATION_REQUIRED, "请先完成人机验证。");
            case 5 -> throw error(PasswordResetErrorCode.VERIFICATION_CODE_EXPIRED, "验证码已过期。");
            case 6, 7 -> throw error(PasswordResetErrorCode.VERIFICATION_CODE_INVALID, "验证码不正确。");
            case 8 -> throw error(PasswordResetErrorCode.RESET_BLOCKED, "找回密码暂时被限制。");
            default -> throw unavailable();
        };
    }

    @Override
    public long claimForgetToken(
            HmacIdentifier forgetTokenHash,
            HmacIdentifier deviceHash,
            HmacIdentifier claimId,
            Instant now) {
        // 领取动作把凭据绑定到设备与 claimId；提交前可释放，提交后只能由同一 claimId 消费。
        List<?> result = execute(CLAIM,
                List.of(keyFactory.passwordResetForgetKey(forgetTokenHash)),
                deviceHash.value(), claimId.value(), Long.toString(now.toEpochMilli()));
        if (status(result) != 0) {
            throw error(PasswordResetErrorCode.FORGET_TOKEN_INVALID,
                    "重置密码凭证无效或已使用。");
        }
        return number(result.get(1));
    }

    @Override
    public void consumeForgetToken(
            HmacIdentifier forgetTokenHash, HmacIdentifier claimId) {
        execute(CONSUME, List.of(keyFactory.passwordResetForgetKey(forgetTokenHash)),
                claimId.value());
    }

    @Override
    public void releaseForgetToken(
            HmacIdentifier forgetTokenHash, HmacIdentifier claimId) {
        execute(RELEASE, List.of(keyFactory.passwordResetForgetKey(forgetTokenHash)),
                claimId.value());
    }

    private List<String> flowKeys(ProtectedPasswordResetAccess access) {
        return List.of(keyFactory.passwordResetFlowKey(access.flowId()),
                keyFactory.passwordResetChallengeKey(access.challengeId()));
    }

    private static PasswordResetFlowSnapshot snapshot(List<?> result) {
        int status = status(result);
        if (status != 0) {
            throw switch (status) {
                case 1 -> error(PasswordResetErrorCode.RESET_FLOW_NOT_FOUND, "找回密码流程不存在。");
                case 2 -> error(PasswordResetErrorCode.RESET_FLOW_EXPIRED, "找回密码流程已过期。");
                case 3 -> error(PasswordResetErrorCode.RESET_FLOW_FORBIDDEN, "找回密码流程无效。");
                default -> unavailable();
            };
        }
        return new PasswordResetFlowSnapshot(
                VerificationChannel.valueOf(text(result.get(1))),
                text(result.get(2)), number(result.get(3)),
                "1".equals(text(result.get(4))),
                Instant.ofEpochMilli(number(result.get(5))),
                Instant.ofEpochMilli(number(result.get(6))),
                Instant.ofEpochMilli(number(result.get(7))));
    }

    private static ProtectedPasswordResetAccess requireAccess(
            ProtectedPasswordResetAccess access) {
        if (access == null || access.flowId() == null || access.challengeId() == null
                || access.deviceHash() == null || access.codeId() == null
                || access.globalDeviceHash() == null || access.targetHash() == null) {
            throw error(PasswordResetErrorCode.RESET_FLOW_FORBIDDEN, "找回密码流程无效。");
        }
        return access;
    }

    private static HmacIdentifier requireIdentifier(HmacIdentifier identifier) {
        if (identifier == null) {
            throw error(PasswordResetErrorCode.RESET_FLOW_FORBIDDEN, "鎵惧洖瀵嗙爜娴佺▼鏃犳晥銆?");
        }
        return identifier;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank() || value.length() > 254) {
            throw error(PasswordResetErrorCode.RESET_FLOW_FORBIDDEN, "鎵惧洖瀵嗙爜娴佺▼鏃犳晥銆?");
        }
        return value;
    }

    private static String requireProviderText(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.matches("^[A-Za-z0-9._:-]+$")) {
            throw error(PasswordResetErrorCode.INVALID_INPUT,
                    "Provider delivery diagnostic is invalid.");
        }
        return value;
    }

    private <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
        try {
            T result = redisTemplate.execute(script, keys, args);
            if (result == null) throw unavailable();
            return result;
        } catch (PasswordResetException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PasswordResetException(
                    PasswordResetErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "找回密码服务暂时不可用。", exception);
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
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        if (value == null) throw unavailable();
        return value.toString();
    }

    private static PasswordResetException error(
            PasswordResetErrorCode code, String message) {
        return new PasswordResetException(code, message);
    }

    private static PasswordResetException unavailable() {
        return error(PasswordResetErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                "找回密码服务暂时不可用。");
    }

    private static RedisScript<Long> loginLong(String name) {
        return script("auth-login/" + name, Long.class);
    }

    private static RedisScript<Long> resetLong(String name) {
        return script("auth-password-reset/" + name, Long.class);
    }

    private static RedisScript<String> resetString(String name) {
        return script("auth-password-reset/" + name, String.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RedisScript<List> loginList(String name) {
        return (RedisScript) script("auth-login/" + name, List.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RedisScript<List> resetList(String name) {
        return (RedisScript) script("auth-password-reset/" + name, List.class);
    }

    private static <T> RedisScript<T> script(String path, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/" + path));
        script.setResultType(type);
        return script;
    }
}
