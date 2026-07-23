package com.example.temperate.service.registration.verification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.aliyun.AliyunUtils;
import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodeCommand;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.enums.RegistrationStatus;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationProvider;
import com.example.temperate.service.registration.flow.security.RegistrationAccess;
import com.example.temperate.service.registration.verification.delivery.classification.AliyunSmsFailureClassifier;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationPurpose;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryException;
import com.example.temperate.service.registration.verification.delivery.util.gmail.GmailApiMailUtil;
import com.example.temperate.service.registration.verification.delivery.util.microsoft.MicrosoftGraphApiMailUtil;
import com.example.temperate.service.registration.verification.delivery.util.twilio.TwilioVerifySmsUtil;
import com.example.temperate.service.registration.verification.delivery.util.twilio.TwilioWhatsAppMessagingUtil;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeVerifier;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

/**
 * 验证五个供应商实现各自只调用对应发送工具，并把用户输入校验统一委托给共享 Redis 校验器。
 */
class SixDigitVerificationCodeServiceImplTest {

    @Test
    void aliyunProductionConstructorIsExplicitlySelectedForSpringInjection()
            throws NoSuchMethodException {
        boolean isAutowired = AliyunSmsSixDigitVerificationCodeServiceImpl.class
                .getConstructor(
                        AliyunUtils.class,
                        String.class,
                        AliyunSmsFailureClassifier.class,
                        SixDigitVerificationCodeVerifier.class)
                .isAnnotationPresent(Autowired.class);

        assertThat(isAutowired).isTrue();
    }

    @Test
    void gmailServiceDelegatesSendAndVerificationToTheirSingleCollaborators() {
        GmailApiMailUtil gmail = mock(GmailApiMailUtil.class);
        SixDigitVerificationCodeVerifier verifier = mock(SixDigitVerificationCodeVerifier.class);
        VerificationDeliveryRequest request =
                new VerificationDeliveryRequest("alice@example.test", "012345");
        RegistrationVerifyCodeCommand command = command(VerificationChannel.EMAIL);
        RegistrationStatusResult status = status();
        when(gmail.sendVerificationCode(request)).thenReturn(Mono.just(
                new VerificationDeliveryResult(
                        VerificationChannel.EMAIL, "gmail", "message-id", Instant.EPOCH)));
        when(verifier.verify(command)).thenReturn(status);
        GmailSixDigitVerificationCodeServiceImpl service =
                new GmailSixDigitVerificationCodeServiceImpl(gmail, verifier);

        VerificationDeliveryResult result = service.sendCode(request).block();

        assertThat(result.provider()).isEqualTo("gmail");
        assertThat(service.type()).isEqualTo(VerificationProvider.GMAIL);
        assertThat(service.verifyCode(command)).isSameAs(status);
        verify(gmail).sendVerificationCode(request);
        verify(verifier).verify(command);
    }

    @Test
    void microsoftServiceDelegatesSendAndVerificationToTheirSingleCollaborators() {
        MicrosoftGraphApiMailUtil microsoft = mock(MicrosoftGraphApiMailUtil.class);
        SixDigitVerificationCodeVerifier verifier = mock(SixDigitVerificationCodeVerifier.class);
        VerificationDeliveryRequest request =
                new VerificationDeliveryRequest("alice@example.test", "012345");
        RegistrationVerifyCodeCommand command = command(VerificationChannel.EMAIL);
        RegistrationStatusResult status = status();
        when(microsoft.sendVerificationCode(request)).thenReturn(Mono.just(
                new VerificationDeliveryResult(
                        VerificationChannel.EMAIL, "microsoft_graph", null, Instant.EPOCH)));
        when(verifier.verify(command)).thenReturn(status);
        MicrosoftGraphSixDigitVerificationCodeServiceImpl service =
                new MicrosoftGraphSixDigitVerificationCodeServiceImpl(microsoft, verifier);

        VerificationDeliveryResult result = service.sendCode(request).block();

        assertThat(result.provider()).isEqualTo("microsoft_graph");
        assertThat(service.type()).isEqualTo(VerificationProvider.MICROSOFT_GRAPH);
        assertThat(service.verifyCode(command)).isSameAs(status);
        verify(microsoft).sendVerificationCode(request);
        verify(verifier).verify(command);
    }

