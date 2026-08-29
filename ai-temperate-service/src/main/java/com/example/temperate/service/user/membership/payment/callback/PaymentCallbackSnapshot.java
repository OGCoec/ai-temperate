package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.common.redis.key.PaymentCallbackRedisId;
import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该快照是来保存已通过传输层解析的模拟支付回调最小业务字段，供五秒批处理执行订单和金额校验。
 *
 * <p>快照不保存签名、买家信息或完整请求体，GET 与 POST 归一化后共享同一结构。</p>
 */
public record PaymentCallbackSnapshot(
        int schemaVersion,
        String callbackId,
        String orderId,
        String pid,
        String providerTradeNo,
        String channelTradeNo,
        String payType,
        String tradeStatus,
        BigDecimal paidAmountYuan,
        OffsetDateTime paidAt,
        OffsetDateTime receivedAt,
        long requestTimestampEpochSeconds,
        String idempotencyFingerprint,
        String payloadDigest) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public PaymentCallbackSnapshot {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported payment callback snapshot schema.");
        }
        new PaymentCallbackRedisId(callbackId);
        new MembershipOrderRedisId(orderId);
        pid = requireText("pid", pid, 64);
        providerTradeNo = requireText("provider trade number", providerTradeNo, 128);
        channelTradeNo = requireText("channel trade number", channelTradeNo, 128);
        payType = requireText("pay type", payType, 16);
        tradeStatus = requireText("trade status", tradeStatus, 32);
        paidAmountYuan = requireAmount(paidAmountYuan);
        paidAt = MembershipPaymentTime.normalize(
                Objects.requireNonNull(paidAt, "paidAt must not be null"));
        receivedAt = MembershipPaymentTime.normalize(
                Objects.requireNonNull(receivedAt, "receivedAt must not be null"));
        if (requestTimestampEpochSeconds <= 0) {
            throw new IllegalArgumentException("Payment callback timestamp must be positive.");
        }
        idempotencyFingerprint = requireDigest(
                "idempotency fingerprint", idempotencyFingerprint);
        payloadDigest = requireDigest("payload digest", payloadDigest);
    }

    private static BigDecimal requireAmount(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("Payment callback amount must be non-negative.");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Payment callback amount must contain at most two decimals.", exception);
        }
    }

    private static String requireText(String name, String value, int maximumLength) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.trim())
                || value.length() > maximumLength) {
            throw new IllegalArgumentException("Payment callback " + name + " is invalid.");
        }
        return value;
    }

    private static String requireDigest(String name, String value) {
        String digest = requireText(name, value, 43);
        if (!digest.matches("^[A-Za-z0-9_-]{43}$")) {
            throw new IllegalArgumentException("Payment callback " + name + " is invalid.");
        }
        return digest;
    }
}
