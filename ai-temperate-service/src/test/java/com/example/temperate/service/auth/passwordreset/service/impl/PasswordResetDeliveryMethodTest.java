package com.example.temperate.service.auth.passwordreset.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.service.auth.password.policy.PasswordStrengthPolicy;
import com.example.temperate.service.auth.passwordreset.PasswordResetErrorCode;
import com.example.temperate.service.auth.passwordreset.PasswordResetException;
import com.example.temperate.service.auth.passwordreset.dto.PasswordResetAccess;
import com.example.temperate.service.auth.passwordreset.flow.PasswordResetFlowSnapshot;
import com.example.temperate.service.auth.passwordreset.flow.PasswordResetFlowStore;
import com.example.temperate.service.auth.passwordreset.notification.PasswordResetNotificationService;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.session.authentication.service.SessionAuthenticationService;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.humanverification.HumanVerificationCommand;
import com.example.temperate.service.humanverification.HumanVerificationService;
import com.example.temperate.service.humanverification.HumanVerificationServiceRegistry;
import com.example.temperate.service.humanverification.HumanVerificationType;
import com.example.temperate.service.humanverification.exception.HumanVerificationUnavailableException;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import com.example.temperate.service.registration.enums.RegistrationDiagnosticCode;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.verification.delivery.operation.VerificationDeliveryOperationIdGenerator;
import com.example.temperate.service.registration.verification.delivery.rabbit.VerificationDeliveryPublisher;
import com.example.temperate.service.registration.verification.generator.VerificationCodeGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 验证手机号找回密码选择 WhatsApp 时仍复用 SMS 验证码槽，并在生成验证码前拒绝中国号码。
 */
class PasswordResetDeliveryMethodTest {

    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");
    private static final HmacIdentifier HMAC =
            HmacIdentifier.fromProtectedValue("A".repeat(43));

    private PasswordResetFlowStore flowStore;
    private AuthSessionSecretProtector protector;
    private VerificationCodeGenerator codeGenerator;
    private VerificationDeliveryPublisher publisher;
    private HumanVerificationServiceRegistry humanVerificationServices;
    private HumanVerificationService turnstileService;
    private PasswordResetServiceImpl service;

