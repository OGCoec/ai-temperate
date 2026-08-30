package com.example.temperate.service.auth.session.authentication.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.session.authentication.dto.command.SessionBootstrapCommand;
import com.example.temperate.service.auth.session.authentication.dto.result.SessionAuthenticationResult;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionSnapshot;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionValidation;
import com.example.temperate.service.auth.session.refresh.store.RefreshSessionStore;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证会话认证、CSRF 轮换、账号状态检查和错误清理指示的业务语义。
 */
class SessionAuthenticationServiceImplTest {

    private static final long USER_ID = 10001L;
    private static final String RT = "A2345678901234567890123456789012345678";
    private static final String DEVICE = "550e8400-e29b-41d4-a716-446655440000";
    private static final String NEW_CSRF = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq";
    private static final Instant EXPIRES_AT = Instant.parse("2026-07-15T09:00:00Z");
    private static final HmacSha256Identifier HMAC = new HmacSha256Identifier(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private AuthTokenService tokenService;
    private RefreshSessionStore sessionStore;
    private AuthSessionSecretProtector protector;
    private UserLoginIdentityMapper identityMapper;
    private SessionAuthenticationServiceImpl service;
    private HmacIdentifier refreshHash;
    private HmacIdentifier deviceHash;
    private String publicId;

    @BeforeEach
    void setUp() {
        tokenService = mock(AuthTokenService.class);
        sessionStore = mock(RefreshSessionStore.class);
        protector = mock(AuthSessionSecretProtector.class);
        identityMapper = mock(UserLoginIdentityMapper.class);
        service = new SessionAuthenticationServiceImpl(
                tokenService, sessionStore, protector, identityMapper);
        refreshHash = id("refresh");
        deviceHash = id("device");
        publicId = new PublicIdCodec().encode(USER_ID);
        when(protector.refreshToken(RT)).thenReturn(refreshHash);
        when(protector.device(DEVICE)).thenReturn(deviceHash);
        when(identityMapper.findAuthenticationById(USER_ID)).thenReturn(
                new AuthenticationContext(
                        USER_ID, "{bcrypt}hash", 3L, AccountStatus.ACTIVE, "用户"));
        when(tokenService.issueAccessToken(USER_ID)).thenReturn("new-access-token");
        clearInvocations(tokenService, sessionStore, protector, identityMapper);
    }

    @Test
    void bootstrapWithoutBindingRejectsMissingRefreshBeforeCallingDependencies() {
        assertThatThrownBy(() -> service.bootstrap(
                new SessionBootstrapCommand(null, null, DEVICE)))
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo(SessionAuthenticationErrorCode.REFRESH_TOKEN_REQUIRED);
                    assertThat(exception.clearCookies()).isTrue();
                });

        verifyNoInteractions(tokenService, sessionStore, protector, identityMapper);
    }

    @Test
    void bootstrapWithBindingRejectsBlankRefreshBeforeCallingDependencies() {
        PreAuthSessionBinding binding = mock(PreAuthSessionBinding.class);

        assertThatThrownBy(() -> service.bootstrap(
                new SessionBootstrapCommand(null, "  ", DEVICE),
                binding))
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo(SessionAuthenticationErrorCode.REFRESH_TOKEN_REQUIRED);
                    assertThat(exception.clearCookies()).isTrue();
                });

        verifyNoInteractions(tokenService, sessionStore, protector, identityMapper, binding);
    }

    @Test
    void bootstrapChangesCsrfButKeepsTheSameRtHash() {
        HmacIdentifier newCsrfHash = id("new-csrf");
        when(tokenService.newCsrfToken()).thenReturn(NEW_CSRF);
        when(protector.csrf(NEW_CSRF)).thenReturn(newCsrfHash);
        when(sessionStore.bootstrapAndRenew(refreshHash, deviceHash, newCsrfHash))
                .thenReturn(new RefreshSessionValidation(
                        RefreshSessionValidation.Status.VALID, snapshot(newCsrfHash)));

        SessionAuthenticationResult result = service.bootstrap(
                new SessionBootstrapCommand(null, RT, DEVICE));

        assertThat(result.getCsrfToken()).isEqualTo(NEW_CSRF);
        verify(sessionStore).bootstrapAndRenew(refreshHash, deviceHash, newCsrfHash);
        verify(tokenService).issueAccessToken(USER_ID);
    }

    @Test
    void logoutAllRetriesTheWholeBatchAndReturnsTheRevokedCount() {
        when(sessionStore.revokeAllForUser(USER_ID))
                .thenThrow(new IllegalStateException("redis unavailable"))
                .thenThrow(new IllegalStateException("redis unavailable"))
                .thenReturn(3);

        int revokedCount = service.logoutAllForUser(USER_ID);

        assertThat(revokedCount).isEqualTo(3);
        verify(sessionStore, org.mockito.Mockito.times(3)).revokeAllForUser(USER_ID);
    }

    @Test
    void logoutAllMapsThreeFailedBatchAttemptsToInfrastructureUnavailable() {
        when(sessionStore.revokeAllForUser(USER_ID))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThatThrownBy(() -> service.logoutAllForUser(USER_ID))
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo(SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE);
                    assertThat(exception.getCause()).isInstanceOf(IllegalStateException.class);
                });

        verify(sessionStore, org.mockito.Mockito.times(3)).revokeAllForUser(USER_ID);
    }

    @Test
    void logoutAllRejectsInvalidUserWithoutCallingRedis() {
        assertThatThrownBy(() -> service.logoutAllForUser(0L))
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(SessionAuthenticationErrorCode.INVALID_INPUT));

        verifyNoInteractions(sessionStore);
    }

    private RefreshSessionSnapshot snapshot(HmacIdentifier currentCsrfHash) {
        return new RefreshSessionSnapshot(
                USER_ID,
                publicId,
                currentCsrfHash.value(),
                "person@example.test",
                "+8613812345678",
                deviceHash.value(),
                EXPIRES_AT);
    }

    private static HmacIdentifier id(String value) {
        return HMAC.identify(value);
    }
}
