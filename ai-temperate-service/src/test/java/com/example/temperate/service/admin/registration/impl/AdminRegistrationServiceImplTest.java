package com.example.temperate.service.admin.registration.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.config.AdminConfigurationService;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.service.humanverification.HumanVerificationService;
import com.example.temperate.service.humanverification.HumanVerificationServiceRegistry;
import com.example.temperate.service.humanverification.HumanVerificationType;
import com.example.temperate.service.auth.password.policy.PasswordStrengthPolicy;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import com.example.temperate.service.registration.component.token.RegistrationTokenGenerator;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.RegistrationStatus;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.flow.domain.RegistrationFlowSnapshot;
import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import com.example.temperate.service.registration.flow.security.RegistrationAccess;
import com.example.temperate.service.registration.flow.security.RegistrationTokenProtector;
import com.example.temperate.service.registration.flow.store.RegistrationFlowStore;
import com.example.temperate.service.registration.verification.delivery.coordinator.VerificationDeliveryCoordinator;
import com.example.temperate.service.registration.verification.delivery.logging.DebugLogCapture;
import com.example.temperate.service.registration.verification.delivery.operation.VerificationDeliveryOperationIdGenerator;
import com.example.temperate.service.registration.verification.generator.VerificationCodeGenerator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 验证管理员注册只在 hCaptcha 成功后原子标记当前 Flow，供应商失败时不推进 Redis 状态。
 */
