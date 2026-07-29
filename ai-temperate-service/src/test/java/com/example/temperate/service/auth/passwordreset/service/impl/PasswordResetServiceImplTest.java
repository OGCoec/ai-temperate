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

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceDecision;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceFilter;
import com.example.temperate.service.auth.password.policy.PasswordStrengthPolicy;
import com.example.temperate.service.auth.passwordreset.PasswordResetErrorCode;
import com.example.temperate.service.auth.passwordreset.PasswordResetException;
import com.example.temperate.service.auth.passwordreset.dto.PasswordResetStartCommand;
import com.example.temperate.service.auth.passwordreset.flow.PasswordResetFlowStore;
import com.example.temperate.service.auth.passwordreset.notification.PasswordResetNotificationService;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.session.authentication.service.SessionAuthenticationService;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.humanverification.HumanVerificationServiceRegistry;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.verification.delivery.operation.VerificationDeliveryOperationIdGenerator;
import com.example.temperate.service.registration.verification.delivery.rabbit.VerificationDeliveryPublisher;
import com.example.temperate.service.registration.verification.generator.VerificationCodeGenerator;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 验证密码重置在领取凭据、哈希和数据库写入前执行统一强度门禁。
 */
class PasswordResetServiceImplTest {

    @Test
    void weakPasswordDoesNotClaimFlowHashOrWriteDatabase() {
        UserLoginIdentityMapper identityMapper = mock(UserLoginIdentityMapper.class);
        PasswordResetFlowStore flowStore = mock(PasswordResetFlowStore.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        PasswordResetServiceImpl service = new PasswordResetServiceImpl(
                identityMapper,
                new RegistrationInputNormalizer(),
                new PasswordStrengthPolicy(),
                flowStore,
                mock(AuthSessionSecretProtector.class),
                mock(AuthTokenService.class),
                mock(HumanVerificationServiceRegistry.class),
                mock(VerificationCodeGenerator.class),
                new VerificationDeliveryOperationIdGenerator(),
                mock(VerificationDeliveryPublisher.class),
                mock(PasswordResetNotificationService.class),
                mock(SessionAuthenticationService.class),
                passwordEncoder,
                mock(com.example.temperate.service.auth.identity.bloom.IdentityPresenceFilter.class),
                Clock.systemUTC());

        assertThatThrownBy(() -> service.complete(
                "masked-forget-token", "masked-device-id", "1234567", "1234567"))
                .isInstanceOfSatisfying(PasswordResetException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                PasswordResetErrorCode.PASSWORD_STRENGTH_INSUFFICIENT));

        verifyNoInteractions(flowStore, passwordEncoder, identityMapper);
    }

    @Test
    void definiteBloomMissCreatesFakeResetFlowWithoutDatabaseLookup() {
        UserLoginIdentityMapper identityMapper = mock(UserLoginIdentityMapper.class);
        PasswordResetFlowStore flowStore = mock(PasswordResetFlowStore.class);
        IdentityPresenceFilter identityPresenceFilter = mock(IdentityPresenceFilter.class);
        AuthSessionSecretProtector protector = mock(AuthSessionSecretProtector.class);
        AuthTokenService tokenService = mock(AuthTokenService.class);
        HmacIdentifier hmac = HmacIdentifier.fromProtectedValue("A".repeat(43));
        when(identityPresenceFilter.checkEmail("missing@example.com"))
                .thenReturn(IdentityPresenceDecision.DEFINITELY_ABSENT);
        when(tokenService.newFlowToken()).thenReturn("flow-token", "challenge-token");
        when(protector.passwordResetFlowToken(anyString())).thenReturn(hmac);
        when(protector.passwordResetChallenge(anyString())).thenReturn(hmac);
        when(protector.device(anyString())).thenReturn(hmac);
        when(protector.deviceBlock(anyString())).thenReturn(hmac);
        when(protector.passwordResetCodeKey(anyString())).thenReturn(hmac);
        when(protector.passwordResetTarget(anyString())).thenReturn(hmac);

        PasswordResetServiceImpl service = new PasswordResetServiceImpl(
                identityMapper,
                new RegistrationInputNormalizer(),
                new PasswordStrengthPolicy(),
                flowStore,
                protector,
                tokenService,
                mock(HumanVerificationServiceRegistry.class),
                mock(VerificationCodeGenerator.class),
                new VerificationDeliveryOperationIdGenerator(),
                mock(VerificationDeliveryPublisher.class),
                mock(PasswordResetNotificationService.class),
                mock(SessionAuthenticationService.class),
                mock(PasswordEncoder.class),
                identityPresenceFilter,
                Clock.systemUTC());

        service.start(new PasswordResetStartCommand(
                VerificationChannel.EMAIL,
                "missing@example.com",
                null,
                null,
                "550e8400-e29b-41d4-a716-446655440000",
                "127.0.0.1"));

        verify(identityMapper, never()).findByNormalizedEmail(anyString());
        verify(flowStore).create(
                any(),
                eq(VerificationChannel.EMAIL),
                eq("missing@example.com"),
                eq(0L),
                any());
    }
}
