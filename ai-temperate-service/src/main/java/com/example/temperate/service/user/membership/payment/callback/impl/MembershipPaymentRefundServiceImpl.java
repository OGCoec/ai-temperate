package com.example.temperate.service.user.membership.payment.callback.impl;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRefundService;
import com.example.temperate.service.user.membership.payment.callback.PaymentRefundAttemptOutcome;
import com.example.temperate.service.user.membership.payment.callback.PaymentRefundAttemptResult;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTraceContext;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderReference;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundResult;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来真正调用当前 Provider 的退款接口、验证全额终态，并仅按受控错误码把网络超时标记为可重试。
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
    public PaymentRefundAttemptResult refund(PaymentRefundCommand command, int attemptNo) {
        PaymentRefundCommand value = Objects.requireNonNull(command);
        PaymentProviderType providerType = PaymentProviderReference.resolveTrade(
                value.providerTradeNo());
        LOGGER.warn(
                "Membership payment refund request started; traceId={} provider={}",
                MembershipPaymentTraceContext.currentTraceId(),
                providerType);
        MembershipPaymentProvider provider = providerRegistry.getRequired(providerType);
        try {
            PaymentRefundResult result = provider.refundPayment(value);
            if (!confirmedFullRefund(value, result)) {
                return result(
                        PaymentRefundAttemptOutcome.EXPLICIT_FAILURE,
                        "PROVIDER_REFUND_NOT_CONFIRMED",
                        providerType,
                        attemptNo);
            }
        } catch (MembershipPaymentException exception) {
            // 稳定超时错误码与受控超时异常链必须同时成立；任一证据缺失都不能授权下一次外部退款。
            boolean timeoutCode = exception.code() == MembershipPaymentErrorCode.LIUHAO_TIMEOUT
                    || exception.code() == MembershipPaymentErrorCode.BAR_TIMEOUT;
            boolean timedOut = timeoutCode && hasControlledTimeoutCause(exception);
            return result(
                    timedOut
                            ? PaymentRefundAttemptOutcome.TIMED_OUT
                            : PaymentRefundAttemptOutcome.EXPLICIT_FAILURE,
                    timeoutCode && !timedOut
                            ? "TIMEOUT_CAUSE_UNVERIFIED"
                            : exception.code().name(),
                    providerType,
                    attemptNo);
        } catch (RuntimeException exception) {
            return result(
                    PaymentRefundAttemptOutcome.EXPLICIT_FAILURE,
                    "UNCLASSIFIED_NON_TIMEOUT_FAILURE",
                    providerType,
                    attemptNo);
        }
        metrics.refundRequired();
        LOGGER.warn(
                "Membership payment refund request succeeded; traceId={} provider={}",
                MembershipPaymentTraceContext.currentTraceId(),
                providerType);
        return result(
                PaymentRefundAttemptOutcome.SUCCEEDED,
                "VERIFIED",
                providerType,
                attemptNo);
    }

    private static boolean confirmedFullRefund(
            PaymentRefundCommand command,
            PaymentRefundResult result) {
        return result != null
                && result.status() == PaymentProviderStatus.REFUNDED
                && Objects.equals(command.providerTradeNo(), result.providerTradeNo())
                && result.refundedAmountYuan() != null
                && command.amountYuan().compareTo(result.refundedAmountYuan()) == 0;
    }

    private static PaymentRefundAttemptResult result(
            PaymentRefundAttemptOutcome outcome,
            String safeReason,
            PaymentProviderType provider,
            int attemptNo) {
        return new PaymentRefundAttemptResult(outcome, safeReason, provider, attemptNo);
    }

    private static boolean hasControlledTimeoutCause(Throwable throwable) {
        Throwable current = throwable == null ? null : throwable.getCause();
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || "org.apache.hc.client5.http.ConnectTimeoutException"
                            .equals(current.getClass().getName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
