package com.example.temperate.service.registration.verification.delivery.rabbit;

import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationPurpose;
import java.time.Instant;
import java.util.Objects;

/**
 * 表示一次可被 RabbitMQ 延迟重试的验证码投递任务。
 *
 * <p>消息体只包含 HMAC 后的流程标识和加密 payload；验证码、完整邮箱和手机号只存在于
 * protectedPayload 解密后的短暂内存对象中。</p>
 */
public record VerificationDeliveryMessage(
        String messageId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String traceId,
        VerificationDeliveryFlowKind flowKind,
        VerificationChannel channel,
        VerificationDeliveryMethod deliveryMethod,
        VerificationPurpose purpose,
        String operationId,
        int attemptNo,
        int maxAttempts,
        Instant codeExpiresAt,
        String flowId,
        String flowCsrfHash,
        String challengeId,
        String deviceHash,
        String globalDeviceHash,
        String ipHash,
        String emailCodeId,
        String phoneCodeId,
        String codeId,
        String targetHash,
        String protectedPayload) {

    public VerificationDeliveryMessage {
        requireText(messageId, "messageId");
        requireText(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        requireText(traceId, "traceId");
        Objects.requireNonNull(flowKind, "flowKind must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        // v1 消息没有 deliveryMethod；按逻辑渠道推断可保证滚动部署期间仍能消费存量消息。
        deliveryMethod = deliveryMethod == null
                ? VerificationDeliveryMethod.defaultFor(channel)
                : deliveryMethod;
        if ((channel == VerificationChannel.EMAIL)
                != (deliveryMethod == VerificationDeliveryMethod.EMAIL)) {
            throw new IllegalArgumentException(
                    "deliveryMethod must belong to the verification channel");
        }
        Objects.requireNonNull(purpose, "purpose must not be null");
        requireText(operationId, "operationId");
        if (attemptNo <= 0 || maxAttempts <= 0 || attemptNo > maxAttempts) {
            throw new IllegalArgumentException("attemptNo must be within maxAttempts");
        }
        Objects.requireNonNull(codeExpiresAt, "codeExpiresAt must not be null");
        requireText(protectedPayload, "protectedPayload");
    }

    /**
     * 保留 v1 构造契约，旧生产者和既有测试未传投递方式时按逻辑渠道采用兼容默认值。
     */
    public VerificationDeliveryMessage(
            String messageId,
            String eventType,
            int schemaVersion,
            Instant occurredAt,
            String traceId,
            VerificationDeliveryFlowKind flowKind,
            VerificationChannel channel,
            VerificationPurpose purpose,
            String operationId,
            int attemptNo,
            int maxAttempts,
            Instant codeExpiresAt,
            String flowId,
            String flowCsrfHash,
            String challengeId,
            String deviceHash,
            String globalDeviceHash,
            String ipHash,
            String emailCodeId,
            String phoneCodeId,
            String codeId,
            String targetHash,
            String protectedPayload) {
        this(
                messageId,
                eventType,
                schemaVersion,
                occurredAt,
                traceId,
                flowKind,
                channel,
                VerificationDeliveryMethod.defaultFor(channel),
                purpose,
                operationId,
                attemptNo,
                maxAttempts,
                codeExpiresAt,
                flowId,
                flowCsrfHash,
                challengeId,
                deviceHash,
                globalDeviceHash,
                ipHash,
                emailCodeId,
                phoneCodeId,
                codeId,
                targetHash,
                protectedPayload);
    }

    public VerificationDeliveryMessage nextAttempt(String nextMessageId, Instant occurredAt) {
        // 旧消息一旦进入新消费者即升级为 v2，并显式写回推断后的投递方式，避免后续重试继续依赖缺省规则。
        return new VerificationDeliveryMessage(
                nextMessageId,
                eventType,
                VerificationDeliveryRabbitNames.SCHEMA_VERSION,
                occurredAt,
                traceId,
                flowKind,
                channel,
                deliveryMethod,
                purpose,
                operationId,
                attemptNo + 1,
                maxAttempts,
                codeExpiresAt,
                flowId,
                flowCsrfHash,
                challengeId,
                deviceHash,
                globalDeviceHash,
                ipHash,
                emailCodeId,
                phoneCodeId,
                codeId,
                targetHash,
                protectedPayload);
    }

    @Override
    public String toString() {
        return "VerificationDeliveryMessage[messageId=" + messageId
                + ", traceId=" + traceId
                + ", flowKind=" + flowKind
                + ", channel=" + channel
                + ", deliveryMethod=" + deliveryMethod
                + ", purpose=" + purpose
                + ", attemptNo=" + attemptNo
                + ", maxAttempts=" + maxAttempts
                + ", protectedPayload=redacted]";
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
