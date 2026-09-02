package com.example.temperate.web.user.membership.payment;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutSubmission;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutMode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该响应是来向 H5 提供一次短时有效的 Provider 顶层表单或 HTTPS 跳转描述，不代表支付成功事实。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MembershipCheckoutSubmissionResponse(
        PaymentProviderType provider,
        PaymentCheckoutMode checkoutMode,
        URI action,
        String method,
        String contentType,
        OffsetDateTime submitExpiresAt,
        MembershipCheckoutSubmissionFieldsResponse fields) {

    public static MembershipCheckoutSubmissionResponse from(
            PaymentCheckoutSubmission submission) {
        PaymentCheckoutSubmission value = Objects.requireNonNull(submission);
        return new MembershipCheckoutSubmissionResponse(
                value.provider(),
                value.checkoutMode(),
                value.action(),
                value.method(),
                value.contentType(),
                value.submitExpiresAt(),
                value.fields() == null
                        ? null
                        : MembershipCheckoutSubmissionFieldsResponse.from(value.fields()));
    }
}
