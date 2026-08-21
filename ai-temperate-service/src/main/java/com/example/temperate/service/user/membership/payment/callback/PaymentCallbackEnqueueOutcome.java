package com.example.temperate.service.user.membership.payment.callback;

/**
 * 该枚举是来区分模拟支付回调首次入队与 GET/POST 或供应商重试形成的短期业务重复。
 */
public enum PaymentCallbackEnqueueOutcome {
    ENQUEUED,
    DUPLICATE
}
