package com.example.temperate.service.registration.verification.delivery.classification;

import com.example.temperate.common.aliyun.AliyunUtils;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureCategory;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureHint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureStage;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.RecommendedAction;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.SSLHandshakeException;
import org.springframework.stereotype.Component;

/**
 * 将阿里云短信的受控响应字段和传输异常映射为有限重试决策，防止业务拒绝被 RabbitMQ 反复放大。
 *
 * <p>分类器不读取供应商原始 Message，也不接收手机号或验证码。只有能够确认发生在请求发送前或协议握手阶段的
 * 瞬态故障才允许有限重试；可能已经到达供应商却未收到响应的异常统一标记为结果未知并停止自动重试。</p>
 */
@Component
public final class AliyunSmsFailureClassifier {

    private static final Set<String> RETRYABLE_PROVIDER_CODES = Set.of(
            "ISP.SYSTEM_ERROR",
            "ISP.UNKNOWN",
            "SYSTEM_ERROR",
            "UNKNOWN");

    private static final Set<String> NON_RETRYABLE_THROTTLE_CODES = Set.of(
            "BIZ.FREQUENCY",
            "FREQUENCY_FAIL",
            "ISV.BUSINESS_LIMIT_CONTROL",
            "BUSINESS_LIMIT_CONTROL");

    private static final Set<String> NON_RETRYABLE_PARAMETER_CODES = Set.of(
            "ISV.INVALID_PARAMETERS",
            "INVALID_PARAMETERS",
            "ISV.MOBILE_NUMBER_ILLEGAL",
            "MOBILE_NUMBER_ILLEGAL",
            "INVALID_MOBILE",
            "INVALID_PHONE_NUMBER");

    /**
     * 根据有限响应字段判定业务拒绝是否适合自动重试；未知的 {@code success=false} 按不可重试处理。
     */
    public FailureDecision classifyResult(AliyunUtils.SmsSendResult result) {
        if (result == null) {
            return outcomeUnknown("sms_delivery_outcome_unknown");
        }
        if (result.accepted()) {
            return new FailureDecision(
                    false,
                    "accepted",
                    FailureStage.PROVIDER_API,
                    null,
                    null,
                    null);
        }

        String providerCode = normalizeCode(result.providerCode());
        if (NON_RETRYABLE_THROTTLE_CODES.contains(providerCode) || result.httpStatus() != null
                && result.httpStatus() == 429) {
            return nonRetryable(
                    "sms_provider_frequency_limited",
                    FailureCategory.THROTTLED,
                    FailureHint.PROVIDER_RATE_LIMITED,
                    RecommendedAction.STOP_AUTOMATIC_RETRY);
        }
        if (NON_RETRYABLE_PARAMETER_CODES.contains(providerCode)) {
            return nonRetryable(
                    "sms_invalid_request",
                    FailureCategory.INVALID_REQUEST,
                    FailureHint.SMS_PROVIDER_CONFIGURATION_INVALID,
                    RecommendedAction.VERIFY_SMS_PROVIDER_CONFIGURATION);
        }
        if (RETRYABLE_PROVIDER_CODES.contains(providerCode)
                || isServerError(result.httpStatus())) {
            return retryable(
                    "sms_provider_transient_failure",
                    FailureStage.PROVIDER_API,
                    FailureCategory.TRANSIENT_PROVIDER_FAILURE,
                    FailureHint.PROVIDER_TEMPORARILY_UNAVAILABLE,
                    RecommendedAction.RETRY_WITH_BACKOFF);
        }
        if (isConfigurationCode(providerCode)) {
            return nonRetryable(
                    "sms_provider_configuration_error",
                    FailureCategory.CONFIGURATION_ERROR,
                    FailureHint.SMS_PROVIDER_CONFIGURATION_INVALID,
                    RecommendedAction.VERIFY_SMS_PROVIDER_CONFIGURATION);
        }
        if (isClientError(result.httpStatus())) {
            return nonRetryable(
                    "sms_provider_request_rejected",
                    FailureCategory.INVALID_REQUEST,
                    FailureHint.PROVIDER_REJECTED_REQUEST,
                    RecommendedAction.STOP_AUTOMATIC_RETRY);
        }
        if (providerCode.isEmpty() && result.providerSuccess() == null) {
            return outcomeUnknown("sms_delivery_outcome_unknown");
        }
        return nonRetryable(
                "sms_provider_business_rejected",
                FailureCategory.UNCLASSIFIED_PROVIDER_ERROR,
                FailureHint.PROVIDER_ERROR_NOT_CLASSIFIED,
                RecommendedAction.INSPECT_STATUS_CODE_AND_REQUEST_ID);
    }

