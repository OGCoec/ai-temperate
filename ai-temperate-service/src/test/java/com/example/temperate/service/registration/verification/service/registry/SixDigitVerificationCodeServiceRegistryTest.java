package com.example.temperate.service.registration.verification.service.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodeCommand;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.VerificationProvider;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 验证供应商服务按稳定枚举注册、重复类型启动失败、未知类型受控失败且不依赖 Bean 名或 Map 顺序。
 */
class SixDigitVerificationCodeServiceRegistryTest {

    @Test
    void registersEveryServiceAndSelectsByStableProvider() {
        SixDigitVerificationCodeService gmail = service(VerificationProvider.GMAIL);
        SixDigitVerificationCodeService microsoft =
                service(VerificationProvider.MICROSOFT_GRAPH);
        SixDigitVerificationCodeService aliyun = service(VerificationProvider.ALIYUN_SMS);
        SixDigitVerificationCodeService twilioSms = service(VerificationProvider.TWILIO_SMS);
        SixDigitVerificationCodeService twilioWhatsapp =
                service(VerificationProvider.TWILIO_WHATSAPP);

        SixDigitVerificationCodeServiceRegistry registry =
                new SixDigitVerificationCodeServiceRegistry(Map.of(
                        "arbitraryThirdBean", twilioSms,
                        "arbitraryFirstBean", gmail,
                        "arbitrarySecondBean", aliyun,
                        "arbitraryFourthBean", microsoft,
                        "arbitraryFifthBean", twilioWhatsapp));

        assertThat(registry.registeredTypes()).containsExactlyInAnyOrder(
                VerificationProvider.GMAIL,
                VerificationProvider.MICROSOFT_GRAPH,
                VerificationProvider.ALIYUN_SMS,
                VerificationProvider.TWILIO_SMS,
                VerificationProvider.TWILIO_WHATSAPP);
        assertThat(registry.getRequired(VerificationProvider.GMAIL)).isSameAs(gmail);
        assertThat(registry.getRequired(VerificationProvider.MICROSOFT_GRAPH))
                .isSameAs(microsoft);
        assertThat(registry.getRequired(VerificationProvider.ALIYUN_SMS)).isSameAs(aliyun);
        assertThat(registry.getRequired(VerificationProvider.TWILIO_SMS)).isSameAs(twilioSms);
        assertThat(registry.getRequired(VerificationProvider.TWILIO_WHATSAPP))
                .isSameAs(twilioWhatsapp);
    }

    @Test
    void duplicateStableProviderFailsAtConstruction() {
        assertThatThrownBy(() -> new SixDigitVerificationCodeServiceRegistry(Map.of(
                        "first", service(VerificationProvider.GMAIL),
                        "second", service(VerificationProvider.GMAIL))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GMAIL");
    }

    @Test
    void unknownProviderReturnsControlledBusinessError() {
        SixDigitVerificationCodeServiceRegistry registry =
                new SixDigitVerificationCodeServiceRegistry(
                        Map.of("mailBean", service(VerificationProvider.GMAIL)));

        assertThatThrownBy(() -> registry.getRequired(VerificationProvider.TWILIO_SMS))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(RegistrationErrorCode.VERIFICATION_CHANNEL_UNSUPPORTED));
    }

    private static SixDigitVerificationCodeService service(VerificationProvider provider) {
        return new SixDigitVerificationCodeService() {
            @Override
            public VerificationProvider type() {
                return provider;
            }

            @Override
            public Mono<VerificationDeliveryResult> sendCode(
                    VerificationDeliveryRequest request) {
                return Mono.empty();
            }

            @Override
            public RegistrationStatusResult verifyCode(
                    RegistrationVerifyCodeCommand command) {
                return null;
            }
        };
    }
}
