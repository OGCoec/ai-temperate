package com.example.temperate.service.user.membership.payment.provider;

/**
 * 该结果是来保存 API 统一下单经过验签后的交易号和短期支付载体；支付载体不得写入订单、Redis 或日志。
 */
public record PaymentCreateResult(
        String providerTradeNo,
        String providerPayType,
        String payInfo,
        boolean created) {
}
