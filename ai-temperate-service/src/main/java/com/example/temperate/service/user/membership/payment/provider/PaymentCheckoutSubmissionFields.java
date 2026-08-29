package com.example.temperate.service.user.membership.payment.provider;

import java.util.Objects;

/**
 * 该值对象是来承载浏览器提交 BAR Checkout 所需的固定签名字段，不包含 API Key、Cookie 或可持久化令牌。
 */
public record PaymentCheckoutSubmissionFields(
        String pid,
        String outTradeNo,
        String type,
        String name,
        String money,
        String notifyUrl,
        String returnUrl,
        String timestamp,
        String keyVersion,
        String signType,
        String sign) {

    public PaymentCheckoutSubmissionFields {
        pid = Objects.requireNonNull(pid);
        outTradeNo = Objects.requireNonNull(outTradeNo);
        type = Objects.requireNonNull(type);
        name = Objects.requireNonNull(name);
        money = Objects.requireNonNull(money);
        notifyUrl = Objects.requireNonNull(notifyUrl);
        returnUrl = Objects.requireNonNull(returnUrl);
        timestamp = Objects.requireNonNull(timestamp);
        keyVersion = Objects.requireNonNull(keyVersion);
        signType = Objects.requireNonNull(signType);
        sign = Objects.requireNonNull(sign);
    }

    /** 签名字段只允许进入一次性 HTTP 响应和 Form 正文，调试字符串必须整体脱敏。 */
    @Override
    public String toString() {
        return "PaymentCheckoutSubmissionFields[redacted]";
    }
}
