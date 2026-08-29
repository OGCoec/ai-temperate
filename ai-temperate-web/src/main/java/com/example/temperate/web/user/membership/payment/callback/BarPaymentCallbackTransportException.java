package com.example.temperate.web.user.membership.payment.callback;

/**
 * 该异常是来表示 BAR 回调在进入业务验签前发生的 Query 白名单或重复参数错误，不携带原始查询串。
 */
public final class BarPaymentCallbackTransportException extends RuntimeException {

    public BarPaymentCallbackTransportException(String message) {
        super(message);
    }
}
