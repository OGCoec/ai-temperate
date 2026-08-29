package com.example.temperate.service.user.membership.payment.provider;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.common.redis.key.PaymentCallbackRedisId;
import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该快照是来模拟六号支付的订单查询事实，RabbitMQ 检查消费者据此区分未支付、已支付与未知结果。
 */
public record SimulatedPaymentProviderResult(
        int schemaVersion,
        String orderId,
        SimulatedPaymentProviderStatus status,
        String callbackId,
        String providerTradeNo,
        String payType,
        BigDecimal paidAmountYuan,
        OffsetDateTime updatedAt) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public SimulatedPaymentProviderResult {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported simulated provider result schema.");
        }
        new MembershipOrderRedisId(orderId);
        status = Objects.requireNonNull(status, "status must not be null");
        if (callbackId != null) {
            new PaymentCallbackRedisId(callbackId);
        }
        if (status == SimulatedPaymentProviderStatus.PAID
                && (callbackId == null
                || providerTradeNo == null
                || providerTradeNo.isBlank()
                || payType == null
                || payType.isBlank()
                || paidAmountYuan == null)) {
            throw new IllegalArgumentException(
                    "Paid simulated provider result requires payment details.");
        }
        updatedAt = MembershipPaymentTime.normalize(
                Objects.requireNonNull(updatedAt, "updatedAt must not be null"));
    }
}
