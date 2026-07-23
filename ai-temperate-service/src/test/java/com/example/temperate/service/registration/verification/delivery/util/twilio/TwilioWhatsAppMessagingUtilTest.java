package com.example.temperate.service.registration.verification.delivery.util.twilio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryException;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryOutcome;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureCategory;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureHint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureStage;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.RecommendedAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.twilio.exception.ApiException;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.Message;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 验证 Twilio WhatsApp 工具使用 Content Template 投递六位验证码，并执行受控错误分类而不连接真实网络。
 */
class TwilioWhatsAppMessagingUtilTest {

    private static final String FROM = "+14155238886";
    private static final String CONTENT_SID = "HXaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String MESSAGE_SID = "SMbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final Instant ACCEPTED_AT = Instant.parse("2026-07-20T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC);

    @Test
    void sendsVerificationTemplateToStrictE164WhatsappAddress() {
        AtomicReference<String> capturedTo = new AtomicReference<>();
        AtomicReference<String> capturedFrom = new AtomicReference<>();
        AtomicReference<String> capturedContentSid = new AtomicReference<>();
        AtomicReference<String> capturedVariables = new AtomicReference<>();
        TwilioWhatsAppMessagingUtil util = util((client, to, from, contentSid, variables, callback) -> {
            capturedTo.set(to);
            capturedFrom.set(from);
            capturedContentSid.set(contentSid);
            capturedVariables.set(variables);
            return message(MESSAGE_SID, Message.Status.QUEUED, null);
        });

        VerificationDeliveryResult result = util.sendVerificationCode(
                        new VerificationDeliveryRequest("+447911123456", "012345"))
                .block();

        assertThat(capturedTo.get()).isEqualTo("whatsapp:+447911123456");
        assertThat(capturedFrom.get()).isEqualTo("whatsapp:" + FROM);
        assertThat(capturedContentSid.get()).isEqualTo(CONTENT_SID);
        assertThat(capturedVariables.get()).isEqualTo("{\"1\":\"012345\"}");
        assertThat(result).isNotNull();
        assertThat(result.channel()).isEqualTo(VerificationChannel.SMS);
        assertThat(result.deliveryMethod()).isEqualTo(VerificationDeliveryMethod.WHATSAPP);
        assertThat(result.provider()).isEqualTo("twilio-whatsapp");
        assertThat(result.providerMessageId()).isEqualTo(MESSAGE_SID);
        assertThat(result.acceptedAt()).isEqualTo(ACCEPTED_AT);
    }

    @Test
    void treatsProviderAcceptedStatusesAsOneAcceptedOutcome() {
        for (Message.Status status : new Message.Status[] {
            Message.Status.ACCEPTED,
            Message.Status.QUEUED,
            Message.Status.SENDING,
            Message.Status.SENT,
            Message.Status.DELIVERED,
            Message.Status.READ
        }) {
            TwilioWhatsAppMessagingUtil util = util(
                    (client, to, from, contentSid, variables, callback) ->
                            message(MESSAGE_SID, status, null));

            VerificationDeliveryResult result = sendValid(util);

            assertThat(result.providerMessageId()).isEqualTo(MESSAGE_SID);
            assertThat(result.metadata().providerStatus())
                    .isEqualTo(status.toString().toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Test
    void rejectsInvalidDestinationCodeSenderAndContentSid() {
        assertThatThrownBy(() -> new TwilioWhatsAppMessagingUtil(
                        mock(TwilioRestClient.class),
                        "invalid-from",
                        CONTENT_SID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
        assertThatThrownBy(() -> new TwilioWhatsAppMessagingUtil(
                        mock(TwilioRestClient.class),
                        FROM,
                        "invalid-content"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contentSid");

        TwilioWhatsAppMessagingUtil util = util(
                (client, to, from, contentSid, variables, callback) ->
                        message(MESSAGE_SID, Message.Status.QUEUED, null));
        assertInvalidRequest(util, "447911123456", "012345");
        assertInvalidRequest(util, "+44 7911 123456", "012345");
        assertInvalidRequest(util, "+447911123456", "12345");
    }

    @Test
    void mapsExplicitTransientCodesToRetryableFailure() {
        for (int providerCode : new int[] {
            20429, 63009, 63010, 63012, 63017, 63018, 63117, 63119
        }) {
            assertApiFailure(providerCode, 400, true);
        }
        assertApiFailure(63015, 400, false);
        assertApiFailure(63015, 500, false);
        assertApiFailure(63028, 400, false);
        assertApiFailure(63028, 429, true);
        assertApiFailure(63028, 503, true);
    }

    @Test
    void treatsImmediateFailedStatusAsClassifiedFailure() {
        TwilioWhatsAppMessagingUtil util = util(
                (client, to, from, contentSid, variables, callback) ->
                        message(MESSAGE_SID, Message.Status.FAILED, 63015));

        assertThatThrownBy(() -> sendValid(util))
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.safeReason()).isEqualTo("twilio_whatsapp_rejected");
                    assertThat(exception.metadata().providerCode()).isEqualTo("63015");
                    assertThat(exception.metadata().failureHint())
                            .isEqualTo(FailureHint.WHATSAPP_SANDBOX_NOT_JOINED);
                    assertThat(exception.metadata().recommendedAction())
                            .isEqualTo(RecommendedAction.JOIN_WHATSAPP_SANDBOX);
                });
    }

    @Test
    void treatsUndeliveredStatusAsExplicitFailure() {
        TwilioWhatsAppMessagingUtil util = util(
                (client, to, from, contentSid, variables, callback) ->
                        message(MESSAGE_SID, Message.Status.UNDELIVERED, 63015));

        assertThatThrownBy(() -> sendValid(util))
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.outcome())
                            .isEqualTo(VerificationDeliveryOutcome.EXPLICIT_FAILURE);
                    assertThat(exception.retryable()).isFalse();
                });
    }

    @Test
    void keepsRetryableProviderCodesOutOfSenderAndTemplateDiagnostics() {
        ApiException failure = mock(ApiException.class);
        when(failure.getStatusCode()).thenReturn(400);
        when(failure.getHttpStatusCode()).thenReturn(400);
        when(failure.getCode()).thenReturn(63018);
        TwilioWhatsAppMessagingUtil util = util((client, to, from, contentSid, variables, callback) -> {
            throw failure;
        });

        assertThatThrownBy(() -> sendValid(util))
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.metadata().failureHint())
                            .isEqualTo(FailureHint.PROVIDER_TEMPORARILY_UNAVAILABLE);
                    assertThat(exception.metadata().recommendedAction())
                            .isEqualTo(RecommendedAction.RETRY_WITH_BACKOFF);
                });
    }

    @Test
    void neverCopiesRawApiMessageIntoSafeMetadata() {
        ApiException failure = mock(ApiException.class);
        when(failure.getStatusCode()).thenReturn(400);
        when(failure.getHttpStatusCode()).thenReturn(400);
        when(failure.getCode()).thenReturn(63015);
        when(failure.getMessage()).thenReturn("contains +447911123456 and 012345");
        TwilioWhatsAppMessagingUtil util = util((client, to, from, contentSid, variables, callback) -> {
            throw failure;
        });

        assertThatThrownBy(() -> sendValid(util))
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception ->
                        assertThat(exception.metadata().toString())
                                .doesNotContain("+447911123456")
                                .doesNotContain("012345")
                                .doesNotContain("contains"));
    }

    @Test
    void transportFailureIsUnknownAndNotRetryableWithoutCopyingRawText() {
        TwilioWhatsAppMessagingUtil util = util((client, to, from, contentSid, variables, callback) -> {
            throw new IllegalStateException("raw transport failure contains 012345");
        });

        assertThatThrownBy(() -> sendValid(util))
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.outcome()).isEqualTo(VerificationDeliveryOutcome.UNKNOWN);
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.metadata().failureStage())
                            .isEqualTo(FailureStage.TRANSPORT);
                    assertThat(exception.metadata().failureCategory())
                            .isEqualTo(FailureCategory.TRANSPORT_FAILURE);
                    assertThat(exception.metadata().toString()).doesNotContain("012345");
                });
    }

    @Test
    void missingMessageSidIsUnknownAndNotRetryable() {
        TwilioWhatsAppMessagingUtil util = util(
                (client, to, from, contentSid, variables, callback) ->
                        message(null, Message.Status.QUEUED, null));

        assertThatThrownBy(() -> sendValid(util))
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.outcome()).isEqualTo(VerificationDeliveryOutcome.UNKNOWN);
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.safeReason())
                            .isEqualTo("twilio_whatsapp_response_missing_sid");
                });
    }

    @Test
    void timeoutIsUnknownAndNeverRetryable() {
        TwilioWhatsAppMessagingUtil util = util(
                (client, to, from, contentSid, variables, callback) -> {
                    // 发送器接口不声明受检异常，因此保留超时 cause 供工具类沿异常链识别 UNKNOWN。
                    throw new IllegalStateException(new SocketTimeoutException("read timeout"));
                });

        assertThatThrownBy(() -> sendValid(util))
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.outcome()).isEqualTo(VerificationDeliveryOutcome.UNKNOWN);
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.safeReason())
                            .isEqualTo("twilio_whatsapp_transport_outcome_unknown");
                });
    }

    private static TwilioWhatsAppMessagingUtil util(
            TwilioWhatsAppMessagingUtil.WhatsAppMessageSender sender) {
        return new TwilioWhatsAppMessagingUtil(
                mock(TwilioRestClient.class),
                FROM,
                CONTENT_SID,
                PhoneNumberUtil.getInstance(),
                new ObjectMapper(),
                FIXED_CLOCK,
                sender);
    }

    private static Message message(String sid, Message.Status status, Integer errorCode) {
        Message message = mock(Message.class);
        when(message.getSid()).thenReturn(sid);
        when(message.getStatus()).thenReturn(status);
        when(message.getErrorCode()).thenReturn(errorCode);
        return message;
    }

    private static void assertApiFailure(
            int providerCode, int httpStatus, boolean expectedRetryable) {
        ApiException failure = mock(ApiException.class);
        when(failure.getStatusCode()).thenReturn(httpStatus);
        when(failure.getHttpStatusCode()).thenReturn(httpStatus);
        when(failure.getCode()).thenReturn(providerCode);
        TwilioWhatsAppMessagingUtil util = util((client, to, from, contentSid, variables, callback) -> {
            throw failure;
        });

        assertThatThrownBy(() -> sendValid(util))
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isEqualTo(expectedRetryable);
                    assertThat(exception.metadata().providerCode())
                            .isEqualTo(Integer.toString(providerCode));
                });
    }

    private static void assertInvalidRequest(
            TwilioWhatsAppMessagingUtil util, String destination, String code) {
        assertThatThrownBy(() -> util.sendVerificationCode(
                        new VerificationDeliveryRequest(destination, code))
                .block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.safeReason())
                            .isEqualTo("twilio_whatsapp_invalid_request");
                    assertThat(exception.metadata().failureStage())
                            .isEqualTo(FailureStage.REQUEST_VALIDATION);
                    assertThat(exception.metadata().failureCategory())
                            .isEqualTo(FailureCategory.INVALID_REQUEST);
                });
    }

    private static VerificationDeliveryResult sendValid(TwilioWhatsAppMessagingUtil util) {
        return util.sendVerificationCode(
                        new VerificationDeliveryRequest("+447911123456", "012345"))
                .block();
    }
}
