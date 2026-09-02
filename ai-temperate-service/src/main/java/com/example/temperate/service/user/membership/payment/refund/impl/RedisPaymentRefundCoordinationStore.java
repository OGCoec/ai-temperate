package com.example.temperate.service.user.membership.payment.refund.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.redis.key.PaymentCallbackRedisId;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.refund.PaymentRefundCoordinationAction;
import com.example.temperate.service.user.membership.payment.refund.PaymentRefundCoordinationDecision;
import com.example.temperate.service.user.membership.payment.refund.PaymentRefundCoordinationStore;
import com.example.temperate.service.user.membership.payment.refund.PaymentRefundTerminalOutcome;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 该实现是来用单 Key Lua 原子维护退款尝试、待发布消息与终态，避免 Rabbit 重投触发重复退款。
 *
 * <p>Hash 的二十四小时 TTL 只覆盖崩溃恢复窗口；PostgreSQL 的 REFUND_REQUIRED 仍是退款业务事实来源。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class RedisPaymentRefundCoordinationStore
        implements PaymentRefundCoordinationStore {

    private static final long TTL_MILLIS = Duration.ofHours(24).toMillis();
    private static final RedisScript<String> BEGIN_INITIAL = script("begin_initial.lua");
    private static final RedisScript<String> CLAIM_RETRY = script("claim_retry.lua");
    private static final RedisScript<Long> MARK_SUCCEEDED = longScript("mark_succeeded.lua");
    private static final RedisScript<Long> PREPARE_RETRY = longScript("prepare_retry.lua");
    private static final RedisScript<Long> CONFIRM_RETRY = longScript("confirm_retry.lua");
    private static final RedisScript<Long> PREPARE_TERMINAL = longScript("prepare_terminal.lua");
    private static final RedisScript<Long> CONFIRM_TERMINAL = longScript("confirm_terminal.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final HybridBase64UrlCodec idCodec;

    public RedisPaymentRefundCoordinationStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            HybridBase64UrlCodec idCodec) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.idCodec = Objects.requireNonNull(idCodec);
    }

    @Override
    public PaymentRefundCoordinationDecision beginInitial(String callbackId) {
        return decision(executeString(
                BEGIN_INITIAL,
                callbackId,
                Long.toString(TTL_MILLIS)));
    }

    @Override
    public PaymentRefundCoordinationDecision claimRetry(
            String callbackId, int attemptNo, String messageId) {
        requireAttempt(attemptNo);
        requireMessageId(messageId);
        return decision(executeString(
                CLAIM_RETRY,
                callbackId,
                Integer.toString(attemptNo),
                messageId,
                Long.toString(TTL_MILLIS)));
    }

    @Override
    public boolean markSucceeded(String callbackId, int attemptNo) {
        requireAttempt(attemptNo);
        return executeBoolean(
                MARK_SUCCEEDED,
                callbackId,
                Integer.toString(attemptNo),
                Long.toString(TTL_MILLIS));
    }

    @Override
    public boolean prepareRetry(
            String callbackId,
            int attemptNo,
            String messageId,
            int nextAttemptNo,
            String safeReason) {
        requireAttempt(attemptNo);
        requireAttempt(nextAttemptNo);
        if (nextAttemptNo != attemptNo + 1) {
            throw new IllegalArgumentException("Payment refund retry attempt must be monotonic.");
        }
        requireMessageId(messageId);
        requireReason(safeReason);
        return executeBoolean(
                PREPARE_RETRY,
                callbackId,
                Integer.toString(attemptNo),
                messageId,
                Integer.toString(nextAttemptNo),
                safeReason,
                Long.toString(TTL_MILLIS));
    }

    @Override
    public boolean confirmRetry(String callbackId, String messageId, int nextAttemptNo) {
        requireMessageId(messageId);
        requireAttempt(nextAttemptNo);
        return executeBoolean(
                CONFIRM_RETRY,
                callbackId,
                messageId,
                Integer.toString(nextAttemptNo),
                Long.toString(TTL_MILLIS));
    }

    @Override
    public boolean prepareTerminal(
            String callbackId,
            int attemptNo,
            String messageId,
            PaymentRefundTerminalOutcome outcome,
            String safeReason) {
        requireAttempt(attemptNo);
        requireMessageId(messageId);
        requireReason(safeReason);
        return executeBoolean(
                PREPARE_TERMINAL,
                callbackId,
                Integer.toString(attemptNo),
                messageId,
                Objects.requireNonNull(outcome).name(),
                safeReason,
                Long.toString(TTL_MILLIS));
    }

    @Override
    public boolean confirmTerminal(String callbackId, String messageId) {
        requireMessageId(messageId);
        return executeBoolean(
                CONFIRM_TERMINAL,
                callbackId,
                messageId,
                Long.toString(TTL_MILLIS));
    }

    private String executeString(
            RedisScript<String> script,
            String callbackId,
            String... arguments) {
        try {
            String value = redisTemplate.execute(
                    script,
                    List.of(key(callbackId)),
                    (Object[]) arguments);
            if (value == null) {
                throw unavailable("Redis refund coordination returned no result.");
            }
            return value;
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis refund coordination failed.", exception);
        }
    }

    private boolean executeBoolean(
            RedisScript<Long> script,
            String callbackId,
            String... arguments) {
        try {
            Long value = redisTemplate.execute(
                    script,
                    List.of(key(callbackId)),
                    (Object[]) arguments);
            if (value == null || (value != 0L && value != 1L)) {
                throw unavailable("Redis refund coordination returned an invalid result.");
            }
            return value == 1L;
        } catch (MembershipPaymentInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Redis refund coordination failed.", exception);
        }
    }

    private String key(String callbackId) {
        return keyFactory.paymentRefundCoordinationKey(
                new PaymentCallbackRedisId(callbackId));
    }

    private PaymentRefundCoordinationDecision decision(String raw) {
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 6) {
            throw unavailable("Redis refund coordination decision is malformed.");
        }
        try {
            return new PaymentRefundCoordinationDecision(
                    PaymentRefundCoordinationAction.valueOf(parts[0]),
                    integer(parts[1]),
                    emptyToNull(parts[2]),
                    integer(parts[3]),
                    parts[4].isEmpty()
                            ? null
                            : PaymentRefundTerminalOutcome.valueOf(parts[4]),
                    emptyToNull(parts[5]));
        } catch (IllegalArgumentException exception) {
            throw unavailable("Redis refund coordination decision is invalid.", exception);
        }
    }

    private void requireMessageId(String messageId) {
        if (!Objects.equals(messageId, idCodec.encode(idCodec.decode(messageId)))) {
            throw new IllegalArgumentException("Payment refund message ID is invalid.");
        }
    }

    private static int integer(String value) {
        return value.isEmpty() ? 0 : Integer.parseInt(value);
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private static void requireAttempt(int attemptNo) {
        if (attemptNo < 1 || attemptNo > 6) {
            throw new IllegalArgumentException("Payment refund attempt is invalid.");
        }
    }

    private static void requireReason(String safeReason) {
        if (safeReason == null || !safeReason.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
            throw new IllegalArgumentException("Payment refund reason is invalid.");
        }
    }

    private static RedisScript<String> script(String name) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/membership-payment/refund/" + name));
        script.setResultType(String.class);
        return script;
    }

    private static RedisScript<Long> longScript(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/membership-payment/refund/" + name));
        script.setResultType(Long.class);
        return script;
    }

    private static MembershipPaymentInfrastructureException unavailable(String message) {
        return new MembershipPaymentInfrastructureException(message);
    }

    private static MembershipPaymentInfrastructureException unavailable(
            String message, Throwable cause) {
        return new MembershipPaymentInfrastructureException(message, cause);
    }
}
