package com.example.temperate.service.user.membership.payment.provider;

/**
 * 该结果是来表达关单后的权威提供方状态，非安全终态必须由上层继续查询或重试。
 */
public record PaymentCloseResult(
        PaymentProviderStatus status,
        String providerTradeNo) {
}
