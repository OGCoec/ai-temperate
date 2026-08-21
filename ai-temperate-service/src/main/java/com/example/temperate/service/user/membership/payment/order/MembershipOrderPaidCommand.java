package com.example.temperate.service.user.membership.payment.order;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.common.redis.key.PaymentCallbackRedisId;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该命令是来为批量支付状态 Lua 提供一条已落库回调的订单、流水、金额和时间边界，不携带原始回调报文。
 */
public record MembershipOrderPaidCommand(
        String callbackId,
        String orderId,
        String providerTradeNo,
        BigDecimal paidAmountYuan,
        OffsetDateTime paidAt,
        OffsetDateTime changedAt) {

    public MembershipOrderPaidCommand {
        new PaymentCallbackRedisId(callbackId);
        new MembershipOrderRedisId(orderId);
        if (providerTradeNo == null
                || providerTradeNo.isBlank()
                || !providerTradeNo.equals(providerTradeNo.trim())
                || providerTradeNo.length() > 128) {
            throw new IllegalArgumentException("Provider trade number is invalid.");
        }
        Objects.requireNonNull(paidAmountYuan, "paidAmountYuan must not be null");
        Objects.requireNonNull(paidAt, "paidAt must not be null");
        Objects.requireNonNull(changedAt, "changedAt must not be null");
    }
}
