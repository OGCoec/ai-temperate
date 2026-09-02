package com.example.temperate.service.user.membership.payment.provider;

/**
 * 该枚举是来声明浏览器执行一次短时支付入口的方式，避免前端根据 Provider 名称猜测提交协议。
 */
public enum PaymentCheckoutMode {
    FORM_POST,
    REDIRECT_URL
}
