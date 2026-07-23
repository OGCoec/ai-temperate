package com.example.temperate.service.registration.verification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodeCommand;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.RegistrationStatus;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.flow.domain.RegistrationFlowSnapshot;
import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import com.example.temperate.service.registration.flow.security.RegistrationAccess;
import com.example.temperate.service.registration.flow.security.RegistrationTokenProtector;
import com.example.temperate.service.registration.flow.store.RegistrationFlowStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证共享 Redis 校验器只传递 HMAC 摘要、拒绝非法验证码并返回原子状态机产生的注册状态。
 */
class RedisSixDigitVerificationCodeVerifierImplTest {

    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
    private static final HmacIdentifier DIGEST =
            HmacIdentifier.fromProtectedValue("A".repeat(43));

    private RegistrationFlowStore flowStore;
    private RegistrationTokenProtector tokenProtector;
    private ProtectedRegistrationAccess protectedAccess;
    private RedisSixDigitVerificationCodeVerifierImpl verifier;

    @BeforeEach
    void setUp() {
        flowStore = mock(RegistrationFlowStore.class);
        tokenProtector = mock(RegistrationTokenProtector.class);
        protectedAccess = mock(ProtectedRegistrationAccess.class);
        verifier = new RedisSixDigitVerificationCodeVerifierImpl(
                flowStore,
                tokenProtector,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void validCodeUsesDigestAndMapsReadyState() {
        RegistrationVerifyCodeCommand command = command("012345");
        when(tokenProtector.protect(command.access())).thenReturn(protectedAccess);
        when(tokenProtector.codeDigest(
                "register-token", VerificationChannel.SMS, "012345"))
                .thenReturn(DIGEST);
        when(flowStore.verifyCode(
                protectedAccess, VerificationChannel.SMS, DIGEST, NOW))
                .thenReturn(new RegistrationFlowSnapshot(
                        "alice@example.test",
                        "+447911123456",
                        true,
                        true,
                        true,
                        false,
                        NOW.minusSeconds(60),
                        NOW.plusSeconds(540)));

        var result = verifier.verify(command);

        assertThat(result.status()).isEqualTo(RegistrationStatus.READY_TO_COMPLETE);
        assertThat(result.email()).isEqualTo("alice@example.test");
        assertThat(result.phoneE164()).isEqualTo("+447911123456");
        verify(flowStore).verifyCode(
                protectedAccess, VerificationChannel.SMS, DIGEST, NOW);
    }

    @Test
    void invalidCodeIsRejectedBeforeRedisOrDigestCalculation() {
        RegistrationVerifyCodeCommand command = command("12345");

        assertThatThrownBy(() -> verifier.verify(command))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(RegistrationErrorCode.VERIFICATION_CODE_INVALID));
        verify(tokenProtector, never()).codeDigest(any(), any(), any());
        verify(flowStore, never()).verifyCode(any(), any(), any(), any());
    }

    @Test
    void storeBusinessErrorsArePreservedForAttemptsAndExpiry() {
        RegistrationVerifyCodeCommand command = command("012345");
        when(tokenProtector.protect(command.access())).thenReturn(protectedAccess);
        when(tokenProtector.codeDigest(any(), eq(VerificationChannel.SMS), any()))
                .thenReturn(DIGEST);
        RegistrationException exhausted = new RegistrationException(
                RegistrationErrorCode.VERIFICATION_CODE_ATTEMPTS_EXHAUSTED,
                "Verification attempts exhausted.");
        when(flowStore.verifyCode(any(), eq(VerificationChannel.SMS), eq(DIGEST), eq(NOW)))
                .thenThrow(exhausted);

        assertThatThrownBy(() -> verifier.verify(command)).isSameAs(exhausted);
    }

    private static RegistrationVerifyCodeCommand command(String code) {
        return new RegistrationVerifyCodeCommand(
                new RegistrationAccess(
                        "register-token",
                        "flow-csrf",
                        "challenge-handle",
                        "550e8400-e29b-41d4-a716-446655440000",
                        "203.0.113.7"),
                VerificationChannel.SMS,
                code);
    }
}
