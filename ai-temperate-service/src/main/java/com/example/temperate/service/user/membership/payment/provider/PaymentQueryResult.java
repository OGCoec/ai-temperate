package com.example.temperate.service.user.membership.payment.provider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 该结果是来保存经过具体 Provider 边界核验后的权威支付状态；外部平台实现禁止用未验签 HTTP 字段构造。
 */
public record PaymentQueryResult(
        String orderId,
        String providerTradeNo,
        String channelTradeNo,
        PaymentProviderStatus status,
        BigDecimal amountYuan,
        OffsetDateTime finishedAt,
        String callbackId) {

    public static PaymentQueryResult unknown(String orderId) {
        return new PaymentQueryResult(
                orderId, null, null, PaymentProviderStatus.UNKNOWN, null, null, null);
    }
}
