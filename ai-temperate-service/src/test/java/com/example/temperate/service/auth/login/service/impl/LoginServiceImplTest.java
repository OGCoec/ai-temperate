package com.example.temperate.service.auth.login.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.service.auth.login.audit.observer.LoginAuditObserver;
import com.example.temperate.service.auth.login.component.normalizer.LoginInputNormalizer;
import com.example.temperate.service.auth.login.dto.command.LoginCommand;
import com.example.temperate.service.auth.login.dto.internal.NormalizedLoginInput;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.enums.LoginIdentifierType;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.login.limit.enums.LoginLimitDecision;
import com.example.temperate.service.auth.login.limit.service.LoginRateLimitService;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.session.refresh.dto.command.NewRefreshSession;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionSnapshot;
import com.example.temperate.service.auth.session.refresh.store.RefreshSessionStore;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 验证密码登录编排中的风控、账号状态、密码升级和会话签发安全契约。
 */
class LoginServiceImplTest {

    private static final long USER_ID = 10001L;
    private static final String DEVICE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String REFRESH_TOKEN = "A2345678901234567890123456789012345678";
    private static final String CSRF_TOKEN = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";
    private static final Instant EXPIRES_AT = Instant.parse("2026-07-15T09:00:00Z");
    private static final HmacSha256Identifier HMAC = new HmacSha256Identifier(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private LoginInputNormalizer normalizer;
    private UserLoginIdentityMapper identityMapper;
    private PasswordEncoder passwordEncoder;
    private LoginRateLimitService rateLimitService;
    private LoginAuditObserver auditObserver;
    private AuthTokenService tokenService;
    private RefreshSessionStore sessionStore;
    private AuthSessionSecretProtector protector;
    private PublicIdCodec publicIds;
    private LoginServiceImpl service;

    @BeforeEach
    void setUp() {
        normalizer = mock(LoginInputNormalizer.class);
        identityMapper = mock(UserLoginIdentityMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        rateLimitService = mock(LoginRateLimitService.class);
        auditObserver = mock(LoginAuditObserver.class);
        tokenService = mock(AuthTokenService.class);
        sessionStore = mock(RefreshSessionStore.class);
        protector = mock(AuthSessionSecretProtector.class);
        publicIds = new PublicIdCodec();
        service = new LoginServiceImpl(
                normalizer,
                identityMapper,
                passwordEncoder,
                rateLimitService,
                auditObserver,
                tokenService,
                sessionStore,
                protector,
                publicIds);
    }

    @Test
    void successfulLoginCreatesSixFieldFixedRtAndIssuesSidFreeAccessToken() {
        LoginCommand command = new LoginCommand(
                "person@example.test", "Password1!", DEVICE_ID, "127.0.0.1");
        NormalizedLoginInput input = new NormalizedLoginInput(
                LoginIdentifierType.EMAIL,
                "person@example.test",
                "Password1!",
                DEVICE_ID,
                "127.0.0.1");
        AuthenticationContext context = new AuthenticationContext(
                USER_ID,
                "{bcrypt}hash",
                8L,
                AccountStatus.ACTIVE,
                "用户",
                "person@example.test",
                "+8613812345678");
        HmacIdentifier refreshHash = id("refresh");
        HmacIdentifier deviceHash = id("device");
        HmacIdentifier csrfHash = id("csrf");
        when(normalizer.normalize(command)).thenReturn(input);
        when(rateLimitService.check(any())).thenReturn(LoginLimitDecision.ALLOWED);
        when(identityMapper.findAuthenticationByNormalizedEmail("person@example.test"))
                .thenReturn(context);
        when(passwordEncoder.matches("Password1!", "{bcrypt}hash")).thenReturn(true);
        when(passwordEncoder.upgradeEncoding("{bcrypt}hash")).thenReturn(false);
        when(tokenService.newRefreshToken()).thenReturn(REFRESH_TOKEN);
        when(tokenService.newCsrfToken()).thenReturn(CSRF_TOKEN);
        when(tokenService.issueAccessToken(USER_ID)).thenReturn("access-token");
        when(protector.refreshToken(REFRESH_TOKEN)).thenReturn(refreshHash);
        when(protector.device(DEVICE_ID)).thenReturn(deviceHash);
        when(protector.csrf(CSRF_TOKEN)).thenReturn(csrfHash);
        when(sessionStore.create(any())).thenReturn(new RefreshSessionSnapshot(
                USER_ID,
                publicIds.encode(USER_ID),
                csrfHash.value(),
                "person@example.test",
                "+8613812345678",
                deviceHash.value(),
                EXPIRES_AT));

        LoginResult result = service.login(command);

        ArgumentCaptor<NewRefreshSession> sessionCaptor =
                ArgumentCaptor.forClass(NewRefreshSession.class);
        verify(sessionStore).create(sessionCaptor.capture());
        NewRefreshSession stored = sessionCaptor.getValue();
        assertThat(stored.userId()).isEqualTo(USER_ID);
        assertThat(stored.publicId()).isEqualTo(publicIds.encode(USER_ID));
        assertThat(stored.refreshTokenHash()).isEqualTo(refreshHash);
        assertThat(stored.deviceHash()).isEqualTo(deviceHash);
        assertThat(stored.csrfHash()).isEqualTo(csrfHash);
        assertThat(stored.email()).isEqualTo("person@example.test");
        assertThat(stored.phone()).isEqualTo("+8613812345678");
        assertThat(result.getAccessToken()).isEqualTo("access-token");
        assertThat(result.getRefreshToken()).isEqualTo(REFRESH_TOKEN);
        assertThat(result.getCsrfToken()).isEqualTo(CSRF_TOKEN);
        assertThat(result.getRefreshExpiresAt()).isEqualTo(EXPIRES_AT);
        verify(tokenService).issueAccessToken(USER_ID);
    }

    @Test
    void invalidPasswordDoesNotCreateSession() {
        LoginCommand command = new LoginCommand(
                "person@example.test", "wrong", DEVICE_ID, "127.0.0.1");
        when(normalizer.normalize(command)).thenReturn(new NormalizedLoginInput(
                LoginIdentifierType.EMAIL,
                "person@example.test",
                "wrong",
                DEVICE_ID,
                "127.0.0.1"));
        when(rateLimitService.check(any())).thenReturn(LoginLimitDecision.ALLOWED);
        when(rateLimitService.recordFailure(any())).thenReturn(LoginLimitDecision.ALLOWED);
        when(identityMapper.findAuthenticationByNormalizedEmail("person@example.test"))
                .thenReturn(new AuthenticationContext(
                        USER_ID, "{bcrypt}hash", 1L, AccountStatus.ACTIVE, "用户"));
        when(passwordEncoder.matches("wrong", "{bcrypt}hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(command))
                .isInstanceOfSatisfying(LoginException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(LoginErrorCode.AUTHENTICATION_FAILED));
        verify(sessionStore, never()).create(any());
        verify(tokenService, never()).issueAccessToken(anyLong());
    }

    private static HmacIdentifier id(String value) {
        return HMAC.identify(value);
    }
}
