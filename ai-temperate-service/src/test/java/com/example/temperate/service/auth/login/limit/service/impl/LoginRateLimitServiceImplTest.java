package com.example.temperate.service.auth.login.limit.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.auth.login.limit.dto.LoginAttempt;
import com.example.temperate.service.auth.login.limit.dto.ProtectedLoginAttempt;
import com.example.temperate.service.auth.login.limit.enums.LoginFailureBucket;
import com.example.temperate.service.auth.login.limit.enums.LoginLimitDecision;
import com.example.temperate.service.auth.login.limit.exception.LoginRateLimitInfrastructureException;
import com.example.temperate.service.auth.login.limit.store.LoginFailureStore;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 验证登录限流服务对风控标识保护、存储异常与允许/阻断决策的处理。
 */
class LoginRateLimitServiceImplTest {

    private LoginFailureStore store;
    private LoginRateLimitServiceImpl service;
    private LoginAttempt attempt;

    @BeforeEach
    void setUp() {
        store = mock(LoginFailureStore.class);
        AuthSessionSecretProtector protector = new AuthSessionSecretProtector(
                new HmacSha256Identifier(
                        "rate-service-test-secret-0123456789".getBytes(StandardCharsets.UTF_8)));
        service = new LoginRateLimitServiceImpl(store, protector);
        attempt = new LoginAttempt(
                "person@example.test",
                "123e4567-e89b-42d3-a456-426614174000",
                "203.0.113.10");
    }

    @Test
    void protectsSensitiveValuesAndPassesTheRequestedBucket() {
        when(store.check(any(), eq(LoginFailureBucket.PASSWORD)))
                .thenReturn(LoginLimitDecision.ALLOWED);
        when(store.recordFailure(any(), eq(LoginFailureBucket.PASSWORD)))
                .thenReturn(LoginLimitDecision.BLOCKED);

        assertThat(service.check(attempt)).isEqualTo(LoginLimitDecision.ALLOWED);
        assertThat(service.recordFailure(attempt)).isEqualTo(LoginLimitDecision.BLOCKED);
        service.clearSubjectFailures(attempt);

        ArgumentCaptor<ProtectedLoginAttempt> captor =
                ArgumentCaptor.forClass(ProtectedLoginAttempt.class);
        verify(store).check(captor.capture(), eq(LoginFailureBucket.PASSWORD));
        verify(store).recordFailure(captor.capture(), eq(LoginFailureBucket.PASSWORD));
        verify(store).clearFailures(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(protectedAttempt -> {
            assertThat(protectedAttempt.identifierHash().value()).matches("^[A-Za-z0-9_-]{43}$");
            assertThat(protectedAttempt.actorHash().value()).matches("^[A-Za-z0-9_-]{43}$");
            assertThat(protectedAttempt.networkHash().value()).matches("^[A-Za-z0-9_-]{43}$");
            assertThat(protectedAttempt.identifierHash())
                    .isNotEqualTo(protectedAttempt.actorHash());
            assertThat(protectedAttempt.networkHash())
                    .isEqualTo(protectedAttempt.actorHash())
                    .isNotEqualTo(protectedAttempt.identifierHash());
            assertThat(protectedAttempt.toString())
                    .doesNotContain("person@example.test", "203.0.113.10", "install_A");
        });
    }

    @Test
    void unexpectedStoreFailureIsFailClosed() {
        when(store.check(any(), eq(LoginFailureBucket.PASSWORD)))
                .thenThrow(new IllegalStateException("unexpected redis failure"));

        assertThatThrownBy(() -> service.check(attempt))
                .isInstanceOf(LoginRateLimitInfrastructureException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
