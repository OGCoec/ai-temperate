package com.example.temperate.service.registration.verification.delivery.util.microsoft;

import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureCategory;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureHint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureStage;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.RecommendedAction;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * 将 Microsoft Graph 的稳定状态码和机器可读错误码映射为有限的安全诊断分类。
 *
 * <p>该分类器不读取错误消息、响应体或请求内容；输出的提示只用于确定排查方向，不代表在缺少
 * 账号和邮箱状态证据时已经确认唯一根因。</p>
 */
final class MicrosoftGraphFailureClassifier {

    private MicrosoftGraphFailureClassifier() {
    }

    static Classification classify(
            Integer httpStatus,
            String providerCode,
            Throwable failure) {
        if (hasCode(providerCode, "ErrorInvalidUser")) {
            return classification(
                    FailureStage.PROVIDER_API,
                    FailureCategory.IDENTITY_RESOLUTION,
                    FailureHint.GRAPH_RESOURCE_NOT_RESOLVED,
                    RecommendedAction.VERIFY_AUTHENTICATED_GRAPH_USER);
        }
        if (hasCode(providerCode, "ErrorSendAsDenied")) {
            return classification(
                    FailureStage.PROVIDER_API,
                    FailureCategory.SENDER_AUTHORIZATION,
                    FailureHint.EXPLICIT_SENDER_NOT_AUTHORIZED,
                    RecommendedAction.REMOVE_OR_AUTHORIZE_EXPLICIT_SENDER);
        }
        if (hasCode(providerCode, "Authorization_RequestDenied")) {
            return classification(
                    FailureStage.PROVIDER_API,
                    FailureCategory.PERMISSION_DENIED,
                    FailureHint.GRAPH_PERMISSION_OR_CONSENT_MISSING,
                    RecommendedAction.VERIFY_MAIL_SEND_CONSENT);
        }
        if (hasCode(providerCode, "ErrorAccessDenied")) {
            return classification(
                    FailureStage.PROVIDER_API,
                    FailureCategory.PERMISSION_DENIED,
                    FailureHint.RESOURCE_ACCESS_DENIED,
                    RecommendedAction.VERIFY_ACCOUNT_PERMISSION_AND_LICENSE);
        }
        if (hasCode(providerCode, "InvalidAuthenticationToken")
                || Integer.valueOf(401).equals(httpStatus)) {
            return classification(
                    FailureStage.AUTHENTICATION,
                    FailureCategory.AUTHENTICATION_FAILED,
                    FailureHint.TOKEN_REJECTED_AFTER_REFRESH,
                    RecommendedAction.REAUTHORIZE_MICROSOFT_ACCOUNT);
        }
        if (hasCode(providerCode, "MailboxNotEnabledForRESTAPI")) {
            return classification(
                    FailureStage.PROVIDER_API,
                    FailureCategory.MAILBOX_UNAVAILABLE,
                    FailureHint.MAILBOX_NOT_AVAILABLE_TO_GRAPH,
                    RecommendedAction.VERIFY_OUTLOOK_MAILBOX_STATE);
        }
        if (hasCause(failure, TimeoutException.class)) {
            return classification(
                    FailureStage.TIMEOUT,
                    FailureCategory.TIMEOUT,
                    FailureHint.PROVIDER_REQUEST_TIMED_OUT,
                    RecommendedAction.CHECK_TIMEOUT_AND_RETRY);
        }
        if (hasCause(failure, IOException.class)) {
            return classification(
                    FailureStage.TRANSPORT,
                    FailureCategory.TRANSPORT_FAILURE,
                    FailureHint.PROVIDER_CONNECTION_FAILED,
                    RecommendedAction.CHECK_NETWORK_AND_RETRY);
        }
        if (Integer.valueOf(400).equals(httpStatus)) {
            return classification(
                    FailureStage.PROVIDER_API,
                    FailureCategory.INVALID_REQUEST,
                    FailureHint.PROVIDER_REJECTED_REQUEST,
                    RecommendedAction.VERIFY_GRAPH_REQUEST_CONFIGURATION);
        }
        if (Integer.valueOf(403).equals(httpStatus)) {
            return classification(
                    FailureStage.PROVIDER_API,
                    FailureCategory.PERMISSION_DENIED,
                    FailureHint.RESOURCE_ACCESS_DENIED,
                    RecommendedAction.VERIFY_ACCOUNT_PERMISSION_AND_LICENSE);
        }
        if (Integer.valueOf(404).equals(httpStatus)) {
            return classification(
                    FailureStage.PROVIDER_API,
                    FailureCategory.RESOURCE_NOT_FOUND,
                    FailureHint.GRAPH_RESOURCE_NOT_RESOLVED,
                    RecommendedAction.VERIFY_AUTHENTICATED_GRAPH_USER);
        }
        if (Integer.valueOf(429).equals(httpStatus)) {
            return classification(
                    FailureStage.PROVIDER_API,
                    FailureCategory.THROTTLED,
                    FailureHint.PROVIDER_RATE_LIMITED,
                    RecommendedAction.RETRY_AFTER_PROVIDER_DELAY);
        }
        if (Integer.valueOf(408).equals(httpStatus)
                || httpStatus != null && httpStatus >= 500) {
            return classification(
                    FailureStage.PROVIDER_API,
                    FailureCategory.TRANSIENT_PROVIDER_FAILURE,
                    FailureHint.PROVIDER_TEMPORARILY_UNAVAILABLE,
                    RecommendedAction.RETRY_WITH_BACKOFF);
        }
        return classification(
                FailureStage.PROVIDER_API,
                FailureCategory.UNCLASSIFIED_PROVIDER_ERROR,
                FailureHint.PROVIDER_ERROR_NOT_CLASSIFIED,
                RecommendedAction.INSPECT_STATUS_CODE_AND_REQUEST_ID);
    }