@ExtendWith(MockitoExtension.class)
class AdminRegistrationServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-23T13:00:00Z");
    private static final RegistrationAccess ACCESS = new RegistrationAccess(
            "a".repeat(38),
            "b".repeat(43),
            "c".repeat(43),
            "11111111-1111-4111-8111-111111111111",
            "203.0.113.10");
    private static final ProtectedRegistrationAccess PROTECTED_ACCESS = protectedAccess();

    @Mock AdminConfigurationService configurationService;
    @Mock RegistrationFlowStore flowStore;
    @Mock RegistrationInputNormalizer inputNormalizer;
    @Mock RegistrationTokenProtector tokenProtector;
    @Mock RegistrationTokenGenerator tokenGenerator;
    @Mock VerificationCodeGenerator codeGenerator;
    @Mock VerificationDeliveryOperationIdGenerator operationIdGenerator;
    @Mock VerificationDeliveryCoordinator deliveryCoordinator;
    @Mock HumanVerificationServiceRegistry humanVerificationServices;
    @Mock HumanVerificationService hcaptcha;
    @Mock PasswordStrengthPolicy passwordPolicy;
    @Mock PasswordEncoder passwordEncoder;

    private AdminRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminRegistrationServiceImpl(
                configurationService,
                flowStore,
                inputNormalizer,
                tokenProtector,
                tokenGenerator,
                codeGenerator,
                operationIdGenerator,
                deliveryCoordinator,
                humanVerificationServices,
                passwordPolicy,
                passwordEncoder,
                Clock.fixed(NOW, ZoneOffset.UTC),
                AdminProperties.testDefaults(Path.of("target/admin-registration/complete.yaml")));
        when(tokenProtector.protect(ACCESS)).thenReturn(PROTECTED_ACCESS);
        when(humanVerificationServices.getRequired(HumanVerificationType.HCAPTCHA))
                .thenReturn(hcaptcha);
        when(flowStore.getRequired(PROTECTED_ACCESS, NOW)).thenReturn(snapshot(false));
    }

    @Test
    void marksHumanVerifiedOnlyAfterProviderSuccess() {
        when(hcaptcha.verify(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        when(flowStore.markHumanVerified(PROTECTED_ACCESS, NOW)).thenReturn(snapshot(true));

        try (DebugLogCapture logs =
                DebugLogCapture.start(AdminRegistrationServiceImpl.class)) {
            StepVerifier.create(service.verifyHcaptcha(ACCESS, "one-time-token"))
                    .assertNext(result -> {
                        assertThat(result.status()).isEqualTo(RegistrationStatus.ACTIVE);
                        assertThat(result.humanVerified()).isTrue();
                    })
                    .verifyComplete();

            assertThat(logs.joinedMessages())
                    .contains("event=admin_hcaptcha_flow_finalized")
                    .contains("outcome=succeeded")
                    .doesNotContain("one-time-token")
                    .doesNotContain(ACCESS.registerToken())
                    .doesNotContain(ACCESS.flowCsrf())
                    .doesNotContain(ACCESS.challengeHandle())
                    .doesNotContain(ACCESS.deviceInstallationId())
                    .doesNotContain(ACCESS.canonicalIp());
        }

        verify(humanVerificationServices).getRequired(HumanVerificationType.HCAPTCHA);
        verify(hcaptcha).verify(org.mockito.ArgumentMatchers.argThat(command ->
                "one-time-token".equals(command.responseToken())
                        && ACCESS.canonicalIp().equals(command.canonicalClientIp())
                        && ACCESS.challengeHandle().equals(command.challengeId())
                        && command.expectedAction().isEmpty()));
        verify(flowStore).markHumanVerified(PROTECTED_ACCESS, NOW);
    }

    @Test
    void providerFailureDoesNotAdvanceRegistrationState() {
        when(hcaptcha.verify(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.error(
                new AdminException(
                        AdminErrorCode.HCAPTCHA_REJECTED,
                        "Human verification was rejected.")));

        StepVerifier.create(service.verifyHcaptcha(ACCESS, "one-time-token"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(AdminException.class);
                    assertThat(((AdminException) error).code())
                            .isEqualTo(AdminErrorCode.HCAPTCHA_REJECTED);
                })
                .verify();

        verify(flowStore, never()).markHumanVerified(PROTECTED_ACCESS, NOW);
    }

    @Test
    void logsConsumedChallengeAsRedisFinalizeRejection() {
        when(hcaptcha.verify(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        when(flowStore.markHumanVerified(PROTECTED_ACCESS, NOW))
                .thenThrow(new RegistrationException(
                        RegistrationErrorCode.TURNSTILE_REJECTED,
                        "sensitive Redis failure one-time-token"));

        try (DebugLogCapture logs =
                DebugLogCapture.start(AdminRegistrationServiceImpl.class)) {
            StepVerifier.create(service.verifyHcaptcha(ACCESS, "one-time-token"))
                    .expectErrorSatisfies(error -> {
                        assertThat(error).isInstanceOf(AdminException.class);
                        assertThat(((AdminException) error).code())
                                .isEqualTo(AdminErrorCode.HCAPTCHA_REJECTED);
                    })
                    .verify();

            assertThat(logs.joinedMessages())
                    .contains("event=admin_hcaptcha_flow_finalize_rejected")
                    .contains("failureStage=redis_finalize")
                    .contains("safeReason=challenge_already_consumed_or_invalid")
                    .contains("registrationCode=TURNSTILE_REJECTED")
                    .doesNotContain("sensitive Redis failure")
                    .doesNotContain("one-time-token")
                    .doesNotContain(ACCESS.registerToken())
                    .doesNotContain(ACCESS.flowCsrf())
                    .doesNotContain(ACCESS.challengeHandle())
                    .doesNotContain(ACCESS.deviceInstallationId())
                    .doesNotContain(ACCESS.canonicalIp());
        }
    }

    private static RegistrationFlowSnapshot snapshot(boolean humanVerified) {
        return new RegistrationFlowSnapshot(
                "admin@example.test",
                "+12164202316",
                humanVerified,
                false,
                false,
                false,
                NOW,
                NOW.plusSeconds(600),
                NOW.plusSeconds(1800));
    }

    private static ProtectedRegistrationAccess protectedAccess() {
        HmacIdentifier identifier =
                HmacIdentifier.fromProtectedValue("A".repeat(43));
        return new ProtectedRegistrationAccess(
                identifier,
                identifier,
                identifier,
                identifier,
                identifier,
                identifier,
                identifier,
                identifier);
    }
}