    @Test
    void aliyunServicePassesRemainingValidityToSharedTool() throws Exception {
        AliyunUtils aliyunUtils = mock(AliyunUtils.class);
        Duration remaining = Duration.ofSeconds(241);
        when(aliyunUtils.sendSmsVerifyCode(
                        eq("+8613800138000"),
                        eq("SMS_TEST_TEMPLATE"),
                        eq("012345"),
                        eq(remaining)))
                .thenReturn(new AliyunUtils.SmsSendResult(
                        true, 200, "OK", true, "test-request"));
        AliyunSmsSixDigitVerificationCodeServiceImpl service = aliyunService(aliyunUtils);

        VerificationDeliveryResult result = service.sendCode(new VerificationDeliveryRequest(
                        "+8613800138000",
                        "012345",
                        VerificationPurpose.REGISTRATION,
                        remaining))
                .block();

        assertThat(result).isNotNull();
        assertThat(result.channel()).isEqualTo(VerificationChannel.SMS);
        assertThat(result.provider()).isEqualTo("aliyun-dypnsapi");
        assertThat(result.providerMessageId()).isEqualTo("test-request");
        assertThat(result.metadata().operation().name()).isEqualTo("SEND_SMS");
        assertThat(service.type()).isEqualTo(VerificationProvider.ALIYUN_SMS);
        verify(aliyunUtils).sendSmsVerifyCode(
                "+8613800138000", "SMS_TEST_TEMPLATE", "012345", remaining);
    }