    /**
     * 对 refresh token 交换阶段单独分类，避免把 OAuth 端点失败误报成 sendMail 请求错误。
     */
    static Classification classifyOAuth(
            Integer httpStatus,
            Throwable failure) {
        if (Integer.valueOf(400).equals(httpStatus)
                || Integer.valueOf(401).equals(httpStatus)) {
            return classification(
                    FailureStage.AUTHENTICATION,
                    FailureCategory.AUTHENTICATION_FAILED,
                    FailureHint.OAUTH_CREDENTIAL_OR_REFRESH_TOKEN_REJECTED,
                    RecommendedAction.REAUTHORIZE_OR_VERIFY_CLIENT_CREDENTIALS);
        }
        if (Integer.valueOf(429).equals(httpStatus)) {
            return classification(
                    FailureStage.AUTHENTICATION,
                    FailureCategory.THROTTLED,
                    FailureHint.PROVIDER_RATE_LIMITED,
                    RecommendedAction.RETRY_AFTER_PROVIDER_DELAY);
        }
        if (Integer.valueOf(408).equals(httpStatus)
                || httpStatus != null && httpStatus >= 500) {
            return classification(
                    FailureStage.AUTHENTICATION,
                    FailureCategory.TRANSIENT_PROVIDER_FAILURE,
                    FailureHint.PROVIDER_TEMPORARILY_UNAVAILABLE,
                    RecommendedAction.RETRY_WITH_BACKOFF);
        }
        if (hasCause(failure, TimeoutException.class)) {
            return classification(
                    FailureStage.TIMEOUT,
                    FailureCategory.TIMEOUT,
                    FailureHint.PROVIDER_REQUEST_TIMED_OUT,
                    RecommendedAction.CHECK_TIMEOUT_AND_RETRY);
        }
        if (hasCause(failure, IOException.class)) {
            return classification(
                    FailureStage.TRANSPORT,
                    FailureCategory.TRANSPORT_FAILURE,
                    FailureHint.PROVIDER_CONNECTION_FAILED,
                    RecommendedAction.CHECK_NETWORK_AND_RETRY);
        }
        return classification(
                FailureStage.AUTHENTICATION,
                FailureCategory.UNCLASSIFIED_PROVIDER_ERROR,
                FailureHint.PROVIDER_ERROR_NOT_CLASSIFIED,
                RecommendedAction.INSPECT_STATUS_CODE_AND_REQUEST_ID);
    }

    private static boolean hasCode(String actualCode, String expectedCode) {
        return actualCode != null && expectedCode.equalsIgnoreCase(actualCode);
    }

    private static boolean hasCause(
            Throwable failure,
            Class<? extends Throwable> expectedType) {
        Throwable current = failure;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }

    private static Classification classification(
            FailureStage failureStage,
            FailureCategory failureCategory,
            FailureHint failureHint,
            RecommendedAction recommendedAction) {
        return new Classification(
                failureStage,
                failureCategory,
                failureHint,
                recommendedAction);
    }

    /**
     * 保存一次 Graph 失败的受控分类结果，供适配器装配统一供应商元数据。
     */
    record Classification(
            FailureStage failureStage,
            FailureCategory failureCategory,
            FailureHint failureHint,
            RecommendedAction recommendedAction) {

        Classification {
            Objects.requireNonNull(failureStage, "failureStage must not be null");
            Objects.requireNonNull(failureCategory, "failureCategory must not be null");
            Objects.requireNonNull(failureHint, "failureHint must not be null");
            Objects.requireNonNull(recommendedAction, "recommendedAction must not be null");
        }
    }
}
