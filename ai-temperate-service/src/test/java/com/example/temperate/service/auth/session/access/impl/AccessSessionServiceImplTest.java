package com.example.temperate.service.auth.session.access.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.session.access.dto.SessionAccessCommand;
import com.example.temperate.service.auth.session.access.dto.SessionAccessResult;
import com.example.temperate.service.auth.session.access.dto.SessionBindingAccessCommand;
import com.example.temperate.service.auth.session.access.observability.AccessSessionMetrics;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionSnapshot;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionValidation;
import com.example.temperate.service.auth.session.refresh.store.RefreshSessionStore;
import com.example.temperate.service.auth.session.token.dto.result.VerifiedAccessToken;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 验证普通用户请求严格执行 RT-first 会话校验，并且只为签名合法但已过期的 AT 执行同请求续签。
 */
class AccessSessionServiceImplTest {

    private static final long USER_ID = 10001L;
    private static final String ACCESS_TOKEN = "signed-access-token";
    private static final String REFRESH_TOKEN = "0123456789abcdefghijklmnopqrstuvwxyzAB";
    private static final String DEVICE_ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final String CSRF_TOKEN =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final Instant ISSUED_AT = Instant.parse("2026-08-04T12:00:00Z");
    private static final Instant ACCESS_EXPIRES_AT = Instant.parse("2026-08-04T12:10:00Z");
    private static final Instant REFRESH_EXPIRES_AT = Instant.parse("2026-08-04T15:00:00Z");

    private AuthTokenService tokenService;
    private RefreshSessionStore refreshSessionStore;
    private UserLoginIdentityMapper identityMapper;
    private AuthSessionSecretProtector protector;
    private AccessSessionServiceImpl service;
    private RefreshSessionSnapshot session;
    private String publicId;

    @BeforeEach
    void setUp() {
        tokenService = mock(AuthTokenService.class);
        refreshSessionStore = mock(RefreshSessionStore.class);
        identityMapper = mock(UserLoginIdentityMapper.class);
        publicId = new PublicIdCodec().encode(USER_ID);
        protector = new AuthSessionSecretProtector(
                new HmacSha256Identifier(
                        "0123456789abcdef0123456789abcdef"
                                .getBytes(StandardCharsets.UTF_8)));
        service = new AccessSessionServiceImpl(
                tokenService,
                refreshSessionStore,
                protector,
                identityMapper,
                mock(AccessSessionMetrics.class));
        session = new RefreshSessionSnapshot(
                USER_ID,
                publicId,
                protector.csrf(CSRF_TOKEN).value(),
                "person@example.test",
                "+8613812345678",
                protector.device(DEVICE_ID).value(),
                REFRESH_EXPIRES_AT);
    }

