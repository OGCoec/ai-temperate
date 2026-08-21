package com.example.temperate.web.user.membership.payment;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * 该请求是来接收服务端定价的目标会员等级、白名单支付方式和客户端 UUIDv4 幂等键，不接受客户端金额。
 */
public record CreateMembershipOrderRequest(
        @NotNull MembershipTier targetTier,
        @NotBlank @Pattern(regexp = "^(alipay|wxpay)$") String payType,
        @NotNull UUID idempotencyKey) {

    /** 未知字段必须失败，避免客户端误以为金额、优惠或其他未实现字段已经生效。 */
    @JsonAnySetter
    public void rejectUnknown(String name, JsonNode value) {
        throw new MembershipPaymentException(
                MembershipPaymentErrorCode.INPUT_INVALID,
                "Membership order request contains an unsupported field.");
    }
}
