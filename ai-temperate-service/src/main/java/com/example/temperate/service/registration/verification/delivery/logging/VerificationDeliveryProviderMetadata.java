package com.example.temperate.service.registration.verification.delivery.logging;

import java.util.regex.Pattern;

/**
 * 保存验证码供应商允许进入诊断日志的有限响应元数据，并在对象边界统一执行长度与字符白名单校验。
 *
 * <p>该类型不接收原始响应体、错误消息或请求对象；任何空值、超长值或含非诊断字符的字符串都会被
 * 归一化为 {@code unavailable}，避免供应商返回内容把个人信息或控制字符带入日志。</p>
 */
public record VerificationDeliveryProviderMetadata(
        Integer httpStatus,
        String providerCode,
        String providerStatus,
        Boolean providerSuccess,
        String requestId,
        String exceptionClass,
        Operation operation,
        Endpoint endpoint,
        FailureStage failureStage,
        FailureCategory failureCategory,
        FailureHint failureHint,
        RecommendedAction recommendedAction,
        Boolean explicitFrom,
        Boolean authRefreshAttempted,
        Long retryAfterSeconds) {

    private static final int MAX_DIAGNOSTIC_LENGTH = 128;
    private static final long MAX_RETRY_AFTER_SECONDS = 86_400L;
    private static final String UNAVAILABLE = "unavailable";
    private static final Pattern SAFE_DIAGNOSTIC_VALUE =
            Pattern.compile("^[A-Za-z0-9._:-]{1," + MAX_DIAGNOSTIC_LENGTH + "}$");

    public VerificationDeliveryProviderMetadata {
        httpStatus = normalizeHttpStatus(httpStatus);
        providerCode = sanitizeDiagnosticValue(providerCode);
        providerStatus = sanitizeDiagnosticValue(providerStatus);
        requestId = sanitizeDiagnosticValue(requestId);
        exceptionClass = sanitizeDiagnosticValue(exceptionClass);
        retryAfterSeconds = normalizeRetryAfterSeconds(retryAfterSeconds);
    }

    /**
     * 保留原有供应商适配器使用的六参数构造方式，使新增诊断字段不会迫使其他供应商伪造不存在的信息。
     */
    public VerificationDeliveryProviderMetadata(
            Integer httpStatus,
            String providerCode,
            String providerStatus,
            Boolean providerSuccess,
            String requestId,
            String exceptionClass) {
        this(
                httpStatus,
                providerCode,
                providerStatus,
                providerSuccess,
                requestId,
                exceptionClass,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static VerificationDeliveryProviderMetadata empty() {
        return new VerificationDeliveryProviderMetadata(
                null, null, null, null, null, null);
    }

    /**
     * 仅在供应商没有提供异常类型时补充受控类名，同时完整保留已经完成的错误分类。
     */
    public VerificationDeliveryProviderMetadata withFallbackExceptionClass(
            String fallbackExceptionClass) {
        if (!UNAVAILABLE.equals(exceptionClass)) {
            return this;
        }
        return new VerificationDeliveryProviderMetadata(
                httpStatus,
                providerCode,
                providerStatus,
                providerSuccess,
                requestId,
                fallbackExceptionClass,
                operation,
                endpoint,
                failureStage,
                failureCategory,
                failureHint,
                recommendedAction,
                explicitFrom,
                authRefreshAttempted,
                retryAfterSeconds);
    }

    /**
     * 将准备写入日志的供应商标识收敛到固定字符集，防止换行、地址或原始错误文本进入结构化日志。
     */
    public static String sanitizeDiagnosticValue(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_DIAGNOSTIC_LENGTH) {
            return UNAVAILABLE;
        }
        return SAFE_DIAGNOSTIC_VALUE.matcher(value).matches() ? value : UNAVAILABLE;
    }

    private static Integer normalizeHttpStatus(Integer status) {
        return status != null && status >= 100 && status <= 599 ? status : null;
    }

    private static Long normalizeRetryAfterSeconds(Long retryAfterSeconds) {
        return retryAfterSeconds != null
                        && retryAfterSeconds >= 0L
                        && retryAfterSeconds <= MAX_RETRY_AFTER_SECONDS
                ? retryAfterSeconds
                : null;
    }

    /** 表示供应商执行的受控业务动作，禁止使用请求内容拼接动作名称。 */
    public enum Operation {
        SEND_SMS,
        SEND_WHATSAPP,
        SEND_MAIL,
        REFRESH_ACCESS_TOKEN
    }

    /** 表示供应商 API 的受控调用入口，不包含主机名、账号或请求参数。 */
    public enum Endpoint {
        ALIYUN_DYPNSAPI,
        TWILIO_MESSAGES,
        ME_SEND_MAIL,
        OAUTH_TOKEN
    }

    /** 表示失败发生的技术阶段，用于区分本地校验、认证、供应商处理和网络问题。 */
    public enum FailureStage {
        REQUEST_VALIDATION,
        AUTHENTICATION,
        PROVIDER_API,
        TRANSPORT,
        TIMEOUT
    }

    /** 表示由稳定状态码和错误码映射出的有限失败类别。 */
    public enum FailureCategory {
        IDENTITY_RESOLUTION,
        SENDER_AUTHORIZATION,
        PERMISSION_DENIED,
        AUTHENTICATION_FAILED,
        MAILBOX_UNAVAILABLE,
        INVALID_REQUEST,
        RESOURCE_NOT_FOUND,
        THROTTLED,
        TRANSIENT_PROVIDER_FAILURE,
        TRANSPORT_FAILURE,
        TIMEOUT,
        CONFIGURATION_ERROR,
        OUTCOME_UNKNOWN,
        UNCLASSIFIED_PROVIDER_ERROR
    }

    /** 表示不包含第三方原始文本的安全排查提示，不应被解释成已确认的唯一根因。 */
    public enum FailureHint {
        EXPLICIT_SENDER_NOT_AUTHORIZED,
        GRAPH_PERMISSION_OR_CONSENT_MISSING,
        RESOURCE_ACCESS_DENIED,
        TOKEN_REJECTED_AFTER_REFRESH,
        OAUTH_CREDENTIAL_OR_REFRESH_TOKEN_REJECTED,
        MAILBOX_NOT_AVAILABLE_TO_GRAPH,
        PROVIDER_REJECTED_REQUEST,
        GRAPH_RESOURCE_NOT_RESOLVED,
        PROVIDER_RATE_LIMITED,
        PROVIDER_TEMPORARILY_UNAVAILABLE,
        PROVIDER_CONNECTION_FAILED,
        PROVIDER_REQUEST_TIMED_OUT,
        SMS_PROVIDER_CONFIGURATION_INVALID,
        WHATSAPP_SANDBOX_NOT_JOINED,
        WHATSAPP_SENDER_OR_TEMPLATE_INVALID,
        SMS_DELIVERY_OUTCOME_UNKNOWN,
        PROVIDER_ERROR_NOT_CLASSIFIED
    }

    /** 表示与安全失败类别对应的固定排查动作，禁止携带动态账号或地址。 */
    public enum RecommendedAction {
        REMOVE_OR_AUTHORIZE_EXPLICIT_SENDER,
        VERIFY_MAIL_SEND_CONSENT,
        VERIFY_ACCOUNT_PERMISSION_AND_LICENSE,
        REAUTHORIZE_MICROSOFT_ACCOUNT,
        REAUTHORIZE_OR_VERIFY_CLIENT_CREDENTIALS,
        VERIFY_OUTLOOK_MAILBOX_STATE,
        VERIFY_GRAPH_REQUEST_CONFIGURATION,
        VERIFY_AUTHENTICATED_GRAPH_USER,
        RETRY_AFTER_PROVIDER_DELAY,
        RETRY_WITH_BACKOFF,
        CHECK_NETWORK_AND_RETRY,
        CHECK_TIMEOUT_AND_RETRY,
        VERIFY_SMS_PROVIDER_CONFIGURATION,
        JOIN_WHATSAPP_SANDBOX,
        VERIFY_WHATSAPP_SENDER_AND_TEMPLATE,
        STOP_AUTOMATIC_RETRY,
        INSPECT_DELIVERY_BEFORE_RETRY,
        INSPECT_STATUS_CODE_AND_REQUEST_ID
    }
}
