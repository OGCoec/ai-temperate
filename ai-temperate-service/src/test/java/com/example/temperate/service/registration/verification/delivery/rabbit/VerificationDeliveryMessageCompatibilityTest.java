package com.example.temperate.service.registration.verification.delivery.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationPurpose;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 验证 RabbitMQ 投递消息兼容 v1 缺失投递方式的载荷，并在重试和终态消息中保留 v2 投递方式。
 */
class VerificationDeliveryMessageCompatibilityTest {

    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");

    @Test
    void missingDeliveryMethodUsesChannelCompatibleDefault() throws Exception {
        VerificationDeliveryMessage email = deserializeLegacy(VerificationChannel.EMAIL);
        VerificationDeliveryMessage phone = deserializeLegacy(VerificationChannel.SMS);

        assertThat(email.deliveryMethod()).isEqualTo(VerificationDeliveryMethod.EMAIL);
        assertThat(phone.deliveryMethod()).isEqualTo(VerificationDeliveryMethod.SMS);
    }

    @Test
    void retryAndTerminalFailureRetainWhatsappDeliveryMethod() {
        VerificationDeliveryMessage whatsapp =
                message(2, VerificationChannel.SMS, VerificationDeliveryMethod.WHATSAPP);

        VerificationDeliveryMessage retry = whatsapp.nextAttempt("message-2", NOW.plusSeconds(10));
        VerificationDeliveryTerminalFailureMessage terminal =
                VerificationDeliveryTerminalFailureMessage.from(
                        whatsapp, "twilio_whatsapp", "provider_rejected", false, NOW);

        assertThat(retry.deliveryMethod()).isEqualTo(VerificationDeliveryMethod.WHATSAPP);
        assertThat(retry.schemaVersion()).isEqualTo(2);
        assertThat(terminal.deliveryMethod()).isEqualTo(VerificationDeliveryMethod.WHATSAPP);
        assertThat(terminal.schemaVersion()).isEqualTo(2);

        VerificationDeliveryTerminalFailureMessage unknown =
                VerificationDeliveryTerminalFailureMessage.from(
                        whatsapp,
                        "twilio_whatsapp",
                        "twilio_whatsapp_outcome_unknown",
                        false,
                        NOW);
        assertThat(unknown.outcome()).isEqualTo(VerificationDeliveryOutcome.UNKNOWN);
    }

    @Test
    void retryUpgradesLegacyMessageToSchemaV2WithInferredMethod() {
        VerificationDeliveryMessage legacy = message(1, VerificationChannel.SMS, null);

        VerificationDeliveryMessage retry = legacy.nextAttempt(
                "message-2", NOW.plusSeconds(10));

        assertThat(retry.schemaVersion()).isEqualTo(2);
        assertThat(retry.deliveryMethod()).isEqualTo(VerificationDeliveryMethod.SMS);
    }

    private static VerificationDeliveryMessage message(
            int schemaVersion,
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod) {
        return new VerificationDeliveryMessage(
                "message-1",
                VerificationDeliveryRabbitNames.EVENT_TYPE,
                schemaVersion,
                NOW,
                "trace-1",
                VerificationDeliveryFlowKind.REGISTRATION,
                channel,
                deliveryMethod,
                VerificationPurpose.REGISTRATION,
                "operation-1",
                1,
                6,
                NOW.plusSeconds(300),
                "flow-id",
                "csrf-hash",
                "challenge-id",
                "device-hash",
                "global-device-hash",
                "ip-hash",
                "email-code-id",
                "phone-code-id",
                null,
                null,
                "protected-payload");
    }

    private static VerificationDeliveryMessage deserializeLegacy(
            VerificationChannel channel) throws Exception {
        String json = """
                {
                  "messageId": "message-1",
                  "eventType": "auth.verification.delivery.requested",
                  "schemaVersion": 1,
                  "occurredAt": "2026-07-20T12:00:00Z",
                  "traceId": "trace-1",
                  "flowKind": "REGISTRATION",
                  "channel": "%s",
                  "purpose": "REGISTRATION",
                  "operationId": "operation-1",
                  "attemptNo": 1,
                  "maxAttempts": 6,
                  "codeExpiresAt": "2026-07-20T12:05:00Z",
                  "protectedPayload": "protected-payload"
                }
                """.formatted(channel.name());
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .readValue(json, VerificationDeliveryMessage.class);
    }
}
