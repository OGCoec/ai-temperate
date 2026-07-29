package com.example.temperate.service.auth.login.code.service.impl;

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
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceDecision;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceFilter;
import com.example.temperate.service.auth.login.code.dto.LoginCodeAccess;
import com.example.temperate.service.auth.login.code.dto.LoginCodeStartCommand;
import com.example.temperate.service.auth.login.code.flow.LoginCodeFlowSnapshot;
import com.example.temperate.service.auth.login.code.flow.LoginCodeFlowStore;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.login.limit.enums.LoginFailureBucket;
import com.example.temperate.service.auth.login.limit.enums.LoginLimitDecision;
import com.example.temperate.service.auth.login.limit.service.LoginRateLimitService;
import com.example.temperate.service.auth.login.notification.LoginAccountNotificationService;
import com.example.temperate.service.auth.login.session.LoginSessionIssuer;
import com.example.temperate.service.auth.login.strategy.LoginStrategyType;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 验证手机号登录选择 WhatsApp 时仍复用 SMS 验证因子状态，并在服务端拒绝中国号码和渠道错配。
 */
class LoginCodeDeliveryMethodTest {

    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");
    private static final HmacIdentifier HMAC =
            HmacIdentifier.fromProtectedValue("A".repeat(43));

    private LoginCodeFlowStore flowStore;
    private UserLoginIdentityMapper identityMapper;
    private IdentityPresenceFilter identityPresenceFilter;
    private AuthSessionSecretProtector protector;
    private LoginRateLimitService rateLimitService;
    private VerificationCodeGenerator codeGenerator;
    private VerificationDeliveryPublisher publisher;
    private HumanVerificationServiceRegistry humanVerificationServices;
    private HumanVerificationService turnstileService;
    private AuthTokenService tokenService;
    private LoginCodeFlowServiceImpl service;

    @BeforeEach
    void setUp() {
        flowStore = mock(LoginCodeFlowStore.class);
        identityMapper = mock(UserLoginIdentityMapper.class);
        identityPresenceFilter = mock(IdentityPresenceFilter.class);
        protector = mock(AuthSessionSecretProtector.class);
        rateLimitService = mock(LoginRateLimitService.class);
        codeGenerator = mock(VerificationCodeGenerator.class);
        publisher = mock(VerificationDeliveryPublisher.class);
        humanVerificationServices = mock(HumanVerificationServiceRegistry.class);
        turnstileService = mock(HumanVerificationService.class);
        tokenService = mock(AuthTokenService.class);
        when(humanVerificationServices.getRequired(HumanVerificationType.TURNSTILE))
                .thenReturn(turnstileService);
        when(protector.loginFlowToken(anyString())).thenReturn(HMAC);
        when(protector.loginChallenge(anyString())).thenReturn(HMAC);
        when(protector.device(anyString())).thenReturn(HMAC);
        when(protector.loginCodeKey(anyString())).thenReturn(HMAC);
        when(protector.loginCodeDigest(anyString(), anyString())).thenReturn(HMAC);
        when(protector.loginDeliveryOperation(anyString())).thenReturn(HMAC);
        when(rateLimitService.check(any(), eq(LoginFailureBucket.CODE)))
                .thenReturn(LoginLimitDecision.ALLOWED);
        when(identityPresenceFilter.checkEmail(anyString()))
                .thenReturn(IdentityPresenceDecision.UNAVAILABLE);
        when(identityPresenceFilter.checkPhone(anyString()))
                .thenReturn(IdentityPresenceDecision.UNAVAILABLE);
        when(codeGenerator.generate()).thenReturn("012345");
        service = new LoginCodeFlowServiceImpl(
                identityMapper,
                new RegistrationInputNormalizer(),
                flowStore,
                protector,
                tokenService,
                humanVerificationServices,
                codeGenerator,
                new VerificationDeliveryOperationIdGenerator(),
                publisher,
                mock(LoginAccountNotificationService.class),
                rateLimitService,
                mock(LoginSessionIssuer.class),
                identityPresenceFilter,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void definiteBloomMissCreatesSameShapeFakeFlowWithoutDatabaseLookup() {
        when(identityPresenceFilter.checkEmail("missing@example.com"))
                .thenReturn(IdentityPresenceDecision.DEFINITELY_ABSENT);
        when(tokenService.newFlowToken()).thenReturn("flow-token", "challenge-token");

        service.start(new LoginCodeStartCommand(
                LoginStrategyType.EMAIL_CODE,
                "missing@example.com",
                null,
                null,
                "550e8400-e29b-41d4-a716-446655440000",
                "127.0.0.1"));

        verify(identityMapper, never()).findByNormalizedEmail(anyString());
        verify(flowStore).create(
                any(),
                eq(LoginStrategyType.EMAIL_CODE),
                eq("missing@example.com"),
                eq(0L),
                eq(NOW));
    }

    @Test
    void turnstileLoadsFlowThenVerifiesLoginActionAndMarksOnce() {
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
                                && "login".equals(command.expectedAction())));
        verify(humanVerificationServices)
                .getRequired(HumanVerificationType.TURNSTILE);
        ordered.verify(flowStore).markHumanVerified(any(), eq(NOW));
        verify(flowStore).markHumanVerified(any(), eq(NOW));
    }