    /**
     * 遍历包装异常的 cause 链，优先识别结果未知型 I/O，再识别明确的建连、DNS、TLS 与 ALPN 握手失败。
     */
    public FailureDecision classifyFailure(Throwable failure) {
        if (failure == null) {
            return outcomeUnknown("sms_delivery_outcome_unknown");
        }

        for (Throwable current = failure; current != null; current = nextCause(current)) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return nonRetryable(
                        "sms_delivery_interrupted",
                        FailureCategory.OUTCOME_UNKNOWN,
                        FailureHint.SMS_DELIVERY_OUTCOME_UNKNOWN,
                        RecommendedAction.INSPECT_DELIVERY_BEFORE_RETRY);
            }
            if (current instanceof IllegalArgumentException) {
                return nonRetryable(
                        "sms_invalid_request",
                        FailureCategory.INVALID_REQUEST,
                        FailureHint.SMS_PROVIDER_CONFIGURATION_INVALID,
                        RecommendedAction.VERIFY_SMS_PROVIDER_CONFIGURATION);
            }
            if (isConnectionTimeout(current)) {
                return transportRetryable("sms_connect_timeout");
            }
            if (current instanceof SocketTimeoutException
                    && isConnectTimeoutMessage(current.getMessage())) {
                return transportRetryable("sms_connect_timeout");
            }
            if (current instanceof SocketTimeoutException) {
                return outcomeUnknown("sms_delivery_outcome_unknown");
            }
            if (current instanceof EOFException || isConnectionReset(current)) {
                return outcomeUnknown("sms_delivery_outcome_unknown");
            }
            if (current instanceof SSLHandshakeException
                    || current instanceof UnknownHostException
                    || current instanceof ConnectException
                    || current instanceof NoRouteToHostException
                    || hasSimpleName(current, "ProtocolNegotiationException")) {
                return transportRetryable("sms_transport_handshake_failed");
            }
        }
        return outcomeUnknown("sms_delivery_outcome_unknown");
    }

    private static FailureDecision transportRetryable(String safeReason) {
        return retryable(
                safeReason,
                FailureStage.TRANSPORT,
                FailureCategory.TRANSPORT_FAILURE,
                FailureHint.PROVIDER_CONNECTION_FAILED,
                RecommendedAction.CHECK_NETWORK_AND_RETRY);
    }

    private static FailureDecision outcomeUnknown(String safeReason) {
        return new FailureDecision(
                false,
                safeReason,
                FailureStage.TIMEOUT,
                FailureCategory.OUTCOME_UNKNOWN,
                FailureHint.SMS_DELIVERY_OUTCOME_UNKNOWN,
                RecommendedAction.INSPECT_DELIVERY_BEFORE_RETRY);
    }

    private static FailureDecision nonRetryable(
            String safeReason,
            FailureCategory category,
            FailureHint hint,
            RecommendedAction action) {
        return new FailureDecision(
                false,
                safeReason,
                FailureStage.PROVIDER_API,
                category,
                hint,
                action);
    }

    private static FailureDecision retryable(
            String safeReason,
            FailureStage stage,
            FailureCategory category,
            FailureHint hint,
            RecommendedAction action) {
        return new FailureDecision(true, safeReason, stage, category, hint, action);
    }

    private static String normalizeCode(String providerCode) {
        return providerCode == null ? "" : providerCode.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isServerError(Integer httpStatus) {
        return httpStatus != null && httpStatus >= 500 && httpStatus <= 599;
    }

    private static boolean isClientError(Integer httpStatus) {
        return httpStatus != null && httpStatus >= 400 && httpStatus <= 499;
    }

    private static boolean isConfigurationCode(String code) {
        return containsAny(
                code,
                "SIGN",
                "TEMPLATE",
                "PERMISSION",
                "AUTH",
                "ACCOUNT",
                "PRODUCT",
                "NOT_OPEN",
                "FORBIDDEN",
                "DENIED");
    }

    private static boolean containsAny(String value, String... markers) {
        for (String marker : markers) {
            if (value.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConnectionTimeout(Throwable failure) {
        String simpleName = failure.getClass().getSimpleName();
        return "ConnectTimeoutException".equals(simpleName)
                || "ConnectionRequestTimeoutException".equals(simpleName);
    }

    private static boolean isConnectTimeoutMessage(String message) {
        return message != null
                && message.toLowerCase(Locale.ROOT).contains("connect");
    }

    private static boolean isConnectionReset(Throwable failure) {
        if (!(failure instanceof SocketException)) {
            return false;
        }
        String message = failure.getMessage();
        if (message == null) {
            return true;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("reset")
                || normalized.contains("broken pipe")
                || normalized.contains("closed");
    }

    private static boolean hasSimpleName(Throwable failure, String simpleName) {
        return simpleName.equals(failure.getClass().getSimpleName());
    }

    private static Throwable nextCause(Throwable failure) {
        Throwable cause = failure.getCause();
        return cause == failure ? null : cause;
    }

    /**
     * 保存供 Service 构造安全异常元数据所需的有限分类结果，不携带供应商原始错误文本。
     */
    public record FailureDecision(
            boolean retryable,
            String safeReason,
            FailureStage failureStage,
            FailureCategory failureCategory,
            FailureHint failureHint,
            RecommendedAction recommendedAction) {
    }
}