    @Test
    void rejectsMissingRefreshSessionBeforeParsingAccessTokenOrQueryingAccount() {
        when(refreshSessionStore.validateForAccess(any(), any(), any()))
                .thenReturn(validation(
                        RefreshSessionValidation.Status.MISSING_OR_EXPIRED, null));

        assertThatThrownBy(() -> service.authenticateOrRenew(command(ACCESS_TOKEN)))
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                SessionAuthenticationErrorCode.REFRESH_TOKEN_INVALID));

        verify(refreshSessionStore).validateForAccess(any(), any(), any());
        verifyNoInteractions(tokenService, identityMapper);
    }

    @Test
    void accessCommandAndResultDebugTextNeverExposeSessionCredentials() {
        SessionAccessCommand command = command(ACCESS_TOKEN);
        SessionAccessResult result = new SessionAccessResult(
                new SessionPrincipal(USER_ID, publicId, "User"),
                true,
                "new-access-token",
                REFRESH_EXPIRES_AT);

        assertThat(command.toString())
                .contains("redacted")
                .doesNotContain(ACCESS_TOKEN, REFRESH_TOKEN, CSRF_TOKEN, DEVICE_ID);
        assertThat(result.toString())
                .contains("redacted")
                .doesNotContain("new-access-token", publicId);
    }

    @Test
    void rejectsTamperedAccessTokenWithoutRenewingTheValidRefreshSession() {
        when(refreshSessionStore.validateForAccess(any(), any(), any()))
                .thenReturn(valid(session));
        when(tokenService.verifyAccessToken(ACCESS_TOKEN))
                .thenThrow(new IllegalArgumentException("invalid signature"));

        assertThatThrownBy(() -> service.authenticateOrRenew(command(ACCESS_TOKEN)))
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                SessionAuthenticationErrorCode.ACCESS_TOKEN_INVALID));

        InOrder order = inOrder(refreshSessionStore, tokenService);
        order.verify(refreshSessionStore).validateForAccess(any(), any(), any());
        order.verify(tokenService).verifyAccessToken(ACCESS_TOKEN);
        verify(refreshSessionStore, never()).validateAndRenew(any(), any(), any());
        verifyNoInteractions(identityMapper);
    }

    @Test
    void rejectsMissingAccessTokenAfterRefreshSessionValidationWithoutRenewing() {
        when(refreshSessionStore.validateForAccess(any(), any(), any()))
                .thenReturn(valid(session));

        assertThatThrownBy(() -> service.authenticateOrRenew(command(null)))
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                SessionAuthenticationErrorCode.ACCESS_TOKEN_REQUIRED));

        verify(refreshSessionStore).validateForAccess(any(), any(), any());
        verify(refreshSessionStore, never()).validateAndRenew(any(), any(), any());
        verifyNoInteractions(tokenService, identityMapper);
    }

    @Test
    void rejectsAccessAndRefreshSessionsFromDifferentUsers() {
        when(refreshSessionStore.validateForAccess(any(), any(), any()))
                .thenReturn(valid(session));
        when(tokenService.verifyAccessToken(ACCESS_TOKEN))
                .thenReturn(new VerifiedAccessToken(
                        new PublicIdCodec().encode(20002L),
                        "0123456789abcdefghijklmnopqrstuvwxyzAB",
                        2,
                        ISSUED_AT,
                        ACCESS_EXPIRES_AT,
                        false));

        assertThatThrownBy(() -> service.authenticateOrRenew(command(ACCESS_TOKEN)))
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                SessionAuthenticationErrorCode.SESSION_MISMATCH));

        verifyNoInteractions(identityMapper);
        verify(refreshSessionStore, never()).validateAndRenew(any(), any(), any());
    }

    @Test
    void authenticatesValidAccessTokenWithoutRenewingRefreshTtl() {
        when(refreshSessionStore.validateForAccess(any(), any(), any()))
                .thenReturn(valid(session));
        when(tokenService.verifyAccessToken(ACCESS_TOKEN))
                .thenReturn(access(false));
        when(identityMapper.findAuthenticationById(USER_ID))
                .thenReturn(activeAccount());

        SessionAccessResult result = service.authenticateOrRenew(command(ACCESS_TOKEN));

        assertThat(result.principal().userId()).isEqualTo(USER_ID);
        assertThat(result.renewed()).isFalse();
        assertThat(result.renewedAccessToken()).isNull();
        assertThat(result.refreshExpiresAt()).isEqualTo(REFRESH_EXPIRES_AT);
        verify(refreshSessionStore, never()).validateAndRenew(any(), any(), any());
        verify(tokenService, never()).issueAccessToken(anyLong());
    }

    @Test
    void renewsExpiredSignedAccessTokenInsideTheOriginalRequest() {
        RefreshSessionSnapshot renewedSession = new RefreshSessionSnapshot(
                session.userId(),
                session.publicId(),
                session.csrfHash(),
                session.email(),
                session.phone(),
                session.deviceHash(),
                REFRESH_EXPIRES_AT.plusSeconds(600));
        when(refreshSessionStore.validateForAccess(any(), any(), any()))
                .thenReturn(valid(session));
        when(tokenService.verifyAccessToken(ACCESS_TOKEN))
                .thenReturn(access(true));
        when(identityMapper.findAuthenticationById(USER_ID))
                .thenReturn(activeAccount());
        when(refreshSessionStore.validateAndRenew(any(), any(), any()))
                .thenReturn(valid(renewedSession));
        when(tokenService.issueAccessToken(USER_ID)).thenReturn("new-access-token");

        SessionAccessResult result = service.authenticateOrRenew(command(ACCESS_TOKEN));

        assertThat(result.renewed()).isTrue();
        assertThat(result.renewedAccessToken()).isEqualTo("new-access-token");
        assertThat(result.refreshExpiresAt()).isEqualTo(renewedSession.expiresAt());
        InOrder order = inOrder(refreshSessionStore, tokenService, identityMapper);
        order.verify(refreshSessionStore).validateForAccess(any(), any(), any());
        order.verify(tokenService).verifyAccessToken(ACCESS_TOKEN);
        order.verify(identityMapper).findAuthenticationById(USER_ID);
        order.verify(refreshSessionStore).validateAndRenew(any(), any(), any());
        order.verify(tokenService).issueAccessToken(USER_ID);
    }

    @Test
    void refusesRenewalWhenRefreshSessionIsRevokedDuringTheConcurrencyWindow() {
        when(refreshSessionStore.validateForAccess(any(), any(), any()))
                .thenReturn(valid(session));
        when(tokenService.verifyAccessToken(ACCESS_TOKEN))
                .thenReturn(access(true));
        when(identityMapper.findAuthenticationById(USER_ID))
                .thenReturn(activeAccount());
        when(refreshSessionStore.validateAndRenew(any(), any(), any()))
                .thenReturn(validation(
                        RefreshSessionValidation.Status.MISSING_OR_EXPIRED, null));

        assertThatThrownBy(() -> service.authenticateOrRenew(command(ACCESS_TOKEN)))
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                SessionAuthenticationErrorCode.REFRESH_TOKEN_INVALID));

        verify(tokenService, never()).issueAccessToken(anyLong());
    }

    @Test
    void usesPreAuthAwareReadAndRenewScriptsForExpiredAccessToken() {
        HmacSha256Identifier hmac = new HmacSha256Identifier(
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8));
        PreAuthSessionBinding binding = new PreAuthSessionBinding(
                RiskScope.USER,
                hmac.identify("preauth-token"),
                hmac.identify("preauth-device"),
                RiskSessionType.USER_REFRESH,
                hmac.identify("preauth-session"),
                Duration.ofHours(3),
                false);
        when(refreshSessionStore.validateForAccessWithPreAuth(
                any(), any(), any(), any()))
                .thenReturn(valid(session));
        when(tokenService.verifyAccessToken(ACCESS_TOKEN)).thenReturn(access(true));
        when(identityMapper.findAuthenticationById(USER_ID)).thenReturn(activeAccount());
        when(refreshSessionStore.validateAndRenewWithPreAuth(
                any(), any(), any(), any()))
                .thenReturn(valid(session));
        when(tokenService.issueAccessToken(USER_ID)).thenReturn("new-access-token");

        SessionAccessResult result = service.authenticateOrRenew(
                command(ACCESS_TOKEN), binding);

        assertThat(result.renewed()).isTrue();
        verify(refreshSessionStore).validateForAccessWithPreAuth(
                any(), any(), any(), any());
        verify(refreshSessionStore).validateAndRenewWithPreAuth(
                any(), any(), any(), any());
        verify(refreshSessionStore, never()).validateForAccess(any(), any(), any());
    }

    @Test
    void rejectsARefreshSessionWhosePreAuthBindingIsNoLongerValid() {
        HmacSha256Identifier hmac = new HmacSha256Identifier(
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8));
        PreAuthSessionBinding binding = new PreAuthSessionBinding(
                RiskScope.USER,
                hmac.identify("preauth-token"),
                hmac.identify("preauth-device"),
                RiskSessionType.USER_REFRESH,
                hmac.identify("preauth-session"),
                Duration.ofHours(3),
                false);
        when(refreshSessionStore.validateForAccessWithPreAuth(
                any(), any(), any(), any()))
                .thenReturn(validation(
                        RefreshSessionValidation.Status.PREAUTH_MISMATCH, null));

        assertThatThrownBy(() -> service.authenticateOrRenew(
                command(ACCESS_TOKEN), binding))
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                SessionAuthenticationErrorCode.PREAUTH_REQUIRED));

        verifyNoInteractions(tokenService, identityMapper);
    }

    @Test
    void validatesExistingRefreshBindingWithoutRenewingOrIssuingTokens() {
        when(refreshSessionStore.validateBinding(any(), any())).thenReturn(valid(session));
        when(identityMapper.findAuthenticationById(USER_ID)).thenReturn(activeAccount());

        SessionPrincipal principal = service.validateActiveBinding(
                new SessionBindingAccessCommand(
                        USER_ID,
                        protector.refreshToken(REFRESH_TOKEN),
                        protector.device(DEVICE_ID)));

        assertThat(principal.userId()).isEqualTo(USER_ID);
        verify(refreshSessionStore).validateBinding(any(), any());
        verifyNoInteractions(tokenService);
    }

    @Test
    void rejectsRefreshBindingOwnedByAnotherExpectedUser() {
        when(refreshSessionStore.validateBinding(any(), any())).thenReturn(valid(session));

        assertThatThrownBy(() -> service.validateActiveBinding(
                new SessionBindingAccessCommand(
                        USER_ID + 1,
                        protector.refreshToken(REFRESH_TOKEN),
                        protector.device(DEVICE_ID))))
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                SessionAuthenticationErrorCode.SESSION_MISMATCH));

        verifyNoInteractions(identityMapper, tokenService);
    }

    private SessionAccessCommand command(String accessToken) {
        return new SessionAccessCommand(
                accessToken, REFRESH_TOKEN, CSRF_TOKEN, DEVICE_ID);
    }

    private VerifiedAccessToken access(boolean expired) {
        return new VerifiedAccessToken(
                publicId,
                "0123456789abcdefghijklmnopqrstuvwxyzAB",
                2,
                ISSUED_AT,
                ACCESS_EXPIRES_AT,
                expired);
    }

    private AuthenticationContext activeAccount() {
        return new AuthenticationContext(
                USER_ID, "{bcrypt}test", 1L, AccountStatus.ACTIVE, "User");
    }

    private static RefreshSessionValidation valid(RefreshSessionSnapshot snapshot) {
        return validation(RefreshSessionValidation.Status.VALID, snapshot);
    }

    private static RefreshSessionValidation validation(
            RefreshSessionValidation.Status status,
            RefreshSessionSnapshot snapshot) {
        return new RefreshSessionValidation(status, snapshot);
    }
}
