package com.example.temperate.service.user.membership.payment.refund;

/**
 * 该枚举是来指示退款编排下一步只能调用 Provider、补发消息、完成原回调或安全处理未知结果。
 */
public enum PaymentRefundCoordinationAction {
    ATTEMPT_PROVIDER,
    PUBLISH_RETRY,
    PUBLISH_TERMINAL,
    COMPLETE_COORDINATED,
    ATTEMPT_OUTCOME_UNKNOWN,
    MESSAGE_NOT_READY,
    STALE_MESSAGE
}
