package com.example.temperate.service.registration.verification.delivery.dto;

import java.time.Duration;
import java.util.Objects;

/**
 * 交给六位数验证码供应商 Service 的受保护请求对象。
 *
 * <p>用途：携带目标地址、六位验证码、业务用途及消费者计算出的剩余有效期；剩余有效期不进入受保护消息版本，
 * 只在实际消费时补充。重写 {@link #toString()} 以防日志、异常或调试输出泄露地址和验证码。</p>
 */
public record VerificationDeliveryRequest(
        String destination,
        String code,
        VerificationPurpose purpose,
        Duration validity) {

    public VerificationDeliveryRequest(String destination, String code) {
        this(destination, code, VerificationPurpose.REGISTRATION, null);
    }

    public VerificationDeliveryRequest(
            String destination, String code, VerificationPurpose purpose) {
        this(destination, code, purpose, null);
    }

    public VerificationDeliveryRequest {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        if (validity != null && (validity.isZero() || validity.isNegative())) {
            throw new IllegalArgumentException("validity must be positive when present");
        }
    }

    /**
     * 在消费者确认消息尚未过期后附加本次真实剩余时长，受保护消息体仍保持原有结构，避免破坏既有消息兼容性。
     */
    public VerificationDeliveryRequest withValidity(Duration remainingValidity) {
        return new VerificationDeliveryRequest(
                destination, code, purpose, Objects.requireNonNull(remainingValidity));
    }

    @Override
    public String toString() {
        return "VerificationDeliveryRequest[redacted]";
    }
}
