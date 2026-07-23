package com.example.temperate.service.registration.verification.service.resolver.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.enums.VerificationProvider;
import com.example.temperate.service.registration.exception.RegistrationException;
import org.junit.jupiter.api.Test;

/**
 * 验证邮件投递按当前消息稳定分桶，短信按真实国家代码选供应商，并拒绝不规范的国际号码。
 */
class LibphonenumberVerificationProviderResolverTest {

    private final LibphonenumberVerificationProviderResolver resolver =
            new LibphonenumberVerificationProviderResolver();

    @Test
    void emailAlwaysUsesGmail() {
        assertThat(resolver.resolve(VerificationChannel.EMAIL, "alice@example.test"))
                .isEqualTo(VerificationProvider.GMAIL);
    }

    @Test
    void emailDeliveryUsesStableFiftyFiftyBucketForCurrentMessage() {
        assertThat(resolver.resolveDeliveryAttempt(
                        VerificationChannel.EMAIL,
                        "alice@example.test",
                        "message-0"))
                .isEqualTo(VerificationProvider.GMAIL);
        assertThat(resolver.resolveDeliveryAttempt(
                        VerificationChannel.EMAIL,
                        "alice@example.test",
                        "message-1"))
                .isEqualTo(VerificationProvider.MICROSOFT_GRAPH);
        assertThat(resolver.resolveDeliveryAttempt(
                        VerificationChannel.EMAIL,
                        "alice@example.test",
                        "message-1"))
                .isEqualTo(VerificationProvider.MICROSOFT_GRAPH);
    }

    @Test
    void deliveryAttemptKeepsSmsCountryRouting() {
        assertThat(resolver.resolveDeliveryAttempt(
                        VerificationChannel.SMS,
                        VerificationDeliveryMethod.SMS,
                        "+8613800138000",
                        "message-0"))
                .isEqualTo(VerificationProvider.ALIYUN_SMS);
        assertThat(resolver.resolveDeliveryAttempt(
                        VerificationChannel.SMS,
                        VerificationDeliveryMethod.SMS,
                        "+447911123456",
                        "message-1"))
                .isEqualTo(VerificationProvider.TWILIO_SMS);
    }

    @Test
    void internationalPhoneCanUseTwilioWhatsapp() {
        assertThat(resolver.resolve(
                        VerificationChannel.SMS,
                        VerificationDeliveryMethod.WHATSAPP,
                        "+447911123456"))
                .isEqualTo(VerificationProvider.TWILIO_WHATSAPP);
        assertThat(resolver.resolveDeliveryAttempt(
                        VerificationChannel.SMS,
                        VerificationDeliveryMethod.WHATSAPP,
                        "+12025550123",
                        "message-2"))
                .isEqualTo(VerificationProvider.TWILIO_WHATSAPP);
    }

    @Test
    void chinaPhoneCannotUseWhatsapp() {
        assertThatThrownBy(() -> resolver.resolve(
                        VerificationChannel.SMS,
                        VerificationDeliveryMethod.WHATSAPP,
                        "+8613800138000"))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(RegistrationErrorCode.VERIFICATION_CHANNEL_UNSUPPORTED));
    }

    @Test
    void chinaNumberUsesAliyun() {
        assertThat(resolver.resolve(VerificationChannel.SMS, "+8613800138000"))
                .isEqualTo(VerificationProvider.ALIYUN_SMS);
    }

    @Test
    void ukAndUsNumbersUseTwilio() {
        assertThat(resolver.resolve(VerificationChannel.SMS, "+447911123456"))
                .isEqualTo(VerificationProvider.TWILIO_SMS);
        assertThat(resolver.resolve(VerificationChannel.SMS, "+12025550123"))
                .isEqualTo(VerificationProvider.TWILIO_SMS);
    }

    @Test
    void missingInternationalPrefixAndInvalidNumberAreRejected() {
        assertUnsupported("13800138000");
        assertUnsupported("+44");
    }

    private void assertUnsupported(String destination) {
        assertThatThrownBy(() -> resolver.resolve(VerificationChannel.SMS, destination))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(RegistrationErrorCode.VERIFICATION_CHANNEL_UNSUPPORTED));
    }
}
