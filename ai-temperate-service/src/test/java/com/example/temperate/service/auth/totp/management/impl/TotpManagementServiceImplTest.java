package com.example.temperate.service.auth.totp.management.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.domain.TotpCredential;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.session.authentication.service.SessionAuthenticationService;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.auth.totp.algorithm.TotpCodeService;
import com.example.temperate.service.auth.totp.config.TotpProperties;
import com.example.temperate.service.auth.totp.management.TotpManagementAction;
import com.example.temperate.service.auth.totp.management.dto.TotpSetupResult;
import com.example.temperate.service.auth.totp.management.store.TotpSetupSnapshot;
import com.example.temperate.service.auth.totp.management.store.TotpSetupStore;
import com.example.temperate.service.auth.totp.security.TotpSecretProtector;
import com.example.temperate.service.auth.totp.stepup.TotpStepUpService;
import com.example.temperate.service.auth.totp.verification.CurrentTotpVerificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证 TOTP 开启、轮换和关闭流程保持数据库当前密钥与 Redis 待确认密钥之间的边界。
 */
class TotpManagementServiceImplTest {

    private static final long USER_ID = 10001L;
    private static final String DEVICE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");

    private UserLoginIdentityMapper mapper;
    private TotpStepUpService stepUpService;
    private TotpCodeService codeService;
    private TotpSecretProtector secretProtector;
    private TotpSetupStore setupStore;
    private CurrentTotpVerificationService currentTotpVerificationService;
    private AuthTokenService tokenService;
    private SessionAuthenticationService sessionAuthenticationService;
    private TotpManagementServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(UserLoginIdentityMapper.class);
        stepUpService = mock(TotpStepUpService.class);
        codeService = mock(TotpCodeService.class);
        secretProtector = mock(TotpSecretProtector.class);
        setupStore = mock(TotpSetupStore.class);
        currentTotpVerificationService = mock(CurrentTotpVerificationService.class);
        tokenService = mock(AuthTokenService.class);
        sessionAuthenticationService = mock(SessionAuthenticationService.class);
        service = new TotpManagementServiceImpl(
                mapper,
                stepUpService,
                codeService,
                secretProtector,
                setupStore,
                currentTotpVerificationService,
                tokenService,
                sessionAuthenticationService,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void startsTenMinuteSetupWithoutWritingDatabase() {
        byte[] secret = new byte[32];
        when(mapper.findAuthenticationById(USER_ID)).thenReturn(context(false));
        when(mapper.findTotpCredentialById(USER_ID))
                .thenReturn(new TotpCredential(USER_ID, false, null));
        when(codeService.newSecret()).thenReturn(secret);
        when(codeService.encodeBase32(secret)).thenReturn("BASE32SECRET");
        when(codeService.provisioningUri("user@example.test", secret))
                .thenReturn("otpauth://totp/example");
        when(secretProtector.encrypt(USER_ID, secret)).thenReturn("encrypted-new-secret");
        when(tokenService.newFlowToken()).thenReturn("setup-token");

        TotpSetupResult result = service.startSetup(
                USER_ID,
                DEVICE_ID,
                TotpManagementAction.ENABLE,
                "step-up-proof",
                null);

        assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(result.secretBase32()).isEqualTo("BASE32SECRET");
        verify(setupStore).save(
                USER_ID,
                "setup-token",
                DEVICE_ID,
                "encrypted-new-secret",
                TotpManagementAction.ENABLE,
                false,
                null,
                NOW,
                Duration.ofMinutes(10));
        verify(mapper, never()).enableOrRotateTotp(
                anyLong(), any(), anyBoolean(), nullable(String.class));
    }

    @Test
    void confirmWritesNewSecretOnlyAfterNewCodeMatches() {
        when(setupStore.getRequired(USER_ID, "setup-token", DEVICE_ID, NOW))
                .thenReturn(new TotpSetupSnapshot(
                        "encrypted-new-secret",
                        TotpManagementAction.ENABLE,
                        false,
                        null,
                        0,
                        NOW.plusSeconds(600)));
        byte[] secret = new byte[32];
        when(secretProtector.decrypt(USER_ID, "encrypted-new-secret"))
                .thenReturn(secret);
        when(codeService.findMatchingTimeStep(secret, "012345", NOW))
                .thenReturn(OptionalLong.of(NOW.getEpochSecond() / 30));
        when(mapper.findTotpCredentialById(USER_ID))
                .thenReturn(new TotpCredential(USER_ID, false, null));
        when(mapper.enableOrRotateTotp(
                USER_ID, "encrypted-new-secret", false, null))
                .thenReturn(1);

        var result = service.confirmSetup(
                USER_ID, DEVICE_ID, "setup-token", "012345");

        assertThat(result.enabled()).isTrue();
        assertThat(result.reauthenticationRequired()).isTrue();
        verify(mapper).enableOrRotateTotp(
                USER_ID, "encrypted-new-secret", false, null);
    }

    @Test
    void staleRotationCannotOverwriteASecretChangedAfterSetupStarted() {
        when(setupStore.getRequired(USER_ID, "setup-token", DEVICE_ID, NOW))
                .thenReturn(new TotpSetupSnapshot(
                        "encrypted-pending-secret",
                        TotpManagementAction.ROTATE,
                        true,
                        "encrypted-original-secret",
                        0,
                        NOW.plusSeconds(600)));
        byte[] secret = new byte[32];
        when(secretProtector.decrypt(USER_ID, "encrypted-pending-secret"))
                .thenReturn(secret);
        when(codeService.findMatchingTimeStep(secret, "012345", NOW))
                .thenReturn(OptionalLong.of(NOW.getEpochSecond() / 30));
        when(mapper.findTotpCredentialById(USER_ID))
                .thenReturn(new TotpCredential(
                        USER_ID, true, "encrypted-intervening-secret"));

        assertThatThrownBy(() -> service.confirmSetup(
                USER_ID, DEVICE_ID, "setup-token", "012345"))
                .isInstanceOfSatisfying(
                        LoginException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(LoginErrorCode.TOTP_STATE_CONFLICT));

        verify(mapper, never()).enableOrRotateTotp(
                anyLong(), any(), anyBoolean(), nullable(String.class));
    }

    @Test
    void rotatingRequiresCurrentTotpAndDoesNotOverwriteOldSecretAtStart() {
        when(mapper.findAuthenticationById(USER_ID)).thenReturn(context(true));
        when(mapper.findTotpCredentialById(USER_ID))
                .thenReturn(new TotpCredential(USER_ID, true, "encrypted-old-secret"));
        when(codeService.newSecret()).thenReturn(new byte[32]);
        when(codeService.encodeBase32(any())).thenReturn("NEWBASE32");
        when(codeService.provisioningUri(any(), any()))
                .thenReturn("otpauth://totp/new");
        when(secretProtector.encrypt(eq(USER_ID), any()))
                .thenReturn("encrypted-new-secret");
        when(tokenService.newFlowToken()).thenReturn("setup-token");

        service.startSetup(
                USER_ID,
                DEVICE_ID,
                TotpManagementAction.ROTATE,
                "step-up-proof",
                "654321");

        verify(currentTotpVerificationService).verifyAndClaim(
                USER_ID, "654321", NOW);
        verify(mapper, never()).enableOrRotateTotp(
                anyLong(), any(), anyBoolean(), nullable(String.class));
    }

    @Test
    void invalidCurrentTotpCountsAgainstTheStepUpProof() {
        when(mapper.findAuthenticationById(USER_ID)).thenReturn(context(true));
        when(mapper.findTotpCredentialById(USER_ID))
                .thenReturn(new TotpCredential(
                        USER_ID, true, "encrypted-old-secret"));
        doThrow(new LoginException(
                LoginErrorCode.TOTP_CODE_INVALID,
                "TOTP code is invalid."))
                .when(currentTotpVerificationService)
                .verifyAndClaim(USER_ID, "111111", NOW);

        assertThatThrownBy(() -> service.startSetup(
                USER_ID,
                DEVICE_ID,
                TotpManagementAction.ROTATE,
                "step-up-proof",
                "111111"))
                .isInstanceOf(LoginException.class);

        verify(stepUpService).recordProofFailure(
                USER_ID,
                DEVICE_ID,
                TotpManagementAction.ROTATE,
                "step-up-proof");
        verify(stepUpService, never()).consumeProof(
                USER_ID,
                DEVICE_ID,
                TotpManagementAction.ROTATE,
                "step-up-proof");
    }

    @Test
    void disableClearsStoredSecretAndRevokesAllRefreshSessions() {
        when(mapper.findTotpCredentialById(USER_ID))
                .thenReturn(new TotpCredential(
                        USER_ID, true, "encrypted-current-secret"));
        when(mapper.disableTotp(USER_ID)).thenReturn(1);

        var result = service.disable(
                USER_ID, DEVICE_ID, "step-up-proof", "654321");

        assertThat(result.enabled()).isFalse();
        assertThat(result.reauthenticationRequired()).isTrue();
        verify(stepUpService).consumeProof(
                USER_ID,
                DEVICE_ID,
                TotpManagementAction.DISABLE,
                "step-up-proof");
        verify(stepUpService).requireProof(
                USER_ID,
                DEVICE_ID,
                TotpManagementAction.DISABLE,
                "step-up-proof");
        verify(currentTotpVerificationService).verifyAndClaim(
                USER_ID, "654321", NOW);
        verify(mapper).disableTotp(USER_ID);
        verify(setupStore).deleteForUser(USER_ID);
        verify(sessionAuthenticationService).logoutAllForUser(USER_ID);
    }

    private static AuthenticationContext context(boolean enabled) {
        return new AuthenticationContext(
                USER_ID,
                "{bcrypt}hash",
                1L,
                AccountStatus.ACTIVE,
                "用户",
                "user@example.test",
                null,
                enabled);
    }

    private static TotpProperties properties() {
        return new TotpProperties(
                "AI Temperate", 32, 6, Duration.ofSeconds(30), 1,
                Duration.ofMinutes(10), Duration.ofMinutes(5),
                Duration.ofMinutes(5), 5,
                new TotpProperties.Encryption(
                        "v1", Base64.getEncoder().encodeToString(new byte[32])));
    }
}
