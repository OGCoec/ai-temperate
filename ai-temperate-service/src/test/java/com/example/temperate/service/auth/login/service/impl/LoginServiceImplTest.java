package com.example.temperate.service.auth.login.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceDecision;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceFilter;
import com.example.temperate.service.auth.login.audit.observer.LoginAuditObserver;
import com.example.temperate.service.auth.login.component.normalizer.LoginInputNormalizer;
import com.example.temperate.service.auth.login.completion.LoginCompletionService;
import com.example.temperate.service.auth.login.dto.command.LoginCommand;
import com.example.temperate.service.auth.login.dto.internal.NormalizedLoginInput;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.enums.LoginIdentifierType;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.login.limit.enums.LoginLimitDecision;
import com.example.temperate.service.auth.login.limit.service.LoginRateLimitService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 验证密码登录编排中的风控、账号状态、密码升级和会话签发安全契约。
 */
class LoginServiceImplTest {

    private static final long USER_ID = 10001L;
    private static final String DEVICE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final Instant EXPIRES_AT = Instant.parse("2026-07-15T09:00:00Z");

    private LoginInputNormalizer normalizer;
    private UserLoginIdentityMapper identityMapper;
    private PasswordEncoder passwordEncoder;
    private LoginRateLimitService rateLimitService;
    private LoginAuditObserver auditObserver;
    private LoginCompletionService completionService;
    private IdentityPresenceFilter identityPresenceFilter;
    private LoginServiceImpl service;

    @BeforeEach
    void setUp() {
        normalizer = mock(LoginInputNormalizer.class);
        identityMapper = mock(UserLoginIdentityMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        rateLimitService = mock(LoginRateLimitService.class);
        auditObserver = mock(LoginAuditObserver.class);
        completionService = mock(LoginCompletionService.class);
        identityPresenceFilter = mock(IdentityPresenceFilter.class);
        when(identityPresenceFilter.checkEmail(any()))
                .thenReturn(IdentityPresenceDecision.UNAVAILABLE);
        when(identityPresenceFilter.checkPhone(any()))
                .thenReturn(IdentityPresenceDecision.UNAVAILABLE);
        service = new LoginServiceImpl(
                normalizer,
                identityMapper,
                passwordEncoder,
                rateLimitService,
                auditObserver,
                completionService,
                identityPresenceFilter);
    }

    @Test
    void successfulLoginDelegatesOnlyToUnifiedCompletionBoundary() {
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
        when(normalizer.normalize(command)).thenReturn(input);
        when(rateLimitService.check(any())).thenReturn(LoginLimitDecision.ALLOWED);
        when(identityMapper.findAuthenticationByNormalizedEmail("person@example.test"))
                .thenReturn(context);
        when(passwordEncoder.matches("Password1!", "{bcrypt}hash")).thenReturn(true);
        when(passwordEncoder.upgradeEncoding("{bcrypt}hash")).thenReturn(false);
        LoginResult completed = new LoginResult(
                "AAAAAAAAAAE", "用户", "access-token", "refresh-token",
                "csrf-token", EXPIRES_AT);
        when(completionService.complete(context, DEVICE_ID)).thenReturn(completed);

        LoginResult result = service.login(command);

        verify(completionService).complete(context, DEVICE_ID);
        assertThat(result.getAccessToken()).isEqualTo("access-token");
        assertThat(result.getRefreshToken()).isEqualTo("refresh-token");
        assertThat(result.getCsrfToken()).isEqualTo("csrf-token");
        assertThat(result.getRefreshExpiresAt()).isEqualTo(EXPIRES_AT);
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
        verify(completionService, never()).complete(any(), any());
    }

    @Test
    void definiteBloomMissSkipsDatabaseButStillRunsDummyPasswordHash() {
        LoginCommand command = new LoginCommand(
                "missing@example.test", "wrong", DEVICE_ID, "127.0.0.1");
        when(normalizer.normalize(command)).thenReturn(new NormalizedLoginInput(
                LoginIdentifierType.EMAIL,
                "missing@example.test",
                "wrong",
                DEVICE_ID,
                "127.0.0.1"));
        when(rateLimitService.check(any())).thenReturn(LoginLimitDecision.ALLOWED);
        when(rateLimitService.recordFailure(any())).thenReturn(LoginLimitDecision.ALLOWED);
        when(identityPresenceFilter.checkEmail("missing@example.test"))
                .thenReturn(IdentityPresenceDecision.DEFINITELY_ABSENT);
        when(passwordEncoder.matches("wrong", LoginServiceImpl.DUMMY_PASSWORD_HASH))
                .thenReturn(false);

        assertThatThrownBy(() -> service.login(command))
                .isInstanceOf(LoginException.class);

        verify(identityMapper, never())
                .findAuthenticationByNormalizedEmail("missing@example.test");
        verify(passwordEncoder)
                .matches("wrong", LoginServiceImpl.DUMMY_PASSWORD_HASH);
    }

}
