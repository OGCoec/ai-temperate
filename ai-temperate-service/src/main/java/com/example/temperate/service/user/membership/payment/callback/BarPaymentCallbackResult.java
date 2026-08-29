package com.example.temperate.service.user.membership.payment.callback;

/**
 * 该结果是来告诉 Web 层 BAR 回调已经首次入队或命中合法重复；两者都必须返回 success。
 */
public record BarPaymentCallbackResult(boolean duplicate) {
}
