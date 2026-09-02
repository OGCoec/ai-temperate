package com.example.temperate.service.user.membership.payment.callback;

/**
 * 该枚举是来把单次退款尝试固定分类为成功、明确失败或受控超时，只有受控超时允许创建下一次外部请求。
 */
public enum PaymentRefundAttemptOutcome {
    SUCCEEDED,
    EXPLICIT_FAILURE,
    TIMED_OUT
}
