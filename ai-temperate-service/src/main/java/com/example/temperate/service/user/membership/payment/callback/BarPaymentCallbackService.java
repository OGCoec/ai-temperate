package com.example.temperate.service.user.membership.payment.callback;

/**
 * 该服务是来完成 BAR 回调验签、订单核对、权威反查和现有 Redis 回调队列入队，不直接更新订单数据库状态。
 */
public interface BarPaymentCallbackService {

    BarPaymentCallbackResult receive(BarPaymentCallbackCommand command);
}
