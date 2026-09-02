package com.example.temperate.service.user.membership.payment.provider;

import java.math.BigDecimal;

/**
 * 该命令是来承载 Provider API 统一下单所需的已核验订单事实，不包含签名、密钥或浏览器令牌。
 */
public record PaymentCreateCommand(
        String orderId,
        BigDecimal amountYuan,
        String payType,
        String orderName,
        String clientIp) {
}
