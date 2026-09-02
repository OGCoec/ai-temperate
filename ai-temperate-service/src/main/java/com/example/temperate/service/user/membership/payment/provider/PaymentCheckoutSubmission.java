package com.example.temperate.service.user.membership.payment.provider;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该值对象是来描述一次短时浏览器顶层表单提交或 HTTPS 跳转，协作边界止于当前 payment-attempts HTTP 响应。
 *
 * <p>它不是订单事实，不得写入 PostgreSQL、Redis、RabbitMQ、日志或浏览器持久化存储。</p>
 */
public record PaymentCheckoutSubmission(
        PaymentProviderType provider,
        PaymentCheckoutMode checkoutMode,
        URI action,
        String method,
        String contentType,
        OffsetDateTime submitExpiresAt,
        PaymentCheckoutSubmissionFields fields) {

    public PaymentCheckoutSubmission {
        provider = Objects.requireNonNull(provider);
        checkoutMode = Objects.requireNonNull(checkoutMode);
        action = Objects.requireNonNull(action);
        method = Objects.requireNonNull(method);
        submitExpiresAt = Objects.requireNonNull(submitExpiresAt);
        // 表单模式必须携带已签名字段；跳转模式只允许 GET 且不得伪装成表单提交。
        if (checkoutMode == PaymentCheckoutMode.FORM_POST) {
            if (!"POST".equals(method)) {
                throw new IllegalArgumentException("Form checkout must use POST.");
            }
            contentType = Objects.requireNonNull(contentType);
            fields = Objects.requireNonNull(fields);
        } else if (!"GET".equals(method) || contentType != null || fields != null) {
            throw new IllegalArgumentException(
                    "Redirect checkout must use GET without content type or form fields.");
        }
    }
}
