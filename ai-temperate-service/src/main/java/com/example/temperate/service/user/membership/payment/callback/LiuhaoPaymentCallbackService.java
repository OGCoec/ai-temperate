package com.example.temperate.service.user.membership.payment.callback;

/** 该服务是来完成六号易支付回调验签、订单核对、主动反查和既有 Redis 回调队列入队。 */
public interface LiuhaoPaymentCallbackService {

    boolean receive(LiuhaoPaymentCallbackCommand command);
}
