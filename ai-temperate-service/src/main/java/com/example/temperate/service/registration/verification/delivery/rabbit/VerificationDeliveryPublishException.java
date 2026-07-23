package com.example.temperate.service.registration.verification.delivery.rabbit;

/**
 * 表示验证码投递消息发布到 RabbitMQ 时未收到可靠确认。
 */
public final class VerificationDeliveryPublishException extends RuntimeException {

    public VerificationDeliveryPublishException(String message, Throwable cause) {
        super(message, cause);
    }

    public VerificationDeliveryPublishException(String message) {
        super(message);
    }
}