    @Test
    void turnstileFailureMapsToLoginErrorAndNeverMarksFlow() {
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
                    assertThat(failure).isInstanceOf(LoginException.class);
                    assertThat(((LoginException) failure).code())
                            .isEqualTo(LoginErrorCode.TURNSTILE_REJECTED);
                })
                .verify();

        verify(flowStore, never()).markHumanVerified(any(), any());
    }

    @Test
    void turnstileUnavailablePropagatesWithoutMarkingLoginFlow() {
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
        verify(publisher).publishLogin(
                any(),
                eq(VerificationChannel.SMS),
                eq(VerificationDeliveryMethod.WHATSAPP),
                eq(HMAC),
                any(),
                eq(NOW.plusSeconds(300)));
    }

    @Test
    void missingDeliveryMethodKeepsLegacyPhoneSmsDefault() {
        when(flowStore.getRequired(any(), eq(NOW))).thenReturn(phoneFlow("+447911123456"));

        service.sendCode(access());

        verify(publisher).publishLogin(
                any(),
                eq(VerificationChannel.SMS),
                eq(VerificationDeliveryMethod.SMS),
                eq(HMAC),
                any(),
                eq(NOW.plusSeconds(300)));
    }

    @Test
    void chinaPhoneRejectsWhatsappBeforeCodeOrMessageCreation() {
        when(flowStore.getRequired(any(), eq(NOW))).thenReturn(phoneFlow("+8613800138000"));

        assertThatThrownBy(() -> service.sendCode(
                        access(), VerificationDeliveryMethod.WHATSAPP))
                .isInstanceOfSatisfying(LoginException.class, exception ->
                        assertThat(exception.code()).isEqualTo(LoginErrorCode.INVALID_INPUT));

        verify(codeGenerator, never()).generate();
        verifyNoInteractions(publisher);
    }

    @Test
    void emailFlowRejectsWhatsappBeforeCodeOrMessageCreation() {
        when(flowStore.getRequired(any(), eq(NOW))).thenReturn(new LoginCodeFlowSnapshot(
                LoginStrategyType.EMAIL_CODE,
                "alice@example.test",
                42L,
                true,
                NOW.minusSeconds(10),
                NOW.plusSeconds(300),
                NOW.plusSeconds(600)));

        assertThatThrownBy(() -> service.sendCode(
                        access(), VerificationDeliveryMethod.WHATSAPP))
                .isInstanceOfSatisfying(LoginException.class, exception ->
                        assertThat(exception.code()).isEqualTo(LoginErrorCode.INVALID_INPUT));

        verify(codeGenerator, never()).generate();
        verifyNoInteractions(publisher);
    }

    private static LoginCodeAccess access() {
        return new LoginCodeAccess(
                "flow-token", "challenge", "device-id", "203.0.113.10");
    }

    private static LoginCodeFlowSnapshot phoneFlow(String identifier) {
        return new LoginCodeFlowSnapshot(
                LoginStrategyType.SMS_CODE,
                identifier,
                42L,
                true,
                NOW.minusSeconds(10),
                NOW.plusSeconds(300),
                NOW.plusSeconds(600));
    }
}
