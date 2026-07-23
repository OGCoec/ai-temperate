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

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.service.auth.login.code.dto.LoginCodeAccess;
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
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.service.turnstile.TurnstileVerificationService;
import com.example.temperate.service.registration.verification.delivery.operation.VerificationDeliveryOperationIdGenerator;
import com.example.temperate.service.registration.verification.delivery.rabbit.VerificationDeliveryPublisher;
import com.example.temperate.service.registration.verification.generator.VerificationCodeGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证手机号登录选择 WhatsApp 时仍复用 SMS 验证因子状态，并在服务端拒绝中国号码和渠道错配。
 */
class LoginCodeDeliveryMethodTest {

    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");
    private static final HmacIdentifier HMAC =
            HmacIdentifier.fromProtectedValue("A".repeat(43));

    private LoginCodeFlowStore flowStore;
    private AuthSessionSecretProtector protector;
    private LoginRateLimitService rateLimitService;
    private VerificationCodeGenerator codeGenerator;
    private VerificationDeliveryPublisher publisher;
    private LoginCodeFlowServiceImpl service;

    @BeforeEach
    void setUp() {
        flowStore = mock(LoginCodeFlowStore.class);
        protector = mock(AuthSessionSecretProtector.class);
        rateLimitService = mock(LoginRateLimitService.class);
        codeGenerator = mock(VerificationCodeGenerator.class);
        publisher = mock(VerificationDeliveryPublisher.class);
        when(protector.loginFlowToken(anyString())).thenReturn(HMAC);
        when(protector.loginChallenge(anyString())).thenReturn(HMAC);
        when(protector.device(anyString())).thenReturn(HMAC);
        when(protector.loginCodeKey(anyString())).thenReturn(HMAC);
        when(protector.loginCodeDigest(anyString(), anyString())).thenReturn(HMAC);
        when(protector.loginDeliveryOperation(anyString())).thenReturn(HMAC);
        when(rateLimitService.check(any(), eq(LoginFailureBucket.CODE)))
                .thenReturn(LoginLimitDecision.ALLOWED);
        when(codeGenerator.generate()).thenReturn("012345");
        service = new LoginCodeFlowServiceImpl(
                mock(UserLoginIdentityMapper.class),
                new RegistrationInputNormalizer(),
                flowStore,
                protector,
                mock(AuthTokenService.class),
                mock(TurnstileVerificationService.class),
                codeGenerator,
                new VerificationDeliveryOperationIdGenerator(),
                publisher,
                mock(LoginAccountNotificationService.class),
                rateLimitService,
                mock(LoginSessionIssuer.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
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
