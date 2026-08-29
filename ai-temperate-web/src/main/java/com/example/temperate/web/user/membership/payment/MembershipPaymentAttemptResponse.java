package com.example.temperate.web.user.membership.payment;

import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * 该响应是来组合会员订单事实和本次支付发起生成的可空短时提交描述，供 H5 立即完成顶层 Form POST。
 */
public record MembershipPaymentAttemptResponse(
        MembershipOrderResponse order,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        MembershipCheckoutSubmissionResponse checkoutSubmission) {

    public static MembershipPaymentAttemptResponse from(
            MembershipPaymentAttemptResult result) {
        MembershipPaymentAttemptResult value = Objects.requireNonNull(result);
        return new MembershipPaymentAttemptResponse(
                MembershipOrderResponse.from(value.snapshot()),
                value.checkoutSubmission() == null
                        ? null
                        : MembershipCheckoutSubmissionResponse.from(value.checkoutSubmission()));
    }
}
