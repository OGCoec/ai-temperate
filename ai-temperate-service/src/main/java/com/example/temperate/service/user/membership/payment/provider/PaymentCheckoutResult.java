package com.example.temperate.service.user.membership.payment.provider;

import java.time.OffsetDateTime;

/**
 * 该结果是来返回 Provider 订单绑定事实和一次短时浏览器提交描述，不承担任何持久化职责。
 */
public record PaymentCheckoutResult(
        String providerTradeNo,
        OffsetDateTime expiresAt,
        boolean created,
        PaymentCheckoutSubmission checkoutSubmission) {
}
