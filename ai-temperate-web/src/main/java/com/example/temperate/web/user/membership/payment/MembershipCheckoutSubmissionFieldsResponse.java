package com.example.temperate.web.user.membership.payment;

import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutSubmissionFields;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * 该响应是来把支付 Provider 浏览器 Form 的固定字段映射为协议要求的 snake_case JSON 键，并阻止任意字段穿透到客户端。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MembershipCheckoutSubmissionFieldsResponse(
        String pid,
        @JsonProperty("out_trade_no") String outTradeNo,
        String type,
        String name,
        String money,
        @JsonProperty("notify_url") String notifyUrl,
        @JsonProperty("return_url") String returnUrl,
        String timestamp,
        @JsonProperty("key_version") String keyVersion,
        @JsonProperty("sign_type") String signType,
        String sign) {

    public static MembershipCheckoutSubmissionFieldsResponse from(
            PaymentCheckoutSubmissionFields fields) {
        PaymentCheckoutSubmissionFields value = Objects.requireNonNull(fields);
        return new MembershipCheckoutSubmissionFieldsResponse(
                value.pid(),
                value.outTradeNo(),
                value.type(),
                value.name(),
                value.money(),
                value.notifyUrl(),
                value.returnUrl(),
                value.timestamp(),
                value.keyVersion(),
                value.signType(),
                value.sign());
    }

    /** 防止异常日志或调试输出通过记录默认 toString 泄露完整签名表单。 */
    @Override
    public String toString() {
        return "MembershipCheckoutSubmissionFieldsResponse[redacted]";
    }
}
