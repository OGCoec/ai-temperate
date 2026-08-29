package com.example.temperate.web.user.membership.payment;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutSubmission;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该响应是来向 H5 提供一次短时有效的 BAR 顶层 Form POST 提交描述，不代表支付成功事实。
 */
public record MembershipCheckoutSubmissionResponse(
        PaymentProviderType provider,
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
                value.action(),
                value.method(),
                value.contentType(),
                value.submitExpiresAt(),
                MembershipCheckoutSubmissionFieldsResponse.from(value.fields()));
    }
}
