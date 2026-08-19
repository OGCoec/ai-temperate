package com.example.temperate.service.registration.service.lifecycle.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.profile.UserProfileMapper;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.model.user.entity.UserProfile;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceDecision;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceFilter;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceMutationResult;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.humanverification.HumanVerificationCommand;
import com.example.temperate.service.humanverification.HumanVerificationService;
import com.example.temperate.service.humanverification.HumanVerificationServiceRegistry;
import com.example.temperate.service.humanverification.HumanVerificationType;
import com.example.temperate.service.humanverification.exception.HumanVerificationUnavailableException;
import com.example.temperate.service.registration.component.executor.RegistrationAfterCommitExecutor;
import com.example.temperate.service.registration.component.id.RegistrationIdGenerator;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import com.example.temperate.service.auth.password.policy.PasswordStrengthPolicy;
import com.example.temperate.service.registration.component.token.RegistrationTokenGenerator;
import com.example.temperate.service.registration.dto.command.RegistrationCompleteCommand;
import com.example.temperate.service.registration.dto.command.RegistrationSendCodeCommand;
import com.example.temperate.service.registration.dto.command.RegistrationStartCommand;
import com.example.temperate.service.registration.dto.command.RegistrationTurnstileCommand;
import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodeCommand;
import com.example.temperate.service.registration.dto.query.RegistrationStatusQuery;
import com.example.temperate.service.registration.dto.result.RegistrationStartResult;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.enums.RegistrationDiagnosticCode;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.RegistrationStatus;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.enums.VerificationProvider;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.flow.domain.RegistrationActor;
import com.example.temperate.service.registration.flow.domain.RegistrationCompletionClaim;
import com.example.temperate.service.registration.flow.domain.RegistrationFlow;
import com.example.temperate.service.registration.flow.domain.RegistrationFlowSnapshot;
import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import com.example.temperate.service.registration.flow.security.RegistrationAccess;
import com.example.temperate.service.registration.flow.security.RegistrationTokenProtector;
import com.example.temperate.service.registration.flow.store.RegistrationFlowStore;
import com.example.temperate.service.registration.service.lifecycle.RegistrationService;
import com.example.temperate.service.registration.verification.delivery.coordinator.VerificationDeliveryCoordinator;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.operation.VerificationDeliveryOperationIdGenerator;
import com.example.temperate.service.registration.verification.delivery.rabbit.VerificationDeliveryMessage;
import com.example.temperate.service.registration.verification.delivery.rabbit.VerificationDeliveryPublisher;
import com.example.temperate.service.registration.verification.generator.VerificationCodeGenerator;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeService;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeVerifier;
import com.example.temperate.service.registration.verification.service.impl.RedisSixDigitVerificationCodeVerifierImpl;
import com.example.temperate.service.registration.verification.service.registry.SixDigitVerificationCodeServiceRegistry;
import com.example.temperate.service.registration.verification.service.resolver.impl.LibphonenumberVerificationProviderResolver;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 验证注册业务编排、验证码投递、冲突隐藏和完成流程的单元测试。
 */
class RegistrationServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    private UserLoginIdentityMapper identityMapper;
    private UserProfileMapper profileMapper;
    private UserMembershipQuotaMapper membershipQuotaMapper;
    private InMemoryFlowStore flowStore;
    private PasswordEncoder passwordEncoder;
    private IdentityPresenceFilter identityPresenceFilter;
    private CapturingAfterCommitExecutor afterCommitExecutor;
    private CapturingPublisher deliveryPublisher;
    private TurnstileProbe turnstile;
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        identityMapper = mock(UserLoginIdentityMapper.class);
        profileMapper = mock(UserProfileMapper.class);
        membershipQuotaMapper = mock(UserMembershipQuotaMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        identityPresenceFilter = mock(IdentityPresenceFilter.class);
        flowStore = new InMemoryFlowStore();
        afterCommitExecutor = new CapturingAfterCommitExecutor();
        deliveryPublisher = new CapturingPublisher();
        turnstile = new TurnstileProbe(flowStore);

        when(identityMapper.findConflicts(any(), any())).thenReturn(List.of());
        when(identityMapper.insert(any())).thenReturn(1);
        when(profileMapper.insert(any())).thenReturn(1);
        when(membershipQuotaMapper.insert(any())).thenReturn(1);
        when(passwordEncoder.encode(any())).thenReturn("{bcrypt}test-hash");
        when(identityPresenceFilter.checkEmail(any()))
                .thenReturn(IdentityPresenceDecision.UNAVAILABLE);
        when(identityPresenceFilter.checkPhone(any()))
                .thenReturn(IdentityPresenceDecision.UNAVAILABLE);
        when(identityPresenceFilter.recordRegistration(anyLong(), any(), any()))
                .thenReturn(IdentityPresenceMutationResult.APPLIED);

        HmacSha256Identifier hmac = new HmacSha256Identifier(
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8));
        RegistrationTokenProtector protector =
                new RegistrationTokenProtector(hmac, new AuthSessionSecretProtector(hmac));
        RegistrationTokenGenerator tokens = new FixedTokenGenerator();
        VerificationCodeGenerator codes = () -> "012345";
        RegistrationIdGenerator ids = () -> 42L;
        VerificationDeliveryCoordinator deliveryCoordinator =
                new VerificationDeliveryCoordinator(deliveryPublisher);
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        SixDigitVerificationCodeVerifier codeVerifier =
                new RedisSixDigitVerificationCodeVerifierImpl(
                        flowStore, protector, fixedClock);
        SixDigitVerificationCodeServiceRegistry serviceRegistry =
                new SixDigitVerificationCodeServiceRegistry(Map.of(
                        "gmail", verificationService(VerificationProvider.GMAIL, codeVerifier),
                        "aliyun", verificationService(VerificationProvider.ALIYUN_SMS, codeVerifier),
                        "twilio", verificationService(VerificationProvider.TWILIO_SMS, codeVerifier)));

        service = new RegistrationServiceImpl(
                identityMapper,
                profileMapper,
                membershipQuotaMapper,
                tier -> new com.example.temperate.service.user.membership.MembershipQuotaPlan(
                        5_000L, Duration.ofDays(7)),
                flowStore,
                new RegistrationInputNormalizer(),
                new PasswordStrengthPolicy(),
                protector,
                tokens,
                codes,
                new VerificationDeliveryOperationIdGenerator(),
                deliveryCoordinator,
                serviceRegistry,
                new LibphonenumberVerificationProviderResolver(),
                new HumanVerificationServiceRegistry(Map.of(
                        "turnstile", turnstile,
                        "hcaptcha", new HcaptchaProbe())),
                passwordEncoder,
                ids,
                new PublicIdCodec(),
                afterCommitExecutor,
                identityPresenceFilter,
                fixedClock,
                Duration.ofSeconds(600));
    }

    @Test
    void implementationUsesInterfaceServiceAndTransactionalCompleteContract() throws Exception {
        assertThat(RegistrationServiceImpl.class).isNotFinal();
        assertThat(RegistrationServiceImpl.class).hasAnnotation(Service.class);
        assertThat(RegistrationService.class).isAssignableFrom(RegistrationServiceImpl.class);
        assertThat(RegistrationServiceImpl.class
                        .getMethod("complete", RegistrationCompleteCommand.class)
                        .isAnnotationPresent(Transactional.class))
                .isTrue();
    }

    @Test
    void startNormalizesContactsUsesOneConflictQueryAndCreatesFixedTenMinuteFlow() {
        RegistrationStartResult result = service.start(startCommand());

        verify(identityMapper).findConflicts("alice@example.com", "+13125550100");
        assertThat(result.registerToken()).isEqualTo("register-token");
        assertThat(result.flowCsrf()).isEqualTo("flow-csrf");
        assertThat(result.challengeHandle()).isEqualTo("challenge-handle");
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(flowStore.flow.createdAt()).isEqualTo(NOW);
        assertThat(flowStore.flow.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(flowStore.flow.email()).isEqualTo("alice@example.com");
        assertThat(flowStore.flow.phone()).isEqualTo("+13125550100");
    }

    @Test
    void startSkipsInitialConflictQueryOnlyWhenBothFiltersDefinitelyMiss() {
        when(identityPresenceFilter.checkEmail("alice@example.com"))
                .thenReturn(IdentityPresenceDecision.DEFINITELY_ABSENT);
        when(identityPresenceFilter.checkPhone("+13125550100"))
                .thenReturn(IdentityPresenceDecision.DEFINITELY_ABSENT);

        service.start(startCommand());

        verify(identityMapper, never()).findConflicts(any(), any());
    }

    @Test
    void fifthConflictBlocksActorForSubsequentStartsAndAlwaysUsesUnifiedError() {
        when(identityMapper.findConflicts(any(), any())).thenReturn(List.of(new UserLoginIdentity()));

        for (int attempt = 1; attempt <= 6; attempt++) {
            assertThatThrownBy(() -> service.start(startCommand()))
                    .isInstanceOfSatisfying(RegistrationException.class, exception ->
                            assertThat(exception.code())
                                    .isEqualTo(RegistrationErrorCode.AUTH_REGISTER_UNAVAILABLE));
        }

        verify(identityMapper, times(5)).findConflicts("alice@example.com", "+13125550100");
        assertThat(flowStore.conflictAttempts).isEqualTo(5);
        assertThat(flowStore.blocked).isTrue();
    }

    @Test
    void turnstileAndBothOneTimeCodesAreRequiredBeforeCompletion() {
        RegistrationAccess access = startAndAccess();

        assertThatThrownBy(() -> service.complete(completeCommand(access)))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(RegistrationErrorCode.HUMAN_VERIFICATION_REQUIRED));
        assertThat(flowStore.releaseCalls).isZero();

        RegistrationStatusResult humanVerified = verifyTurnstile(
                new RegistrationTurnstileCommand(
                        access, "turnstile-response"));
        assertThat(turnstile.responseToken).isEqualTo("turnstile-response");
        assertThat(turnstile.remoteIp).isEqualTo("203.0.113.7");
        assertThat(turnstile.challengeHandle).isEqualTo("challenge-handle");
        assertThat(turnstile.expectedAction).isEqualTo("register");
        assertThat(turnstile.flowLoadedBeforeVerify).isTrue();
        assertThat(flowStore.markHumanVerifiedCalls).hasValue(1);
        assertThat(humanVerified.email()).isEqualTo("alice@example.com");
        assertThat(humanVerified.phoneE164()).isEqualTo("+13125550100");

        sendAndVerify(access, VerificationChannel.EMAIL);
        assertThatThrownBy(() -> service.complete(completeCommand(access)))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(RegistrationErrorCode.PHONE_VERIFICATION_REQUIRED));

        sendAndVerify(access, VerificationChannel.SMS);
        RegistrationStatusResult ready = service.status(new RegistrationStatusQuery(access));
        assertThat(ready.status()).isEqualTo(RegistrationStatus.READY_TO_COMPLETE);
        assertThat(ready.email()).isEqualTo("alice@example.com");
        assertThat(ready.phoneE164()).isEqualTo("+13125550100");
    }

    @Test
    void sendBeforeHumanVerificationNeverCreatesOrPublishesCode() {
        RegistrationAccess access = startAndAccess();

        assertThatThrownBy(() -> service.sendCode(
                        new RegistrationSendCodeCommand(
                                access, VerificationChannel.EMAIL)))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                RegistrationErrorCode.HUMAN_VERIFICATION_REQUIRED));

        assertThat(flowStore.codes).isEmpty();
        assertThat(deliveryPublisher.request(VerificationChannel.EMAIL)).isNull();
    }

    @Test
    void repeatedTurnstileFinalizationKeepsPublicErrorGenericAndClassifiesConsumedChallenge() {
        RegistrationAccess access = startAndAccess();
        RegistrationTurnstileCommand command =
                new RegistrationTurnstileCommand(access, "turnstile-response");
        verifyTurnstile(command);

        assertTurnstileRejected(
                service.verifyTurnstile(command),
                RegistrationErrorCode.TURNSTILE_REJECTED,
                RegistrationDiagnosticCode.CHALLENGE_ALREADY_CONSUMED);
    }

    @Test
    void classifiesRedisFailureAfterProviderSuccessAsFinalizeFailure() {
        RegistrationAccess access = startAndAccess();
        flowStore.failHumanFinalize = true;

        assertTurnstileRejected(
                service.verifyTurnstile(new RegistrationTurnstileCommand(
                        access, "turnstile-response")),
                RegistrationErrorCode.AUTH_REGISTER_UNAVAILABLE,
                RegistrationDiagnosticCode.REDIS_FINALIZE_FAILED);
    }

    @Test
    void providerFailureNeverMarksTheRegistrationFlow() {
        RegistrationAccess access = startAndAccess();
        turnstile.failure = new RegistrationException(
                RegistrationErrorCode.TURNSTILE_REJECTED,
                "Turnstile verification was rejected.",
                RegistrationDiagnosticCode.CLOUDFLARE_TOKEN_REJECTED);

        assertTurnstileRejected(
                service.verifyTurnstile(new RegistrationTurnstileCommand(
                        access, "turnstile-response")),
                RegistrationErrorCode.TURNSTILE_REJECTED,
                RegistrationDiagnosticCode.CLOUDFLARE_TOKEN_REJECTED);

        assertThat(flowStore.markHumanVerifiedCalls).hasValue(0);
    }

    @Test
    void providerUnavailablePropagatesWithoutMarkingTheRegistrationFlow() {
        RegistrationAccess access = startAndAccess();
        HumanVerificationUnavailableException unavailable =
                new HumanVerificationUnavailableException(
                        HumanVerificationType.TURNSTILE,
                        new IllegalStateException("simulated transport failure"));
        turnstile.failure = unavailable;

        StepVerifier.create(service.verifyTurnstile(
                        new RegistrationTurnstileCommand(
                                access, "turnstile-response")))
                .expectErrorSatisfies(failure -> assertThat(failure)
                        .isSameAs(unavailable))
                .verify();

        assertThat(flowStore.markHumanVerifiedCalls).hasValue(0);
    }

    @Test
    void sendCodePublishFailureCompensatesTheCurrentCodeState() {
        RegistrationAccess access = startAndAccess();
        verifyTurnstile(new RegistrationTurnstileCommand(
                access, "turnstile-response"));
        deliveryPublisher.failPublish = true;

        assertThatThrownBy(() -> service.sendCode(
                        new RegistrationSendCodeCommand(access, VerificationChannel.EMAIL)))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(RegistrationErrorCode.DELIVERY_UNAVAILABLE));

        assertThat(flowStore.codes).doesNotContainKey(VerificationChannel.EMAIL);
        assertThat(flowStore.deliveryOperations).doesNotContainKey(VerificationChannel.EMAIL);
    }

    @Test
    void internationalWhatsappUsesTheSharedPhoneCodeSlot() {
        RegistrationAccess access = startAndAccess();
        verifyTurnstile(new RegistrationTurnstileCommand(
                access, "turnstile-response"));

        service.sendCode(new RegistrationSendCodeCommand(
                access,
                VerificationChannel.SMS,
                VerificationDeliveryMethod.WHATSAPP));

        assertThat(deliveryPublisher.request(VerificationChannel.SMS).destination())
                .isEqualTo("+13125550100");
        assertThat(deliveryPublisher.deliveryMethod(VerificationChannel.SMS))
                .isEqualTo(VerificationDeliveryMethod.WHATSAPP);
        assertThat(flowStore.codes).containsKey(VerificationChannel.SMS);
        assertThat(flowStore.deliveryOperations).containsKey(VerificationChannel.SMS);
    }

    @Test
    void weakPasswordIsRejectedBeforeHashingOrPersistence() {
        RegistrationAccess access = startAndAccess();
        clearInvocations(identityMapper, passwordEncoder);

        assertThatThrownBy(() -> service.complete(
                new RegistrationCompleteCommand(access, "1234567", "1234567")))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                RegistrationErrorCode.PASSWORD_STRENGTH_INSUFFICIENT));

        verifyNoInteractions(identityMapper, passwordEncoder);
    }

    @Test
    void completePersistsIdentityProfileAndMembershipThenDeletesFlowOnlyAfterCommit() {
        RegistrationAccess access = fullyVerifiedAccess();
        reset(identityMapper, profileMapper, membershipQuotaMapper);
        when(identityMapper.findConflicts(any(), any())).thenReturn(List.of());
        when(identityMapper.insert(any())).thenReturn(1);
        when(profileMapper.insert(any())).thenReturn(1);
        when(membershipQuotaMapper.insert(any())).thenReturn(1);

        var result = service.complete(completeCommand(access));

        String publicId = new PublicIdCodec().encode(42L);
        assertThat(result.publicUserId()).isEqualTo(publicId);
        assertThat(result.registrationTokenToClear()).isEqualTo("register-token");
        verify(identityMapper).findConflicts("alice@example.com", "+13125550100");
        var persistenceOrder = inOrder(identityMapper, profileMapper, membershipQuotaMapper);
        persistenceOrder.verify(identityMapper).insert(any(UserLoginIdentity.class));
        persistenceOrder.verify(profileMapper).insert(any(UserProfile.class));
        persistenceOrder.verify(membershipQuotaMapper)
                .insert(any(UserMembershipQuota.class));

        UserLoginIdentity identity = flowStore.persistedIdentityProbe(identityMapper);
        assertThat(identity.getId()).isEqualTo(42L);
        assertThat(identity.getPasswordHash()).isEqualTo("{bcrypt}test-hash");
        assertThat(identity.getPasswordVersion()).isNull();
        assertThat(identity.getCreatedAt()).isNull();
        assertThat(identity.getUpdatedAt()).isNull();

        UserProfile profile = flowStore.persistedProfileProbe(profileMapper);
        assertThat(profile.getLoginIdentityId()).isEqualTo(42L);
        assertThat(profile.getDisplayName()).isEqualTo("用户" + publicId.substring(publicId.length() - 7));

        UserMembershipQuota membershipQuota =
                flowStore.persistedMembershipQuotaProbe(membershipQuotaMapper);
        assertThat(membershipQuota.getLoginIdentityId()).isEqualTo(42L);
        assertThat(membershipQuota.getMembershipTier())
                .isEqualTo(com.example.temperate.model.auth.enums.MembershipTier.FREE.ordinal());
        assertThat(membershipQuota.getQuotaBalanceMinor()).isEqualTo(5_000L);
        assertThat(membershipQuota.getQuotaPeriodStartedAt()).isNull();
        assertThat(membershipQuota.getQuotaPeriodEndsAt())
                .isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        assertThat(flowStore.deleted).isFalse();

        when(identityPresenceFilter.recordRegistration(
                        42L, "alice@example.com", "+13125550100"))
                .thenAnswer(invocation -> {
                    assertThat(flowStore.deleted).isFalse();
                    return IdentityPresenceMutationResult.APPLIED;
                });
        afterCommitExecutor.commit();
        assertThat(flowStore.deleted).isTrue();
        verify(identityPresenceFilter)
                .recordRegistration(42L, "alice@example.com", "+13125550100");
    }

    @Test
    void uniqueConstraintRaceBecomesUnifiedUnavailableAndRollbackReleasesClaim() {
        RegistrationAccess access = fullyVerifiedAccess();
        when(identityMapper.findConflicts(any(), any())).thenReturn(List.of());
        when(identityMapper.insert(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.complete(completeCommand(access)))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(RegistrationErrorCode.AUTH_REGISTER_UNAVAILABLE));

        assertThat(flowStore.completing).isTrue();
        assertThat(afterCommitExecutor.committedAction).isNotNull();
        assertThat(flowStore.deleted).isFalse();

        afterCommitExecutor.rollback();
        assertThat(flowStore.completing).isFalse();
        assertThat(flowStore.deleted).isFalse();
    }

    @Test
    void mapperImpactFailureReliesOnRollbackToReleaseClaimAndNeverDeletesFlow() {
        RegistrationAccess access = fullyVerifiedAccess();
        when(identityMapper.findConflicts(any(), any())).thenReturn(List.of());
        when(identityMapper.insert(any())).thenReturn(1);
        when(profileMapper.insert(any())).thenReturn(0);

        assertThatThrownBy(() -> service.complete(completeCommand(access)))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(RegistrationErrorCode.REGISTRATION_PERSISTENCE_FAILED));

        assertThat(flowStore.completing).isTrue();

        afterCommitExecutor.rollback();
        assertThat(flowStore.completing).isFalse();
        assertThat(flowStore.deleted).isFalse();
    }

    @Test
    void membershipQuotaImpactFailureReliesOnRollbackAndNeverDeletesFlow() {
        RegistrationAccess access = fullyVerifiedAccess();
        when(identityMapper.findConflicts(any(), any())).thenReturn(List.of());
        when(identityMapper.insert(any())).thenReturn(1);
        when(profileMapper.insert(any())).thenReturn(1);
        when(membershipQuotaMapper.insert(any())).thenReturn(0);

        assertThatThrownBy(() -> service.complete(completeCommand(access)))
                .isInstanceOfSatisfying(RegistrationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(RegistrationErrorCode.REGISTRATION_PERSISTENCE_FAILED));

        verify(membershipQuotaMapper).insert(any(UserMembershipQuota.class));
        assertThat(flowStore.completing).isTrue();

        afterCommitExecutor.rollback();
        assertThat(flowStore.completing).isFalse();
        assertThat(flowStore.deleted).isFalse();
    }

    private RegistrationAccess fullyVerifiedAccess() {
        RegistrationAccess access = startAndAccess();
        verifyTurnstile(new RegistrationTurnstileCommand(
                access, "turnstile-response"));
        sendAndVerify(access, VerificationChannel.EMAIL);
        sendAndVerify(access, VerificationChannel.SMS);
        return access;
    }

    private RegistrationStatusResult verifyTurnstile(
            RegistrationTurnstileCommand command) {
        AtomicReference<RegistrationStatusResult> result =
                new AtomicReference<>();
        StepVerifier.create(service.verifyTurnstile(command))
                .assertNext(result::set)
                .verifyComplete();
        return result.get();
    }

    private static void assertTurnstileRejected(
            Mono<RegistrationStatusResult> operation,
            RegistrationErrorCode expectedErrorCode,
            RegistrationDiagnosticCode expectedDiagnosticCode) {
        StepVerifier.create(operation)
                .expectErrorSatisfies(failure -> {
                    assertThat(failure)
                            .isInstanceOf(RegistrationException.class);
                    RegistrationException exception =
                            (RegistrationException) failure;
                    assertThat(exception.code()).isEqualTo(expectedErrorCode);
                    assertThat(exception.diagnosticCode())
                            .contains(expectedDiagnosticCode);
                })
                .verify();
    }

    private RegistrationAccess startAndAccess() {
        RegistrationStartResult result = service.start(startCommand());
        return new RegistrationAccess(
                result.registerToken(),
                result.flowCsrf(),
                result.challengeHandle(),
                "550e8400-e29b-41d4-a716-446655440000",
                "203.0.113.7");
    }

    private void sendAndVerify(
            RegistrationAccess access,
            VerificationChannel channel) {
        service.sendCode(new RegistrationSendCodeCommand(access, channel));
        VerificationDeliveryRequest request = deliveryPublisher.request(channel);
        assertThat(request.code()).isEqualTo("012345");
        String expectedDestination = channel == VerificationChannel.EMAIL
                ? "alice@example.com"
                : "+13125550100";
        assertThat(request.destination()).isEqualTo(expectedDestination);
        service.verifyCode(new RegistrationVerifyCodeCommand(access, channel, "012345"));
    }

    private static RegistrationStartCommand startCommand() {
        return new RegistrationStartCommand(
                " Alice@Example.COM ",
                "US",
                "3125550100",
                "550e8400-e29b-41d4-a716-446655440000",
                "203.0.113.7");
    }

    private static RegistrationCompleteCommand completeCommand(RegistrationAccess access) {
        return new RegistrationCompleteCommand(access, "test-password", "test-password");
    }

    private static SixDigitVerificationCodeService verificationService(
            VerificationProvider provider,
            SixDigitVerificationCodeVerifier verifier) {
        return new SixDigitVerificationCodeService() {
            @Override
            public VerificationProvider type() {
                return provider;
            }

            @Override
            public Mono<VerificationDeliveryResult> sendCode(
                    VerificationDeliveryRequest request) {
                return Mono.empty();
            }

            @Override
            public RegistrationStatusResult verifyCode(
                    RegistrationVerifyCodeCommand command) {
                return verifier.verify(command);
            }
        };
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

    private static final class TurnstileProbe implements HumanVerificationService {

        private final InMemoryFlowStore flowStore;
        private String responseToken;
        private String remoteIp;
        private String challengeHandle;
        private String expectedAction;
        private boolean flowLoadedBeforeVerify;
        private RuntimeException failure;

        private TurnstileProbe(InMemoryFlowStore flowStore) {
            this.flowStore = flowStore;
        }

        @Override
        public HumanVerificationType type() {
            return HumanVerificationType.TURNSTILE;
        }

        @Override
        public Mono<Void> verify(HumanVerificationCommand command) {
            this.responseToken = command.responseToken();
            this.remoteIp = command.canonicalClientIp();
            this.challengeHandle = command.challengeId();
            this.expectedAction = command.expectedAction();
            this.flowLoadedBeforeVerify = flowStore.flowReadCalls.get() > 0;
            if (failure != null) {
                return Mono.error(failure);
            }
            return Mono.empty();
        }
    }

    private static final class HcaptchaProbe implements HumanVerificationService {

        @Override
        public HumanVerificationType type() {
            return HumanVerificationType.HCAPTCHA;
        }

        @Override
        public Mono<Void> verify(HumanVerificationCommand command) {
            return Mono.empty();
        }
    }

    private static final class CapturingPublisher implements VerificationDeliveryPublisher {

        private final Map<VerificationChannel, VerificationDeliveryRequest> requests =
                new EnumMap<>(VerificationChannel.class);
        private final Map<VerificationChannel, VerificationDeliveryMethod> deliveryMethods =
                new EnumMap<>(VerificationChannel.class);
        private boolean failPublish;

        @Override
        public void publishRegistration(
                ProtectedRegistrationAccess access,
                VerificationChannel channel,
                HmacIdentifier operationId,
                VerificationDeliveryRequest request,
                Instant codeExpiresAt) {
            if (failPublish) {
                throw new IllegalStateException("broker confirm failed");
            }
            requests.put(channel, request);
        }

        @Override
        public void publishRegistration(
                ProtectedRegistrationAccess access,
                VerificationChannel channel,
                VerificationDeliveryMethod deliveryMethod,
                HmacIdentifier operationId,
                VerificationDeliveryRequest request,
                Instant codeExpiresAt) {
            if (failPublish) {
                throw new IllegalStateException("broker confirm failed");
            }
            requests.put(channel, request);
            deliveryMethods.put(channel, deliveryMethod);
        }

        @Override
        public void publishLogin(
                com.example.temperate.service.auth.login.code.flow.ProtectedLoginCodeAccess access,
                VerificationChannel channel,
                HmacIdentifier operationId,
                VerificationDeliveryRequest request,
                Instant codeExpiresAt) {
            throw new UnsupportedOperationException("registration test only");
        }

        @Override
        public void publishPasswordReset(
                com.example.temperate.service.auth.passwordreset.flow.ProtectedPasswordResetAccess access,
                VerificationChannel channel,
                HmacIdentifier operationId,
                VerificationDeliveryRequest request,
                Instant codeExpiresAt) {
            throw new UnsupportedOperationException("registration test only");
        }

        @Override
        public void publishRetry(VerificationDeliveryMessage current, Duration delay) {
            throw new UnsupportedOperationException("registration test only");
        }

        @Override
        public void publishTerminalFailure(
                VerificationDeliveryMessage original,
                String provider,
                String safeReason,
                boolean retryable) {
            throw new UnsupportedOperationException("registration test only");
        }

        private VerificationDeliveryRequest request(VerificationChannel channel) {
            return requests.get(channel);
        }

        private VerificationDeliveryMethod deliveryMethod(VerificationChannel channel) {
            return deliveryMethods.get(channel);
        }
    }

    private static final class CapturingAfterCommitExecutor
            implements RegistrationAfterCommitExecutor {

        private Runnable committedAction;
        private Runnable notCommittedAction;

        @Override
        public void execute(Runnable committedAction, Runnable notCommittedAction) {
            this.committedAction = committedAction;
            this.notCommittedAction = notCommittedAction;
        }

        private void commit() {
            assertThat(committedAction).isNotNull();
            committedAction.run();
        }

        private void rollback() {
            assertThat(notCommittedAction).isNotNull();
            notCommittedAction.run();
        }
    }

    private static final class InMemoryFlowStore implements RegistrationFlowStore {

        private RegistrationFlow flow;
        private int conflictAttempts;
        private boolean blocked;
        private boolean humanVerified;
        private boolean emailVerified;
        private boolean phoneVerified;
        private boolean completing;
        private boolean deleted;
        private boolean failHumanFinalize;
        private int releaseCalls;
        private final AtomicInteger flowReadCalls = new AtomicInteger();
        private final AtomicInteger markHumanVerifiedCalls =
                new AtomicInteger();
        private final Map<VerificationChannel, HmacIdentifier> codes =
                new EnumMap<>(VerificationChannel.class);
        private final Map<VerificationChannel, HmacIdentifier> deliveryOperations =
                new EnumMap<>(VerificationChannel.class);
        private HmacIdentifier claimId;

        @Override
        public boolean isBlocked(RegistrationActor actor) {
            return blocked;
        }

        @Override
        public boolean recordConflict(RegistrationActor actor, Instant occurredAt) {
            conflictAttempts++;
            blocked = conflictAttempts >= 5;
            return blocked;
        }

        @Override
        public void create(RegistrationFlow flow) {
            this.flow = flow;
            humanVerified = false;
            emailVerified = false;
            phoneVerified = false;
            completing = false;
            deleted = false;
            codes.clear();
            deliveryOperations.clear();
        }

        @Override
        public RegistrationFlowSnapshot getRequired(
                ProtectedRegistrationAccess access, Instant now) {
            requireFlow(access, now);
            flowReadCalls.incrementAndGet();
            return snapshot();
        }

        @Override
        public RegistrationFlowSnapshot markHumanVerified(
                ProtectedRegistrationAccess access, Instant now) {
            markHumanVerifiedCalls.incrementAndGet();
            requireFlow(access, now);
            if (failHumanFinalize) {
                throw error(RegistrationErrorCode.AUTH_REGISTER_UNAVAILABLE);
            }
            if (humanVerified) {
                throw error(RegistrationErrorCode.TURNSTILE_REJECTED);
            }
            humanVerified = true;
            return snapshot();
        }

        @Override
        public void issueCode(
                ProtectedRegistrationAccess access,
                VerificationChannel channel,
                HmacIdentifier codeDigest,
                HmacIdentifier sendOperationId,
                Instant now) {
            requireFlow(access, now);
            if (!humanVerified) {
                throw error(RegistrationErrorCode.HUMAN_VERIFICATION_REQUIRED);
            }
            codes.put(channel, codeDigest);
            deliveryOperations.put(channel, sendOperationId);
        }

        @Override
        public boolean markCodeDeliverySucceeded(
                ProtectedRegistrationAccess access,
                VerificationChannel channel,
                HmacIdentifier sendOperationId) {
            requireFlow(access, NOW);
            return sendOperationId.equals(deliveryOperations.get(channel));
        }

        @Override
        public boolean markCodeDeliveryAccepted(
                ProtectedRegistrationAccess access,
                VerificationChannel channel,
                HmacIdentifier sendOperationId,
                String providerMessageId,
                String providerStatus) {
            return markCodeDeliverySucceeded(access, channel, sendOperationId);
        }

        @Override
        public boolean markCodeDeliveryOutcomeUnknown(
                ProtectedRegistrationAccess access,
                VerificationChannel channel,
                HmacIdentifier sendOperationId,
                String safeReason) {
            requireFlow(access, NOW);
            return sendOperationId.equals(deliveryOperations.get(channel));
        }

        @Override
        public boolean claimCodeDeliveryAttempt(
                ProtectedRegistrationAccess access,
                VerificationChannel channel,
                HmacIdentifier sendOperationId,
                String messageId,
                int attemptNo) {
            requireFlow(access, NOW);
            return sendOperationId.equals(deliveryOperations.get(channel));
        }

        @Override
        public boolean releaseCodeDeliveryForRetry(
                ProtectedRegistrationAccess access,
                VerificationChannel channel,
                HmacIdentifier sendOperationId,
                String messageId) {
            requireFlow(access, NOW);
            return sendOperationId.equals(deliveryOperations.get(channel));
        }

        @Override
        public boolean finalizeCodeDeliveryFailure(
                ProtectedRegistrationAccess access,
                VerificationChannel channel,
                HmacIdentifier sendOperationId) {
            return compensateCodeDeliveryFailure(access, channel, sendOperationId);
        }

        @Override
        public boolean compensateCodeDeliveryFailure(
                ProtectedRegistrationAccess access,
                VerificationChannel channel,
                HmacIdentifier sendOperationId) {
            requireFlow(access, NOW);
            if (!sendOperationId.equals(deliveryOperations.get(channel))) {
                return false;
            }
            deliveryOperations.remove(channel);
            codes.remove(channel);
            return true;
        }

        @Override
        public RegistrationFlowSnapshot verifyCode(
                ProtectedRegistrationAccess access,
                VerificationChannel channel,
                HmacIdentifier codeDigest,
                Instant now) {
            requireFlow(access, now);
            if (!codeDigest.equals(codes.remove(channel))) {
                throw error(RegistrationErrorCode.VERIFICATION_CODE_INVALID);
            }
            if (channel == VerificationChannel.EMAIL) {
                emailVerified = true;
            } else {
                phoneVerified = true;
            }
            return snapshot();
        }

        @Override
        public RegistrationCompletionClaim claimCompletion(
                ProtectedRegistrationAccess access,
                HmacIdentifier claimId,
                Instant now) {
            requireFlow(access, now);
            if (completing) {
                throw error(RegistrationErrorCode.REGISTRATION_ALREADY_COMPLETING);
            }
            if (!humanVerified) {
                throw error(RegistrationErrorCode.HUMAN_VERIFICATION_REQUIRED);
            }
            if (!emailVerified) {
                throw error(RegistrationErrorCode.EMAIL_VERIFICATION_REQUIRED);
            }
            if (!phoneVerified) {
                throw error(RegistrationErrorCode.PHONE_VERIFICATION_REQUIRED);
            }
            completing = true;
            this.claimId = claimId;
            return new RegistrationCompletionClaim(snapshot(), claimId);
        }

        @Override
        public void releaseCompletionClaim(
                ProtectedRegistrationAccess access, HmacIdentifier claimId) {
            releaseCalls++;
            if (claimId.equals(this.claimId)) {
                completing = false;
                this.claimId = null;
            }
        }

        @Override
        public void delete(ProtectedRegistrationAccess access) {
            deleted = true;
        }

        private void requireFlow(ProtectedRegistrationAccess access, Instant now) {
            if (flow == null || deleted) {
                throw error(RegistrationErrorCode.REGISTRATION_FLOW_NOT_FOUND);
            }
            if (!flow.access().equals(access)) {
                throw error(RegistrationErrorCode.REGISTRATION_FLOW_FORBIDDEN);
            }
            if (!now.isBefore(flow.expiresAt())) {
                throw error(RegistrationErrorCode.REGISTRATION_FLOW_EXPIRED);
            }
        }

        private RegistrationFlowSnapshot snapshot() {
            return new RegistrationFlowSnapshot(
                    flow.email(),
                    flow.phone(),
                    humanVerified,
                    emailVerified,
                    phoneVerified,
                    completing,
                    flow.createdAt(),
                    flow.expiresAt());
        }

        private static RegistrationException error(RegistrationErrorCode code) {
            return new RegistrationException(code, "controlled test flow error");
        }

        private UserLoginIdentity persistedIdentityProbe(UserLoginIdentityMapper mapper) {
            var captor = org.mockito.ArgumentCaptor.forClass(UserLoginIdentity.class);
            verify(mapper).insert(captor.capture());
            return captor.getValue();
        }

        private UserProfile persistedProfileProbe(UserProfileMapper mapper) {
            var captor = org.mockito.ArgumentCaptor.forClass(UserProfile.class);
            verify(mapper).insert(captor.capture());
            return captor.getValue();
        }

        private UserMembershipQuota persistedMembershipQuotaProbe(
                UserMembershipQuotaMapper mapper) {
            var captor = org.mockito.ArgumentCaptor.forClass(UserMembershipQuota.class);
            verify(mapper).insert(captor.capture());
            return captor.getValue();
        }
    }
}