    @Test
    void aliyunFrequencyRejectionIsNotRetryable() throws Exception {
        AliyunUtils aliyunUtils = mock(AliyunUtils.class);
        when(aliyunUtils.sendSmsVerifyCode(any(), any(), any(), any(Duration.class)))
                .thenReturn(new AliyunUtils.SmsSendResult(
                        false, 200, "biz.FREQUENCY", false, "test-request"));

        assertThatThrownBy(() -> aliyunService(aliyunUtils)
                        .sendCode(smsRequest())
                        .block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.safeReason())
                            .isEqualTo("sms_provider_frequency_limited");
                    assertThat(exception.metadata().providerCode())
                            .isEqualTo("biz.FREQUENCY");
                });
    }

    @Test
    void aliyunProtocolNegotiationFailureIsRetryable() throws Exception {
        AliyunUtils aliyunUtils = mock(AliyunUtils.class);
        when(aliyunUtils.sendSmsVerifyCode(any(), any(), any(), any(Duration.class)))
                .thenThrow(new ProtocolNegotiationException());

        assertThatThrownBy(() -> aliyunService(aliyunUtils)
                        .sendCode(smsRequest())
                        .block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.safeReason())
                            .isEqualTo("sms_transport_handshake_failed");
                });
    }

    @Test
    void aliyunReadTimeoutIsOutcomeUnknownAndNotRetryable() throws Exception {
        AliyunUtils aliyunUtils = mock(AliyunUtils.class);
        when(aliyunUtils.sendSmsVerifyCode(any(), any(), any(), any(Duration.class)))
                .thenThrow(new SocketTimeoutException("read timed out"));

        assertThatThrownBy(() -> aliyunService(aliyunUtils)
                        .sendCode(smsRequest())
                        .block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.safeReason())
                            .isEqualTo("sms_delivery_outcome_unknown");
                });
    }

    @Test
    void aliyunVerificationUsesOnlySharedVerifier() {
        AliyunUtils aliyunUtils = mock(AliyunUtils.class);
        SixDigitVerificationCodeVerifier verifier = mock(SixDigitVerificationCodeVerifier.class);
        RegistrationVerifyCodeCommand command = command(VerificationChannel.SMS);
        RegistrationStatusResult status = status();
        when(verifier.verify(command)).thenReturn(status);
        AliyunSmsSixDigitVerificationCodeServiceImpl service =
                new AliyunSmsSixDigitVerificationCodeServiceImpl(
                        aliyunUtils,
                        "SMS_TEST_TEMPLATE",
                        new AliyunSmsFailureClassifier(),
                        verifier);

        assertThat(service.verifyCode(command)).isSameAs(status);
        verify(verifier).verify(command);
    }

    @Test
    void twilioServiceDelegatesSendAndUsesOnlySharedVerifierForVerification() {
        TwilioVerifySmsUtil twilio = mock(TwilioVerifySmsUtil.class);
        SixDigitVerificationCodeVerifier verifier = mock(SixDigitVerificationCodeVerifier.class);
        VerificationDeliveryRequest request =
                new VerificationDeliveryRequest("+447911123456", "012345");
        RegistrationVerifyCodeCommand command = command(VerificationChannel.SMS);
        RegistrationStatusResult status = status();
        when(twilio.sendVerificationCode(request)).thenReturn(Mono.just(
                new VerificationDeliveryResult(
                        VerificationChannel.SMS,
                        "twilio-verify",
                        "VE00000000000000000000000000000000",
                        Instant.EPOCH)));
        when(verifier.verify(command)).thenReturn(status);
        TwilioSmsSixDigitVerificationCodeServiceImpl service =
                new TwilioSmsSixDigitVerificationCodeServiceImpl(twilio, verifier);

        VerificationDeliveryResult result = service.sendCode(request).block();

        assertThat(result.provider()).isEqualTo("twilio-verify");
        assertThat(service.type()).isEqualTo(VerificationProvider.TWILIO_SMS);
        assertThat(service.verifyCode(command)).isSameAs(status);
        verify(twilio).sendVerificationCode(request);
        verify(verifier).verify(command);
    }

    @Test
    void twilioWhatsappServiceDelegatesSendAndUsesOnlySharedVerifierForVerification() {
        TwilioWhatsAppMessagingUtil twilio = mock(TwilioWhatsAppMessagingUtil.class);
        SixDigitVerificationCodeVerifier verifier = mock(SixDigitVerificationCodeVerifier.class);
        VerificationDeliveryRequest request =
                new VerificationDeliveryRequest("+447911123456", "012345");
        RegistrationVerifyCodeCommand command = command(VerificationChannel.SMS);
        RegistrationStatusResult status = status();
        when(twilio.sendVerificationCode(request)).thenReturn(Mono.just(
                new VerificationDeliveryResult(
                        VerificationChannel.SMS,
                        com.example.temperate.service.registration.enums
                                .VerificationDeliveryMethod.WHATSAPP,
                        "twilio-whatsapp",
                        "SM00000000000000000000000000000000",
                        Instant.EPOCH)));
        when(verifier.verify(command)).thenReturn(status);
        TwilioWhatsAppSixDigitVerificationCodeServiceImpl service =
                new TwilioWhatsAppSixDigitVerificationCodeServiceImpl(twilio, verifier);

        VerificationDeliveryResult result = service.sendCode(request).block();

        assertThat(result.provider()).isEqualTo("twilio-whatsapp");
        assertThat(service.type()).isEqualTo(VerificationProvider.TWILIO_WHATSAPP);
        assertThat(service.verifyCode(command)).isSameAs(status);
        verify(twilio).sendVerificationCode(request);
        verify(verifier).verify(command);
    }

    private static VerificationDeliveryRequest smsRequest() {
        return new VerificationDeliveryRequest(
                "+8613800138000",
                "012345",
                VerificationPurpose.REGISTRATION,
                Duration.ofMinutes(5));
    }

    private static AliyunSmsSixDigitVerificationCodeServiceImpl aliyunService(
            AliyunUtils aliyunUtils) {
        return new AliyunSmsSixDigitVerificationCodeServiceImpl(
                aliyunUtils,
                "SMS_TEST_TEMPLATE",
                new AliyunSmsFailureClassifier(),
                mock(SixDigitVerificationCodeVerifier.class));
    }

    private static RegistrationVerifyCodeCommand command(VerificationChannel channel) {
        return new RegistrationVerifyCodeCommand(
                new RegistrationAccess(
                        "register-token",
                        "flow-csrf",
                        "challenge-handle",
                        "550e8400-e29b-41d4-a716-446655440000",
                        "203.0.113.7"),
                channel,
                "012345");
    }

    private static RegistrationStatusResult status() {
        return new RegistrationStatusResult(
                RegistrationStatus.ACTIVE,
                true,
                false,
                false,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(600));
    }

    private static final class ProtocolNegotiationException extends RuntimeException {
    }
}
