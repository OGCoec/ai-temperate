package com.example.temperate.service.registration.verification.delivery.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 验证投递结果会显式保留实际投递方式，同时兼容旧供应商仅声明逻辑渠道的构造方式。
 */
class VerificationDeliveryResultTest {

    private static final Instant ACCEPTED_AT = Instant.parse("2026-07-20T12:00:00Z");

    @Test
    void legacyPhoneResultDefaultsToSmsDelivery() {
        VerificationDeliveryResult result = new VerificationDeliveryResult(
                VerificationChannel.SMS, "provider", "message-id", ACCEPTED_AT);

        assertThat(result.deliveryMethod()).isEqualTo(VerificationDeliveryMethod.SMS);
    }

    @Test
    void whatsappResultRetainsPhoneChannelAndWhatsappDeliveryMethod() {
        VerificationDeliveryResult result = new VerificationDeliveryResult(
                VerificationChannel.SMS,
                VerificationDeliveryMethod.WHATSAPP,
                "twilio-whatsapp",
                "SMaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ACCEPTED_AT);

        assertThat(result.channel()).isEqualTo(VerificationChannel.SMS);
        assertThat(result.deliveryMethod()).isEqualTo(VerificationDeliveryMethod.WHATSAPP);
    }
}
