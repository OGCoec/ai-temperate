package com.example.temperate.service.user.membership.payment.callback.impl;

import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRefundService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTraceContext;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundResult;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来真正调用当前 Provider 的退款接口并验证全额终态；日志只在请求事实发生后记录低基数事件。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentRefundServiceImpl
        implements MembershipPaymentRefundService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MembershipPaymentRefundServiceImpl.class);

    private final MembershipPaymentProviderRegistry providerRegistry;
    private final MembershipPaymentProperties properties;
    private final MembershipPaymentMetrics metrics;

    public MembershipPaymentRefundServiceImpl(
            MembershipPaymentProviderRegistry providerRegistry,
            MembershipPaymentProperties properties,
            MembershipPaymentMetrics metrics) {
        this.providerRegistry = Objects.requireNonNull(providerRegistry);
        this.properties = Objects.requireNonNull(properties);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public void refund(PaymentRefundCommand command) {
        PaymentRefundCommand value = Objects.requireNonNull(command);
        LOGGER.warn(
                "Membership payment refund request started; traceId={} provider={}",
                MembershipPaymentTraceContext.currentTraceId(),
                properties.defaultProvider());
        MembershipPaymentProvider provider = providerRegistry.getRequired(
                properties.defaultProvider());
        PaymentRefundResult result = provider.refundPayment(value);
        if (result.status() != PaymentProviderStatus.REFUNDED
                || !Objects.equals(value.providerTradeNo(), result.providerTradeNo())
                || result.refundedAmountYuan() == null
                || value.amountYuan().compareTo(result.refundedAmountYuan()) != 0) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.BAR_RESPONSE_INVALID,
                    "The payment provider did not confirm the full refund.");
        }
        metrics.refundRequired();
        LOGGER.warn(
                "Membership payment refund request succeeded; traceId={} provider={}",
                MembershipPaymentTraceContext.currentTraceId(),
                properties.defaultProvider());
    }
}
