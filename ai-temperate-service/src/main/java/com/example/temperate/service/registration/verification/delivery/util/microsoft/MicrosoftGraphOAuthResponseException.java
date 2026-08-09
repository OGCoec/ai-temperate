package com.example.temperate.service.registration.verification.delivery.util.microsoft;

import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata;
import java.util.List;

/**
 * 保存 Microsoft OAuth 令牌端点允许进入诊断链路的机器可读失败字段。
 *
 * <p>该异常只接收 HTTP 状态、OAuth error、数字错误码和受控请求标识，不接收
 * error_description、原始响应体或任何令牌，从源头防止凭据进入异常与控制台日志。</p>
 */
final class MicrosoftGraphOAuthResponseException extends RuntimeException {

    private static final int MAX_ERROR_CODES = 8;
    private static final int MAX_ERROR_CODE = 9_999_999;
    private static final long MAX_RETRY_AFTER_SECONDS = 86_400L;

    private final int httpStatus;
    private final String oauthError;
    private final List<Integer> errorCodes;
    private final String requestId;
    private final Long retryAfterSeconds;

    MicrosoftGraphOAuthResponseException(
            int httpStatus,
            String oauthError,
            List<Integer> errorCodes,
            String requestId,
            Long retryAfterSeconds) {
        super("Microsoft OAuth token endpoint rejected the request.");
        this.httpStatus = httpStatus;
        this.oauthError = safeOptional(oauthError);
        this.errorCodes = safeErrorCodes(errorCodes);
        this.requestId = safeOptional(requestId);
        this.retryAfterSeconds = retryAfterSeconds != null
                        && retryAfterSeconds >= 0L
                        && retryAfterSeconds <= MAX_RETRY_AFTER_SECONDS
                ? retryAfterSeconds
                : null;
    }

    int httpStatus() {
        return httpStatus;
    }

    String oauthError() {
        return oauthError;
    }

    List<Integer> errorCodes() {
        return errorCodes;
    }

    String requestId() {
        return requestId;
    }

    Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public String toString() {
        return "MicrosoftGraphOAuthResponseException[safeMetadata=protected]";
    }

    private static String safeOptional(String value) {
        String sanitized = VerificationDeliveryProviderMetadata.sanitizeDiagnosticValue(value);
        return "unavailable".equals(sanitized) ? null : sanitized;
    }

    private static List<Integer> safeErrorCodes(List<Integer> errorCodes) {
        if (errorCodes == null || errorCodes.isEmpty()) {
            return List.of();
        }
        return errorCodes.stream()
                .filter(code -> code != null && code >= 0 && code <= MAX_ERROR_CODE)
                .limit(MAX_ERROR_CODES)
                .toList();
    }
}
