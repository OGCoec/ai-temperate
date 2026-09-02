package com.example.temperate.service.user.membership.payment.refund;

/**
 * 该枚举是来约束进入退款终态失败队列的三种人工处理原因，禁止把任意外部文本写入消息。
 */
public enum PaymentRefundTerminalOutcome {
    EXPLICIT_FAILURE,
    TIMEOUT_EXHAUSTED,
    ATTEMPT_OUTCOME_UNKNOWN
}
