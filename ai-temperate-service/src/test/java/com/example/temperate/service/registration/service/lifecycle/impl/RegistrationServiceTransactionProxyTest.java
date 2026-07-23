package com.example.temperate.service.registration.service.lifecycle.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.profile.UserProfileMapper;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.registration.component.executor.impl.SpringRegistrationAfterCommitExecutor;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import com.example.temperate.service.registration.component.observer.RegistrationCleanupObserver;
import com.example.temperate.service.auth.password.policy.PasswordStrengthPolicy;
import com.example.temperate.service.registration.component.token.RegistrationTokenGenerator;
import com.example.temperate.service.registration.dto.command.RegistrationCompleteCommand;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.flow.domain.RegistrationCompletionClaim;
import com.example.temperate.service.registration.flow.domain.RegistrationFlowSnapshot;
import com.example.temperate.service.registration.flow.security.RegistrationAccess;
import com.example.temperate.service.registration.flow.security.RegistrationTokenProtector;
import com.example.temperate.service.registration.flow.store.RegistrationFlowStore;
import com.example.temperate.service.registration.service.lifecycle.RegistrationService;
import com.example.temperate.service.registration.service.turnstile.TurnstileVerificationService;
import com.example.temperate.service.registration.verification.delivery.coordinator.VerificationDeliveryCoordinator;
import com.example.temperate.service.registration.verification.delivery.operation.VerificationDeliveryOperationIdGenerator;
import com.example.temperate.service.registration.verification.service.registry.SixDigitVerificationCodeServiceRegistry;
import com.example.temperate.service.registration.verification.service.resolver.VerificationProviderResolver;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;

/**
 * 验证注册完成方法经 Spring 事务代理调用并具备事务边界的测试。
 */
class RegistrationServiceTransactionProxyTest {

    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    @Test
    void completeRunsThroughASpringCglibTransactionProxy() {
        try (Harness harness = harness(new NoOpTransactionManager(), 1)) {
            assertThat(AopUtils.isCglibProxy(harness.service())).isTrue();
            assertThat(harness.service()).isInstanceOf(RegistrationService.class);
            assertThat(AopUtils.getTargetClass(harness.service()))
                    .isEqualTo(RegistrationServiceImpl.class);
        }
    }

    @Test
    void targetFailureRollsBackAndReleasesCompletionClaim() {
        try (Harness harness = harness(new NoOpTransactionManager(), 0)) {
            assertThatThrownBy(() -> harness.service().complete(command()))
                    .isInstanceOf(RegistrationException.class);

            verify(harness.flowStore()).releaseCompletionClaim(any(), any());
            verify(harness.flowStore(), org.mockito.Mockito.never()).delete(any());
        }
    }

    @Test
    void commitFailureReleasesCompletionClaim() {
        try (Harness harness = harness(new FailingCommitTransactionManager(), 1)) {
            assertThatThrownBy(() -> harness.service().complete(command()))
                    .isInstanceOf(TransactionSystemException.class)
                    .hasMessageContaining("commit failed");

            verify(harness.flowStore()).releaseCompletionClaim(any(), any());
            verify(harness.flowStore(), org.mockito.Mockito.never()).delete(any());
        }
    }

    @Test
    void exhaustedRedisCleanupDoesNotTurnACommittedRegistrationIntoAnError() {
        try (Harness harness = harness(new NoOpTransactionManager(), 1)) {
            doThrow(new IllegalStateException("redis unavailable"))
                    .when(harness.flowStore())
                    .delete(any());

            assertThat(harness.service().complete(command()).registrationTokenToClear())
                    .isEqualTo("register-token");

            verify(harness.flowStore(), times(3)).delete(any());
            verify(harness.cleanupObserver()).cleanupExhausted(3);
        }
    }

