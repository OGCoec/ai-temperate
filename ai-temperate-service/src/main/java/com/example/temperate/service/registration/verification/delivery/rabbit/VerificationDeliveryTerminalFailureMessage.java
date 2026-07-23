package com.example.temperate.service.registration.verification.delivery.rabbit;

import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationPurpose;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryOutcome;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 表示验证码投递已经不可继续自动重试的终态审计消息，并保留原任务的受保护载荷以支持受控人工排查。
 *
 * <p>消息 ID 固定复用原投递消息 ID，供下游执行幂等识别；消息不保存第三方原始响应，也不解密手机号、邮箱或验证码。</p>
 */
public record VerificationDeliveryTerminalFailureMessage(
        String messageId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String traceId,
        VerificationDeliveryFlowKind flowKind,
        VerificationChannel channel,
        VerificationDeliveryMethod deliveryMethod,
        VerificationPurpose purpose,
        int attemptNo,
        int maxAttempts,
        String provider,
        String safeReason,
        boolean retryable,
        VerificationDeliveryOutcome outcome,
        String protectedPayload) {

    private static final Pattern SAFE_VALUE = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    public VerificationDeliveryTerminalFailureMessage {
        requireSafeText(messageId, "messageId");
        requireSafeText(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        requireSafeText(traceId, "traceId");
        Objects.requireNonNull(flowKind, "flowKind must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        deliveryMethod = deliveryMethod == null
                ? VerificationDeliveryMethod.defaultFor(channel)
                : deliveryMethod;
        if ((channel == VerificationChannel.EMAIL)
                != (deliveryMethod == VerificationDeliveryMethod.EMAIL)) {
            throw new IllegalArgumentException(
                    "deliveryMethod must belong to the verification channel");
        }
        Objects.requireNonNull(purpose, "purpose must not be null");
        if (attemptNo <= 0 || maxAttempts <= 0 || attemptNo > maxAttempts) {
            throw new IllegalArgumentException("attemptNo must be within maxAttempts");
        }
        requireSafeText(provider, "provider");
        requireSafeText(safeReason, "safeReason");
        outcome = outcome == null ? deriveOutcome(safeReason, retryable) : outcome;
        if (outcome == VerificationDeliveryOutcome.ACCEPTED
                || (outcome == VerificationDeliveryOutcome.UNKNOWN && retryable)) {
            throw new IllegalArgumentException("Terminal outcome and retryable flag are inconsistent");
        }
        if (protectedPayload == null || protectedPayload.isBlank()) {
            throw new IllegalArgumentException("protectedPayload must not be blank");
        }
    }

    /** 兼容已有终态消息构造方，未知结果只由固定安全原因推导。 */
    public VerificationDeliveryTerminalFailureMessage(
            String messageId,
            String eventType,
            int schemaVersion,
            Instant occurredAt,
            String traceId,
            VerificationDeliveryFlowKind flowKind,
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod,
            VerificationPurpose purpose,
            int attemptNo,
            int maxAttempts,
            String provider,
            String safeReason,
            boolean retryable,
            String protectedPayload) {
        this(messageId, eventType, schemaVersion, occurredAt, traceId, flowKind, channel,
                deliveryMethod, purpose, attemptNo, maxAttempts, provider, safeReason,
                retryable, deriveOutcome(safeReason, retryable), protectedPayload);
    }

    /**
     * 从原投递消息建立终态消息，保留同一 messageId 作为跨重复发布的幂等标识。
     */
    public static VerificationDeliveryTerminalFailureMessage from(
            VerificationDeliveryMessage original,
            String provider,
            String safeReason,
            boolean retryable,
            Instant occurredAt) {
        return from(original, provider, safeReason, deriveOutcome(safeReason, retryable),
                retryable, occurredAt);
    }

    /** 创建带显式三态结果的终态审计消息，避免仅靠安全原因字符串推导业务语义。 */
    public static VerificationDeliveryTerminalFailureMessage from(
            VerificationDeliveryMessage original,
            String provider,
            String safeReason,
            VerificationDeliveryOutcome outcome,
            boolean retryable,
            Instant occurredAt) {
        Objects.requireNonNull(original, "original must not be null");
        return new VerificationDeliveryTerminalFailureMessage(
                original.messageId(),
                VerificationDeliveryRabbitNames.TERMINAL_EVENT_TYPE,
                VerificationDeliveryRabbitNames.TERMINAL_SCHEMA_VERSION,
                occurredAt,
                original.traceId(),
                original.flowKind(),
                original.channel(),
                original.deliveryMethod(),
                original.purpose(),
                original.attemptNo(),
                original.maxAttempts(),
                provider,
                safeReason,
                retryable,
                outcome,
                original.protectedPayload());
    }

    @Override
    public String toString() {
        return "VerificationDeliveryTerminalFailureMessage[messageId=" + messageId
                + ", traceId=" + traceId
                + ", flowKind=" + flowKind
                + ", channel=" + channel
                + ", deliveryMethod=" + deliveryMethod
                + ", purpose=" + purpose
                + ", attemptNo=" + attemptNo
                + ", maxAttempts=" + maxAttempts
                + ", provider=" + provider
                + ", safeReason=" + safeReason
                + ", retryable=" + retryable
                + ", outcome=" + outcome
                + ", protectedPayload=redacted]";
    }

    private static VerificationDeliveryOutcome deriveOutcome(String safeReason, boolean retryable) {
        return !retryable && ("twilio_whatsapp_outcome_unknown".equals(safeReason)
                || "twilio_whatsapp_response_missing_sid".equals(safeReason)
                || "twilio_whatsapp_unrecognized_status".equals(safeReason)
                || "twilio_whatsapp_transport_outcome_unknown".equals(safeReason)
                || "verification_delivery_outcome_unknown".equals(safeReason)
                || "verification_delivery_empty_result".equals(safeReason))
                ? VerificationDeliveryOutcome.UNKNOWN
                : VerificationDeliveryOutcome.EXPLICIT_FAILURE;
    }

    private static void requireSafeText(String value, String name) {
        if (value == null || !SAFE_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a safe diagnostic value");
        }
    }
}
