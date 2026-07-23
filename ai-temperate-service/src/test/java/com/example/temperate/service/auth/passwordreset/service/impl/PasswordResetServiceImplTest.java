package com.example.temperate.service.auth.passwordreset.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.service.auth.password.policy.PasswordStrengthPolicy;
import com.example.temperate.service.auth.passwordreset.PasswordResetErrorCode;
import com.example.temperate.service.auth.passwordreset.PasswordResetException;
import com.example.temperate.service.auth.passwordreset.flow.PasswordResetFlowStore;
import com.example.temperate.service.auth.passwordreset.notification.PasswordResetNotificationService;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.session.authentication.service.SessionAuthenticationService;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import com.example.temperate.service.registration.service.turnstile.TurnstileVerificationService;
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
                mock(TurnstileVerificationService.class),
                mock(VerificationCodeGenerator.class),
                new VerificationDeliveryOperationIdGenerator(),
                mock(VerificationDeliveryPublisher.class),
                mock(PasswordResetNotificationService.class),
                mock(SessionAuthenticationService.class),
                passwordEncoder,
                Clock.systemUTC());

        assertThatThrownBy(() -> service.complete(
                "masked-forget-token", "masked-device-id", "1234567", "1234567"))
                .isInstanceOfSatisfying(PasswordResetException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                PasswordResetErrorCode.PASSWORD_STRENGTH_INSUFFICIENT));

        verifyNoInteractions(flowStore, passwordEncoder, identityMapper);
    }
}