    @BeforeEach
    void setUp() {
        flowStore = mock(PasswordResetFlowStore.class);
        protector = mock(AuthSessionSecretProtector.class);
        codeGenerator = mock(VerificationCodeGenerator.class);
        publisher = mock(VerificationDeliveryPublisher.class);
        humanVerificationServices = mock(HumanVerificationServiceRegistry.class);
        turnstileService = mock(HumanVerificationService.class);
        when(humanVerificationServices.getRequired(HumanVerificationType.TURNSTILE))
                .thenReturn(turnstileService);
        when(protector.passwordResetFlowToken(anyString())).thenReturn(HMAC);
        when(protector.passwordResetChallenge(anyString())).thenReturn(HMAC);
        when(protector.device(anyString())).thenReturn(HMAC);
        when(protector.deviceBlock(anyString())).thenReturn(HMAC);
        when(protector.passwordResetCodeKey(anyString())).thenReturn(HMAC);
        when(protector.passwordResetTarget(anyString())).thenReturn(HMAC);
        when(protector.passwordResetCodeDigest(anyString(), anyString())).thenReturn(HMAC);
        when(protector.passwordResetDeliveryOperation(anyString())).thenReturn(HMAC);
        when(codeGenerator.generate()).thenReturn("012345");
        service = new PasswordResetServiceImpl(
                mock(UserLoginIdentityMapper.class),
                new RegistrationInputNormalizer(),
                new PasswordStrengthPolicy(),
                flowStore,
                protector,
                mock(AuthTokenService.class),
                humanVerificationServices,
                codeGenerator,
                new VerificationDeliveryOperationIdGenerator(),
                publisher,
                mock(PasswordResetNotificationService.class),
                mock(SessionAuthenticationService.class),
                mock(PasswordEncoder.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void turnstileLoadsFlowThenVerifiesResetActionAndMarksOnce() {
        when(flowStore.getRequired(any(), eq(NOW)))
                .thenReturn(phoneFlow("+447911123456"));
        when(turnstileService.verify(any(HumanVerificationCommand.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.verifyTurnstile(
                        access(), "turnstile-token"))
                .verifyComplete();

        InOrder ordered = inOrder(flowStore, turnstileService);
        ordered.verify(flowStore).getRequired(any(), eq(NOW));
        ordered.verify(turnstileService).verify(
                org.mockito.ArgumentMatchers.argThat(command ->
                        "turnstile-token".equals(command.responseToken())
                                && "203.0.113.10".equals(command.canonicalClientIp())
                                && "challenge".equals(command.challengeId())
                                && "password_reset".equals(command.expectedAction())));
        verify(humanVerificationServices)
                .getRequired(HumanVerificationType.TURNSTILE);
        ordered.verify(flowStore).markHumanVerified(any(), eq(NOW));
        verify(flowStore).markHumanVerified(any(), eq(NOW));
    }

    @Test
    void turnstileFailureMapsToResetErrorAndNeverMarksFlow() {
        when(flowStore.getRequired(any(), eq(NOW)))
                .thenReturn(phoneFlow("+447911123456"));
        when(turnstileService.verify(any(HumanVerificationCommand.class)))
                .thenReturn(Mono.error(new RegistrationException(
                        RegistrationErrorCode.TURNSTILE_REJECTED,
                        "Turnstile verification was rejected.",
                        RegistrationDiagnosticCode.CLOUDFLARE_TOKEN_REJECTED)));

        StepVerifier.create(service.verifyTurnstile(
                        access(), "turnstile-token"))
                .expectErrorSatisfies(failure -> {
                    assertThat(failure)
                            .isInstanceOf(PasswordResetException.class);
                    assertThat(((PasswordResetException) failure).code())
                            .isEqualTo(
                                    PasswordResetErrorCode.TURNSTILE_REJECTED);
                })
                .verify();

        verify(flowStore, never()).markHumanVerified(any(), any());
    }

    @Test
    void turnstileUnavailablePropagatesWithoutMarkingPasswordResetFlow() {
        when(flowStore.getRequired(any(), eq(NOW)))
                .thenReturn(phoneFlow("+447911123456"));
        HumanVerificationUnavailableException unavailable =
                new HumanVerificationUnavailableException(
                        HumanVerificationType.TURNSTILE,
                        new IllegalStateException("simulated transport failure"));
        when(turnstileService.verify(any(HumanVerificationCommand.class)))
                .thenReturn(Mono.error(unavailable));

        StepVerifier.create(service.verifyTurnstile(
                        access(), "turnstile-token"))
                .expectErrorSatisfies(failure -> assertThat(failure)
                        .isSameAs(unavailable))
                .verify();

        verify(flowStore, never()).markHumanVerified(any(), any());
    }

    @Test
    void internationalWhatsappUsesSharedSmsStateAndPublisherRoute() {
        when(flowStore.getRequired(any(), eq(NOW))).thenReturn(phoneFlow("+447911123456"));

        service.sendCode(access(), VerificationDeliveryMethod.WHATSAPP);

        verify(flowStore).issueCode(any(), eq(HMAC), eq(HMAC), eq(NOW));
        verify(publisher).publishPasswordReset(
                any(),
                eq(VerificationChannel.SMS),
                eq(VerificationDeliveryMethod.WHATSAPP),
                eq(HMAC),
                any(),
                eq(NOW.plusSeconds(300)));
    }

    @Test
    void missingDeliveryMethodKeepsLegacyEmailDefault() {
        when(flowStore.getRequired(any(), eq(NOW))).thenReturn(new PasswordResetFlowSnapshot(
                VerificationChannel.EMAIL,
                "alice@example.test",
                42L,
                true,
                NOW.minusSeconds(10),
                NOW.plusSeconds(300),
                NOW.plusSeconds(600)));

        service.sendCode(access());

        verify(publisher).publishPasswordReset(
                any(),
                eq(VerificationChannel.EMAIL),
                eq(VerificationDeliveryMethod.EMAIL),
                eq(HMAC),
                any(),
                eq(NOW.plusSeconds(300)));
    }

    @Test
    void chinaPhoneRejectsWhatsappBeforeCodeOrMessageCreation() {
        when(flowStore.getRequired(any(), eq(NOW))).thenReturn(phoneFlow("+8613800138000"));

        assertThatThrownBy(() -> service.sendCode(
                        access(), VerificationDeliveryMethod.WHATSAPP))
                .isInstanceOfSatisfying(PasswordResetException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(PasswordResetErrorCode.INVALID_INPUT));

        verify(codeGenerator, never()).generate();
        verifyNoInteractions(publisher);
    }

    @Test
    void emailFlowRejectsWhatsappBeforeCodeOrMessageCreation() {
        when(flowStore.getRequired(any(), eq(NOW))).thenReturn(new PasswordResetFlowSnapshot(
                VerificationChannel.EMAIL,
                "alice@example.test",
                42L,
                true,
                NOW.minusSeconds(10),
                NOW.plusSeconds(300),
                NOW.plusSeconds(600)));

        assertThatThrownBy(() -> service.sendCode(
                        access(), VerificationDeliveryMethod.WHATSAPP))
                .isInstanceOfSatisfying(PasswordResetException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(PasswordResetErrorCode.INVALID_INPUT));

        verify(codeGenerator, never()).generate();
        verifyNoInteractions(publisher);
    }

    private static PasswordResetAccess access() {
        return new PasswordResetAccess(
                "flow-token", "challenge", "device-id", "203.0.113.10");
    }

    private static PasswordResetFlowSnapshot phoneFlow(String identifier) {
        return new PasswordResetFlowSnapshot(
                VerificationChannel.SMS,
                identifier,
                42L,
                true,
                NOW.minusSeconds(10),
                NOW.plusSeconds(300),
                NOW.plusSeconds(600));
    }
}
