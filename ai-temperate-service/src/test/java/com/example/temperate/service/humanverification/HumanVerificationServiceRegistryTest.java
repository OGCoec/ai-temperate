package com.example.temperate.service.humanverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 验证统一人机验证服务注册表按稳定枚举收集策略，并在启动阶段拒绝不完整或冲突的实现集合。
 */
class HumanVerificationServiceRegistryTest {

    @Test
    void selectsBothServicesByStableEnumInsteadOfBeanName() {
        HumanVerificationService turnstile = service(HumanVerificationType.TURNSTILE);
        HumanVerificationService hcaptcha = service(HumanVerificationType.HCAPTCHA);
        HumanVerificationServiceRegistry registry = new HumanVerificationServiceRegistry(Map.of(
                "renamedTurnstileBean", turnstile,
                "renamedHcaptchaBean", hcaptcha));

        assertThat(registry.getRequired(HumanVerificationType.TURNSTILE))
                .isSameAs(turnstile);
        assertThat(registry.getRequired(HumanVerificationType.HCAPTCHA))
                .isSameAs(hcaptcha);
    }

    @Test
    void rejectsDuplicateServiceTypesAtStartup() {
        assertThatThrownBy(() -> new HumanVerificationServiceRegistry(Map.of(
                "turnstileOne", service(HumanVerificationType.TURNSTILE),
                "turnstileTwo", service(HumanVerificationType.TURNSTILE),
                "hcaptcha", service(HumanVerificationType.HCAPTCHA))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate")
                .hasMessageContaining("TURNSTILE");
    }

    @Test
    void rejectsMissingTurnstileAtStartup() {
        assertThatThrownBy(() -> new HumanVerificationServiceRegistry(Map.of(
                "hcaptcha", service(HumanVerificationType.HCAPTCHA))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing")
                .hasMessageContaining("TURNSTILE");
    }

    @Test
    void rejectsMissingHcaptchaAtStartup() {
        assertThatThrownBy(() -> new HumanVerificationServiceRegistry(Map.of(
                "turnstile", service(HumanVerificationType.TURNSTILE))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing")
                .hasMessageContaining("HCAPTCHA");
    }

    @Test
    void rejectsNullTypeInsteadOfReturningNull() {
        HumanVerificationServiceRegistry registry = new HumanVerificationServiceRegistry(Map.of(
                "turnstile", service(HumanVerificationType.TURNSTILE),
                "hcaptcha", service(HumanVerificationType.HCAPTCHA)));

        assertThatThrownBy(() -> registry.getRequired(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    private static HumanVerificationService service(HumanVerificationType type) {
        return new HumanVerificationService() {
            @Override
            public HumanVerificationType type() {
                return type;
            }

            @Override
            public Mono<Void> verify(HumanVerificationCommand command) {
                return Mono.empty();
            }
        };
    }
}