    private static Harness harness(
            PlatformTransactionManager transactionManager, int profileInsertResult) {
        UserLoginIdentityMapper identityMapper = mock(UserLoginIdentityMapper.class);
        UserProfileMapper profileMapper = mock(UserProfileMapper.class);
        UserMembershipQuotaMapper membershipQuotaMapper =
                mock(UserMembershipQuotaMapper.class);
        RegistrationFlowStore flowStore = mock(RegistrationFlowStore.class);
        RegistrationCleanupObserver cleanupObserver = mock(RegistrationCleanupObserver.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

        when(identityMapper.findConflicts(any(), any())).thenReturn(List.of());
        when(identityMapper.insert(any())).thenReturn(1);
        when(profileMapper.insert(any())).thenReturn(profileInsertResult);
        when(membershipQuotaMapper.insert(any())).thenReturn(1);
        when(passwordEncoder.encode(any())).thenReturn("{bcrypt}test-hash");
        when(flowStore.claimCompletion(any(), any(), any())).thenAnswer(invocation ->
                new RegistrationCompletionClaim(
                        new RegistrationFlowSnapshot(
                                "alice@example.com",
                                "+13125550100",
                                true,
                                true,
                                true,
                                true,
                                NOW.minusSeconds(60),
                                NOW.plusSeconds(540)),
                        invocation.getArgument(1)));

        HmacSha256Identifier hmac = new HmacSha256Identifier(
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8));
        RegistrationTokenProtector protector =
                new RegistrationTokenProtector(hmac, new AuthSessionSecretProtector(hmac));
        RegistrationTokenGenerator tokens = new FixedTokenGenerator();
        RegistrationServiceImpl target = new RegistrationServiceImpl(
                identityMapper,
                profileMapper,
                membershipQuotaMapper,
                flowStore,
                new RegistrationInputNormalizer(),
                new PasswordStrengthPolicy(),
                protector,
                tokens,
                () -> "012345",
                new VerificationDeliveryOperationIdGenerator(),
                mock(VerificationDeliveryCoordinator.class),
                mock(SixDigitVerificationCodeServiceRegistry.class),
                mock(VerificationProviderResolver.class),
                mock(TurnstileVerificationService.class),
                passwordEncoder,
                () -> 42L,
                new PublicIdCodec(),
                new SpringRegistrationAfterCommitExecutor(cleanupObserver),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(600));

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(TransactionProxyConfiguration.class);
        context.registerBean(
                "transactionManager", PlatformTransactionManager.class, () -> transactionManager);
        context.registerBean(RegistrationServiceImpl.class, () -> target);
        context.refresh();
        return new Harness(
                context,
                context.getBean(RegistrationService.class),
                flowStore,
                cleanupObserver);
    }

    private static RegistrationCompleteCommand command() {
        return new RegistrationCompleteCommand(
                new RegistrationAccess(
                        "register-token",
                        "flow-csrf",
                        "challenge-handle",
                        "550e8400-e29b-41d4-a716-446655440000",
                        "203.0.113.7"),
                "test-password",
                "test-password");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionProxyConfiguration {}

    private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }

    private static final class FailingCommitTransactionManager
            extends NoOpTransactionManager {

        private FailingCommitTransactionManager() {
            setRollbackOnCommitFailure(true);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            throw new TransactionSystemException("commit failed");
        }
    }

    private record Harness(
            AnnotationConfigApplicationContext context,
            RegistrationService service,
            RegistrationFlowStore flowStore,
            RegistrationCleanupObserver cleanupObserver)
            implements AutoCloseable {

        @Override
        public void close() {
            context.close();
        }
    }

    private static final class FixedTokenGenerator implements RegistrationTokenGenerator {

        @Override
        public String newRegisterToken() {
            return "register-token";
        }

        @Override
        public String newFlowCsrf() {
            return "flow-csrf";
        }

        @Override
        public String newChallengeHandle() {
            return "challenge-handle";
        }

        @Override
        public String newCompletionClaim() {
            return "completion-claim";
        }
    }
}
