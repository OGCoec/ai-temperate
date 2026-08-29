package com.example.temperate.service.user.membership.payment.provider;

import java.math.BigDecimal;

/**
 * 该结果是来表达提供方已经确认的退款状态和模拟退款流水。
 */
public record PaymentRefundResult(
        PaymentProviderStatus status,
        String providerTradeNo,
        String providerRefundNo,
        BigDecimal refundedAmountYuan) {
}
