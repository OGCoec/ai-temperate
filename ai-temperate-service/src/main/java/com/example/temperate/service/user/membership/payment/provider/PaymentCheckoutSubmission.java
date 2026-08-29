package com.example.temperate.service.user.membership.payment.provider;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该值对象是来描述一次短时浏览器顶层 Form 提交，协作边界止于当前 payment-attempts HTTP 响应。
 *
 * <p>它不是订单事实，不得写入 PostgreSQL、Redis、RabbitMQ、日志或浏览器持久化存储。</p>
 */
public record PaymentCheckoutSubmission(
        PaymentProviderType provider,
        URI action,
        String method,
        String contentType,
        OffsetDateTime submitExpiresAt,
        PaymentCheckoutSubmissionFields fields) {

    public PaymentCheckoutSubmission {
        provider = Objects.requireNonNull(provider);
        action = Objects.requireNonNull(action);
        method = Objects.requireNonNull(method);
        contentType = Objects.requireNonNull(contentType);
        submitExpiresAt = Objects.requireNonNull(submitExpiresAt);
        fields = Objects.requireNonNull(fields);
    }
}
