package com.example.temperate.service.registration.verification.service.resolver;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.exception.RegistrationException;
import org.junit.jupiter.api.Test;

/**
 * 验证客户端投递方式必须匹配逻辑验证渠道，并禁止中国大陆手机号使用 WhatsApp 投递。
 */
class VerificationDeliveryMethodPolicyTest {

    @Test
    void acceptsEmailSmsAndInternationalWhatsappCombinations() {
        assertThatCode(() -> VerificationDeliveryMethodPolicy.requireSupported(
                        VerificationChannel.EMAIL,
                        VerificationDeliveryMethod.EMAIL,
                        "alice@example.test"))
                .doesNotThrowAnyException();
        assertThatCode(() -> VerificationDeliveryMethodPolicy.requireSupported(
                        VerificationChannel.SMS,
                        VerificationDeliveryMethod.SMS,
                        "+8613800138000"))
                .doesNotThrowAnyException();
        assertThatCode(() -> VerificationDeliveryMethodPolicy.requireSupported(
                        VerificationChannel.SMS,
                        VerificationDeliveryMethod.WHATSAPP,
                        "+447911123456"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsChannelMismatchChinaWhatsappAndInvalidInternationalNumber() {
        assertUnsupported(VerificationChannel.EMAIL, VerificationDeliveryMethod.WHATSAPP,
                "alice@example.test");
        assertUnsupported(VerificationChannel.SMS, VerificationDeliveryMethod.EMAIL,
                "+447911123456");
        assertUnsupported(VerificationChannel.SMS, VerificationDeliveryMethod.WHATSAPP,
                "+8613800138000");
        assertUnsupported(VerificationChannel.SMS, VerificationDeliveryMethod.WHATSAPP,
                "+44");
        assertUnsupported(VerificationChannel.SMS, VerificationDeliveryMethod.WHATSAPP,
                "+44 7911 123456");
        assertUnsupported(VerificationChannel.SMS, VerificationDeliveryMethod.SMS,
                "+44 7911 123456");
    }

    private static void assertUnsupported(
            VerificationChannel channel,
            VerificationDeliveryMethod method,
            String destination) {
        assertThatThrownBy(() -> VerificationDeliveryMethodPolicy.requireSupported(
                        channel, method, destination))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo(RegistrationErrorCode.VERIFICATION_CHANNEL_UNSUPPORTED));
    }
}
