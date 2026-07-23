package com.example.temperate.service.registration.verification.delivery.exception;

import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata;

/**
 * 表示验证码供应商投递失败，并携带是否适合进入 RabbitMQ 延迟重试的判断。
 *
 * <p>该异常只在投递层和消费者内部流转，不继承注册业务异常，避免把供应商失败误建模为
 * Controller 可直接返回的注册流程错误；reason 只能使用固定安全分类，禁止放入验证码、
 * 完整目标地址、Token 或第三方原始响应体。</p>
 */
public final class VerificationDeliveryException extends RuntimeException {

    private final boolean retryable;
    private final VerificationDeliveryOutcome outcome;
    private final String provider;
    private final String safeReason;
    private final VerificationDeliveryProviderMetadata metadata;

    public VerificationDeliveryException(
            boolean retryable, String provider, String safeReason, Throwable cause) {
        this(
                VerificationDeliveryOutcome.EXPLICIT_FAILURE,
                retryable,
                provider,
                safeReason,
                VerificationDeliveryProviderMetadata.empty(),
                cause);
    }

    public VerificationDeliveryException(
            boolean retryable,
            String provider,
            String safeReason,
            VerificationDeliveryProviderMetadata metadata,
            Throwable cause) {
        this(
                VerificationDeliveryOutcome.EXPLICIT_FAILURE,
                retryable,
                provider,
                safeReason,
                metadata,
                cause);
    }

    public VerificationDeliveryException(
            VerificationDeliveryOutcome outcome,
            boolean retryable,
            String provider,
            String safeReason,
            Throwable cause) {
        this(outcome, retryable, provider, safeReason,
                VerificationDeliveryProviderMetadata.empty(), cause);
    }

    public VerificationDeliveryException(
            VerificationDeliveryOutcome outcome,
            boolean retryable,
            String provider,
            String safeReason,
            VerificationDeliveryProviderMetadata metadata,
            Throwable cause) {
        super("Verification delivery failed.", cause);
        this.outcome = requireOutcome(outcome);
        if (this.outcome == VerificationDeliveryOutcome.UNKNOWN && retryable) {
            throw new IllegalArgumentException("UNKNOWN delivery outcome cannot be retryable");
        }
        this.retryable = retryable;
        this.provider = requireText(provider, "provider");
        this.safeReason = requireText(safeReason, "safeReason");
        this.metadata = metadata == null
                ? VerificationDeliveryProviderMetadata.empty()
                : metadata;
    }

    public boolean retryable() {
        return retryable;
    }

    public VerificationDeliveryOutcome outcome() {
        return outcome;
    }

    public String provider() {
        return provider;
    }

    public String safeReason() {
        return safeReason;
    }

    public VerificationDeliveryProviderMetadata metadata() {
        return metadata;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static VerificationDeliveryOutcome requireOutcome(
            VerificationDeliveryOutcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        if (outcome == VerificationDeliveryOutcome.ACCEPTED) {
            throw new IllegalArgumentException("ACCEPTED cannot be represented by an exception");
        }
        return outcome;
    }
}
