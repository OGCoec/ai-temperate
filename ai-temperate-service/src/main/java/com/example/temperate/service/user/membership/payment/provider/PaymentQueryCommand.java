package com.example.temperate.service.user.membership.payment.provider;

/**
 * 该命令是来按商户订单号或已绑定平台流水查询支付提供方权威状态。
 */
public record PaymentQueryCommand(
        String orderId,
        String providerTradeNo) {
}
