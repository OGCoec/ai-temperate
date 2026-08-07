package com.example.temperate.service.user.aiconversation.diagnostic;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 承载从上游非成功响应中提取且已经完成脱敏的有限诊断字段，供异常分类和 AOP 日志使用。
 *
 * <p>该类型不保存原始响应正文、请求内容或请求头；构造边界同时限制长度和字符集，防止其他调用方绕过
 * 捕获器把不可信供应商内容直接带入结构化日志。</p>
 */
public record AiUpstreamErrorDiagnostic(
        String providerCode,
        String providerType,
        String providerParam,
        String sanitizedMessage,
        String requestId,
        String contentType,
        String bodySha256,
        int capturedBytes,
        boolean truncated) {

    public static final String UNAVAILABLE = "unavailable";
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_MESSAGE_LENGTH = 512;
    private static final int MAX_CAPTURE_BYTES = 16 * 1024;
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile(
            "^[A-Za-z0-9._:+/\\-\\[\\]]{1," + MAX_IDENTIFIER_LENGTH + "}$");
    private static final Pattern SAFE_BODY_SHA256 =
            Pattern.compile("^[A-Za-z0-9_-]{43}$");

    public AiUpstreamErrorDiagnostic {
        providerCode = requireIdentifier(providerCode, "providerCode");
        providerType = requireIdentifier(providerType, "providerType");
        providerParam = requireIdentifier(providerParam, "providerParam");
        requestId = requireIdentifier(requestId, "requestId");
        contentType = requireIdentifier(contentType, "contentType");
        sanitizedMessage = requireMessage(sanitizedMessage);
        bodySha256 = Objects.requireNonNull(bodySha256, "bodySha256");
        if (!UNAVAILABLE.equals(bodySha256)
                && !SAFE_BODY_SHA256.matcher(bodySha256).matches()) {
            throw new IllegalArgumentException(
                    "bodySha256 must be unavailable or a SHA-256 Base64URL value");
        }
        if (capturedBytes < 0 || capturedBytes > MAX_CAPTURE_BYTES) {
            throw new IllegalArgumentException(
                    "capturedBytes must be between 0 and 16384");
        }
    }

    public static AiUpstreamErrorDiagnostic unavailable() {
        return new AiUpstreamErrorDiagnostic(
                UNAVAILABLE,
                UNAVAILABLE,
                UNAVAILABLE,
                UNAVAILABLE,
                UNAVAILABLE,
                UNAVAILABLE,
                UNAVAILABLE,
                0,
                false);
    }

    @Override
    public String toString() {
        return "AiUpstreamErrorDiagnostic[redacted]";
    }

    private static String requireIdentifier(String value, String field) {
        Objects.requireNonNull(value, field);
        if (UNAVAILABLE.equals(value)) {
            return value;
        }
        if (!SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " contains unsafe characters");
        }
        return value;
    }

    private static String requireMessage(String value) {
        Objects.requireNonNull(value, "sanitizedMessage");
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "sanitizedMessage must not be blank");
        }
        if (value.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException(
                    "sanitizedMessage exceeds the diagnostic limit");
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isISOControl(current)
                    || current == '\u2028'
                    || current == '\u2029') {
                throw new IllegalArgumentException(
                        "sanitizedMessage contains log control characters");
            }
        }
        return value;
    }
}
