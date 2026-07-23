package com.example.temperate.service.registration.verification.delivery.dto;

import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata;
import java.time.Instant;
import java.util.Objects;

/**
 * 表示验证码供应商已经接受一次投递请求的受控结果。
 *
 * <p>该结果只记录可观测且可脱敏的供应商信息，不携带验证码、完整邮箱或手机号；调用方据此确认
 * “供应商接受发送请求”，而不声明终端用户一定已经收到消息。</p>
 */
public record VerificationDeliveryResult(
        VerificationChannel channel,
        VerificationDeliveryMethod deliveryMethod,
        String provider,
        String providerMessageId,
        Instant acceptedAt,
        VerificationDeliveryProviderMetadata metadata) {

    public VerificationDeliveryResult(
            VerificationChannel channel,
            String provider,
            String providerMessageId,
            Instant acceptedAt) {
        this(
                channel,
                VerificationDeliveryMethod.defaultFor(channel),
                provider,
                providerMessageId,
                acceptedAt,
                VerificationDeliveryProviderMetadata.empty());
    }

    public VerificationDeliveryResult(
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod,
            String provider,
            String providerMessageId,
            Instant acceptedAt) {
        this(
                channel,
                deliveryMethod,
                provider,
                providerMessageId,
                acceptedAt,
                VerificationDeliveryProviderMetadata.empty());
    }

    public VerificationDeliveryResult(
            VerificationChannel channel,
            String provider,
            String providerMessageId,
            Instant acceptedAt,
            VerificationDeliveryProviderMetadata metadata) {
        this(
                channel,
                VerificationDeliveryMethod.defaultFor(channel),
                provider,
                providerMessageId,
                acceptedAt,
                metadata);
    }

    public VerificationDeliveryResult {
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(deliveryMethod, "deliveryMethod must not be null");
        if ((channel == VerificationChannel.EMAIL)
                != (deliveryMethod == VerificationDeliveryMethod.EMAIL)) {
            throw new IllegalArgumentException(
                    "deliveryMethod must belong to the verification channel");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
        metadata = metadata == null
                ? VerificationDeliveryProviderMetadata.empty()
                : metadata;
    }
}
