package com.example.temperate.service.user.membership.payment.provider;

import java.math.BigDecimal;

/**
 * 该命令是来承载创建支付页面所需的持久化订单事实，不包含 API Key、签名或浏览器令牌。
 */
public record PaymentCheckoutCommand(
        String orderId,
        BigDecimal amountYuan,
        String payType,
        String orderName) {
}
