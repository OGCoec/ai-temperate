package com.example.temperate.service.user.membership.payment.provider;

import java.math.BigDecimal;

/**
 * 该命令是来请求支付提供方对已确认交易执行全额幂等退款。
 */
public record PaymentRefundCommand(
        String orderId,
        String providerTradeNo,
        BigDecimal amountYuan) {
}
