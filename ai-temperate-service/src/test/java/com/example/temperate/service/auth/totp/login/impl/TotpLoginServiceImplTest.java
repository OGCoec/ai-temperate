package com.example.temperate.service.auth.totp.login.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.domain.TotpCredential;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.login.session.LoginSessionIssuer;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.auth.totp.algorithm.TotpCodeService;
import com.example.temperate.service.auth.totp.config.TotpProperties;
import com.example.temperate.service.auth.totp.login.store.TotpLoginChallengeSnapshot;
import com.example.temperate.service.auth.totp.login.store.TotpLoginChallengeStore;
import com.example.temperate.service.auth.totp.security.TotpSecretProtector;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证 TOTP 登录挑战的状态重读、验证码校验、一次性消费和最终会话签发顺序。
 */
class TotpLoginServiceImplTest {

    private static final long USER_ID = 10001L;
    private static final String DEVICE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String FLOW_TOKEN = "A2345678901234567890123456789012345678";
    private static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");
    private static final HmacIdentifier HMAC =
            HmacIdentifier.fromProtectedValue("A".repeat(43));

    private TotpLoginChallengeStore store;
    private UserLoginIdentityMapper mapper;
    private TotpCodeService codes;
    private TotpSecretProtector secretProtector;
    private LoginSessionIssuer sessionIssuer;
    private AuthSessionSecretProtector identifierProtector;
    private TotpLoginServiceImpl service;

    @BeforeEach
    void setUp() {
        store = mock(TotpLoginChallengeStore.class);
        mapper = mock(UserLoginIdentityMapper.class);
        codes = mock(TotpCodeService.class);
        secretProtector = mock(TotpSecretProtector.class);
        sessionIssuer = mock(LoginSessionIssuer.class);
        identifierProtector = mock(AuthSessionSecretProtector.class);
        when(identifierProtector.totpLoginFlowToken(FLOW_TOKEN)).thenReturn(HMAC);
        when(identifierProtector.device(DEVICE_ID)).thenReturn(HMAC);
        when(identifierProtector.totpUsedTimeStep(anyLong(), anyLong()))
                .thenReturn(HMAC);
        service = new TotpLoginServiceImpl(
                store,
                mapper,
                codes,
                secretProtector,
                sessionIssuer,
                mock(AuthTokenService.class),
                identifierProtector,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void verifiesCurrentDatabaseSecretConsumesChallengeThenIssuesSession() {
        AuthenticationContext context = context();
        byte[] secret = new byte[32];
        LoginResult authenticated = new LoginResult(
                "AAAAAAAAAAE", "用户", "access", "refresh", "csrf",
                NOW.plusSeconds(10_800));
        when(store.getRequired(any(), any(), any()))
                .thenReturn(new TotpLoginChallengeSnapshot(
                        USER_ID, 0, NOW.plusSeconds(300)));
        when(mapper.findAuthenticationById(USER_ID)).thenReturn(context);
        when(mapper.findTotpCredentialById(USER_ID))
                .thenReturn(new TotpCredential(USER_ID, true, "encrypted"));
        when(secretProtector.decrypt(USER_ID, "encrypted")).thenReturn(secret);
        when(codes.findMatchingTimeStep(secret, "012345", NOW))
                .thenReturn(OptionalLong.of(NOW.getEpochSecond() / 30L));
        when(sessionIssuer.issue(context, DEVICE_ID)).thenReturn(authenticated);

        LoginResult result = service.verify(FLOW_TOKEN, DEVICE_ID, "012345");

        assertThat(result).isSameAs(authenticated);
        verify(store).consumeSuccessful(any(), any(), any(), any());
        verify(sessionIssuer).issue(context, DEVICE_ID);
    }

    @Test
    void invalidCodeRecordsFailureAndNeverIssuesSession() {
        when(store.getRequired(any(), any(), any()))
                .thenReturn(new TotpLoginChallengeSnapshot(
                        USER_ID, 0, NOW.plusSeconds(300)));
        when(mapper.findAuthenticationById(USER_ID)).thenReturn(context());
        when(mapper.findTotpCredentialById(USER_ID))
                .thenReturn(new TotpCredential(USER_ID, true, "encrypted"));
        when(secretProtector.decrypt(USER_ID, "encrypted")).thenReturn(new byte[32]);
        when(codes.findMatchingTimeStep(any(), any(), any()))
                .thenReturn(OptionalLong.empty());

        assertThatThrownBy(() -> service.verify(FLOW_TOKEN, DEVICE_ID, "999999"))
                .isInstanceOfSatisfying(LoginException.class, exception ->
                        assertThat(exception.code()).isEqualTo(LoginErrorCode.TOTP_CODE_INVALID));

        verify(store).recordFailure(any(), any(), any());
        verify(sessionIssuer, never()).issue(any(), any());
    }

    private static AuthenticationContext context() {
        return new AuthenticationContext(
                USER_ID,
                "{bcrypt}hash",
                1L,
                AccountStatus.ACTIVE,
                "用户",
                "user@example.test",
                null,
                true);
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
