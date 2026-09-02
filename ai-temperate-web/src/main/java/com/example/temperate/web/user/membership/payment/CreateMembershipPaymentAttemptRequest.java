package com.example.temperate.web.user.membership.payment;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

/** 该请求是来在真正发起外部支付时选择 BAR 或六号，且禁止客户端把内部模拟器当成公开支付提供方。 */
public record CreateMembershipPaymentAttemptRequest(
        @NotNull PaymentProviderType provider) {

    /** 未知字段必须失败，避免客户端误以为支付金额、入口或路由信息可以由请求覆盖。 */
    @JsonAnySetter
    public void rejectUnknown(String name, JsonNode value) {
        throw new MembershipPaymentException(
                MembershipPaymentErrorCode.INPUT_INVALID,
                "Payment attempt request contains an unsupported field.");
    }
}
