package com.example.temperate.service.user.membership.payment.provider;

/**
 * 该命令是来请求支付提供方幂等关闭一笔尚未支付的订单。
 */
public record PaymentCloseCommand(
        String orderId,
        String providerTradeNo) {
}
