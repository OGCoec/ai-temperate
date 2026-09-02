package com.example.temperate.service.user.membership.payment.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * 该值对象是来承载浏览器提交外部收银台所需的固定签名字段，不包含私钥、API Key、Cookie 或可持久化令牌。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
        // RSA V2 不使用 BAR 的密钥版本字段；空值由 Web JSON 层省略，不能提交字符串 "null"。
        if (keyVersion != null && (keyVersion.isBlank() || !keyVersion.equals(keyVersion.trim()))) {
            throw new IllegalArgumentException("keyVersion must be absent or non-blank");
        }
        signType = Objects.requireNonNull(signType);
        sign = Objects.requireNonNull(sign);
    }

    /** 签名字段只允许进入一次性 HTTP 响应和 Form 正文，调试字符串必须整体脱敏。 */
    @Override
    public String toString() {
        return "PaymentCheckoutSubmissionFields[redacted]";
    }
}
