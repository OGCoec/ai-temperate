package com.example.temperate.service.auth.passwordreset.service.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceDecision;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceFilter;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceKind;
import com.example.temperate.service.auth.password.policy.PasswordStrengthPolicy;
import com.example.temperate.service.auth.password.policy.PasswordValidationException;
import com.example.temperate.service.auth.passwordreset.PasswordResetErrorCode;
import com.example.temperate.service.auth.passwordreset.PasswordResetException;
import com.example.temperate.service.auth.passwordreset.dto.ForgetTokenResult;
import com.example.temperate.service.auth.passwordreset.dto.PasswordResetAccess;
import com.example.temperate.service.auth.passwordreset.dto.PasswordResetStartCommand;
import com.example.temperate.service.auth.passwordreset.dto.PasswordResetStartResult;
import com.example.temperate.service.auth.passwordreset.flow.PasswordResetFlowSnapshot;
import com.example.temperate.service.auth.passwordreset.flow.PasswordResetFlowStore;
import com.example.temperate.service.auth.passwordreset.flow.ProtectedPasswordResetAccess;
import com.example.temperate.service.auth.passwordreset.notification.PasswordResetNotificationService;
import com.example.temperate.service.auth.passwordreset.service.PasswordResetService;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.session.authentication.service.SessionAuthenticationService;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.humanverification.HumanVerificationCommand;
import com.example.temperate.service.humanverification.HumanVerificationService;
import com.example.temperate.service.humanverification.HumanVerificationServiceRegistry;
import com.example.temperate.service.humanverification.HumanVerificationType;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationPurpose;
import com.example.temperate.service.registration.verification.delivery.operation.VerificationDeliveryOperationIdGenerator;
import com.example.temperate.service.registration.verification.delivery.rabbit.VerificationDeliveryPublisher;
import com.example.temperate.service.registration.verification.generator.VerificationCodeGenerator;
import com.example.temperate.service.registration.verification.service.resolver.VerificationDeliveryMethodPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 密码重置流程的业务协调器。
 *
 * <p>密码哈希更新在本地数据库事务中完成；一次性找回凭据消费、旧会话撤销和变更通知只能在事务提交后
 * 执行。若事务未提交，则释放已领取的找回凭据，保证用户可安全重试且不会出现“密码未改、凭据已失效”
 * 的状态。</p>
 */
@Service
public final class PasswordResetServiceImpl implements PasswordResetService {

    private static final int SESSION_REVOCATION_MAX_ATTEMPTS = 3;
    private static final System.Logger LOGGER =
            System.getLogger(PasswordResetServiceImpl.class.getName());

    private final UserLoginIdentityMapper identityMapper;
    private final RegistrationInputNormalizer inputNormalizer;
    private final PasswordStrengthPolicy passwordPolicy;
    private final PasswordResetFlowStore flowStore;
    private final AuthSessionSecretProtector protector;
    private final AuthTokenService tokenService;
    private final HumanVerificationServiceRegistry humanVerificationServices;
    private final VerificationCodeGenerator codeGenerator;
    private final VerificationDeliveryOperationIdGenerator operationIdGenerator;
    private final VerificationDeliveryPublisher deliveryPublisher;
    private final PasswordResetNotificationService notificationService;
    private final SessionAuthenticationService sessionService;
    private final PasswordEncoder passwordEncoder;
    private final IdentityPresenceFilter identityPresenceFilter;
    private final Clock clock;

    public PasswordResetServiceImpl(
            UserLoginIdentityMapper identityMapper,
            RegistrationInputNormalizer inputNormalizer,
            PasswordStrengthPolicy passwordPolicy,
            PasswordResetFlowStore flowStore,
            AuthSessionSecretProtector protector,
            AuthTokenService tokenService,
            HumanVerificationServiceRegistry humanVerificationServices,
            VerificationCodeGenerator codeGenerator,
            VerificationDeliveryOperationIdGenerator operationIdGenerator,
            VerificationDeliveryPublisher deliveryPublisher,
            PasswordResetNotificationService notificationService,
            SessionAuthenticationService sessionService,
            PasswordEncoder passwordEncoder,
            IdentityPresenceFilter identityPresenceFilter,
            Clock clock) {
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.inputNormalizer = Objects.requireNonNull(inputNormalizer);
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy);
        this.flowStore = Objects.requireNonNull(flowStore);
        this.protector = Objects.requireNonNull(protector);
        this.tokenService = Objects.requireNonNull(tokenService);
        this.humanVerificationServices = Objects.requireNonNull(humanVerificationServices);
        this.codeGenerator = Objects.requireNonNull(codeGenerator);
        this.operationIdGenerator = Objects.requireNonNull(operationIdGenerator);
        this.deliveryPublisher = Objects.requireNonNull(deliveryPublisher);
        this.notificationService = Objects.requireNonNull(notificationService);
        this.sessionService = Objects.requireNonNull(sessionService);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.identityPresenceFilter = Objects.requireNonNull(identityPresenceFilter);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public PasswordResetStartResult start(PasswordResetStartCommand command) {
        if (command == null || command.channel() == null) {
            throw invalid("请选择邮箱或短信找回密码。");
        }
        String identifier = normalize(command);
        String flowToken = tokenService.newFlowToken();
        String challengeHandle = tokenService.newFlowToken();
        ProtectedPasswordResetAccess access = protect(
                new PasswordResetAccess(
                        flowToken,
                        challengeHandle,
                        command.deviceInstallationId(),
                        command.clientIp()),
                identifier);
        if (flowStore.isBlocked(access.deviceHash(), access.globalDeviceHash())) {
            throw error(
                    PasswordResetErrorCode.RESET_BLOCKED,
                    "找回密码暂时被限制，请稍后再试。");
        }

        IdentityPresenceKind kind = command.channel() == VerificationChannel.EMAIL
                ? IdentityPresenceKind.EMAIL
                : IdentityPresenceKind.PHONE;
        IdentityPresenceDecision presence = kind == IdentityPresenceKind.EMAIL
                ? identityPresenceFilter.checkEmail(identifier)
                : identityPresenceFilter.checkPhone(identifier);
        UserLoginIdentity identity = null;
        if (presence != IdentityPresenceDecision.DEFINITELY_ABSENT) {
            identity = kind == IdentityPresenceKind.EMAIL
                    ? identityMapper.findByNormalizedEmail(identifier)
                    : identityMapper.findByNormalizedPhone(identifier);
            identityPresenceFilter.recordDatabaseVerification(
                    kind, presence, identity != null);
        }
        long userId = availableUserId(identity);
        Instant now = clock.instant();
        flowStore.create(access, command.channel(), identifier, userId, now);
        return new PasswordResetStartResult(flowToken, challengeHandle, now.plusSeconds(600));
    }

    @Override
    public Mono<Void> verifyTurnstile(
            PasswordResetAccess access, String turnstileToken) {
        return Mono.defer(() -> {
            String requestTraceId = traceId();
            ProtectedPasswordResetAccess protectedAccess = protect(access, null);
            Mono<Void> loadFlow = Mono.fromCallable(
                            () -> flowStore.getRequired(
                                    protectedAccess, clock.instant()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .then();
            HumanVerificationService turnstile =
                    humanVerificationServices.getRequired(HumanVerificationType.TURNSTILE);
            Mono<Void> siteverify = Mono.defer(() -> turnstile.verify(
                            HumanVerificationCommand.turnstile(
                                    turnstileToken,
                                    access.clientIp(),
                                    access.challengeHandle(),
                                    "password_reset")))
                    .contextWrite(context -> context.put(
                            HumanVerificationService.TRACE_ID_CONTEXT_KEY,
                            requestTraceId))
                    .onErrorMap(
                            RegistrationException.class,
                            exception -> new PasswordResetException(
                                    PasswordResetErrorCode.TURNSTILE_REJECTED,
                                    "人机验证未通过，请重试。",
                                    exception));
            // 密码重置流程同样只在供应商通过后执行阻塞 Redis 原子标记。
            Mono<Void> markVerified = Mono.fromRunnable(
                            () -> flowStore.markHumanVerified(
                                    protectedAccess, clock.instant()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .then();
            return loadFlow.then(siteverify).then(markVerified);
        });
    }

    @Override
    public void sendCode(PasswordResetAccess access) {
        sendCode(access, null);
    }

    @Override
    public void sendCode(
            PasswordResetAccess access, VerificationDeliveryMethod requestedDeliveryMethod) {
        ProtectedPasswordResetAccess initialAccess = protect(access, null);
        PasswordResetFlowSnapshot flow =
                flowStore.getRequired(initialAccess, clock.instant());
        if (!flow.humanVerified()) {
            throw error(
                    PasswordResetErrorCode.HUMAN_VERIFICATION_REQUIRED,
                    "请先完成人机验证。");
        }
        ProtectedPasswordResetAccess protectedAccess =
                withTarget(initialAccess, flow.identifier());
        VerificationDeliveryMethod deliveryMethod = requestedDeliveryMethod == null
                ? VerificationDeliveryMethod.defaultFor(flow.channel())
                : requestedDeliveryMethod;
        try {
            VerificationDeliveryMethodPolicy.requireSupported(
                    flow.channel(), deliveryMethod, flow.identifier());
        } catch (RegistrationException exception) {
            throw new PasswordResetException(
                    PasswordResetErrorCode.INVALID_INPUT,
                    "验证码投递方式不受支持。",
                    exception);
        }
        String code = codeGenerator.generate();
        HmacIdentifier operationId = protector.passwordResetDeliveryOperation(
                operationIdGenerator.generateRawOperationId());
        flowStore.issueCode(
                protectedAccess,
                protector.passwordResetCodeDigest(access.resetFlowToken(), code),
                operationId,
                clock.instant());

        if (flow.userId() <= 0) {
            if (flow.channel() == VerificationChannel.EMAIL) {
                notificationService.notifyEmailNotRegistered(flow.identifier());
            }
            flowStore.markDeliverySucceeded(protectedAccess, operationId);
            return;
        }

        try {
            deliveryPublisher.publishPasswordReset(
                    protectedAccess,
                    flow.channel(),
                    deliveryMethod,
                    operationId,
                    new VerificationDeliveryRequest(
                            flow.identifier(),
                            code,
                            VerificationPurpose.PASSWORD_RESET),
                    clock.instant().plus(Duration.ofMinutes(5)));
        } catch (RuntimeException exception) {
            flowStore.compensateDeliveryFailure(protectedAccess, operationId);
            throw new PasswordResetException(
                    PasswordResetErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "验证码发送服务暂时不可用。",
                    exception);
        }
    }

    @Override
    public ForgetTokenResult verifyCode(PasswordResetAccess access, String code) {
        ProtectedPasswordResetAccess initialAccess = protect(access, null);
        PasswordResetFlowSnapshot flow =
                flowStore.getRequired(initialAccess, clock.instant());
        ProtectedPasswordResetAccess protectedAccess =
                withTarget(initialAccess, flow.identifier());
        String forgetToken = tokenService.newFlowToken();
        HmacIdentifier forgetTokenHash = protector.passwordResetForgetToken(forgetToken);
        long userId = flowStore.verifyAndCreateForgetToken(
                protectedAccess,
                protector.passwordResetCodeDigest(access.resetFlowToken(), code),
                forgetTokenHash,
                clock.instant());
        if (userId <= 0) {
            throw error(
                    PasswordResetErrorCode.VERIFICATION_CODE_INVALID,
                    "验证码不正确或已过期。");
        }
        return new ForgetTokenResult(forgetToken, clock.instant().plusSeconds(300));
    }

    /**
     * 在单个数据库事务内更新密码版本，并将不可回滚的会话撤销和找回凭据消费延后到提交成功之后。
     */
    @Override
    @Transactional
    public void complete(
            String forgetToken,
            String deviceInstallationId,
            String password,
            String passwordConfirmation) {
        validatePassword(password, passwordConfirmation);
        HmacIdentifier forgetTokenHash;
        HmacIdentifier deviceHash;
        HmacIdentifier claimId = protector.passwordResetClaim(UUID.randomUUID().toString());
        try {
            forgetTokenHash = protector.passwordResetForgetToken(forgetToken);
            deviceHash = protector.device(deviceInstallationId);
        } catch (RuntimeException exception) {
            throw new PasswordResetException(
                    PasswordResetErrorCode.FORGET_TOKEN_INVALID,
                    "重置密码凭证无效或已过期。",
                    exception);
        }

        // 先原子领取一次性凭据，阻止两个并发请求同时完成同一轮密码重置。
        long userId = flowStore.claimForgetToken(
                forgetTokenHash, deviceHash, claimId, clock.instant());
        boolean synchronizationRegistered = false;
        try {
            AuthenticationContext context = identityMapper.findAuthenticationById(userId);
            if (context == null
                    || context.getIdentityId() != userId
                    || context.getAccountStatus() != AccountStatus.ACTIVE) {
                throw error(
                        PasswordResetErrorCode.FORGET_TOKEN_INVALID,
                        "重置密码凭证无效或已过期。");
            }
            String encodedPassword = passwordEncoder.encode(password);
            // 密码哈希与凭据版本必须原子更新；登录身份的通用更新时间由数据库触发器统一刷新。
            int affected = identityMapper.updatePasswordHashAndIncrementVersion(
                    userId,
                    encodedPassword);
            if (affected != 1) {
                throw error(
                        PasswordResetErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                        "密码暂时无法重置，请稍后再试。");
            }
            // 数据库提交前绝不消费找回凭据或撤销会话；否则后续回滚会留下不可恢复的外部副作用。
            registerAfterCompletion(
                    forgetTokenHash, claimId, userId, context.getEmail());
            synchronizationRegistered = true;
        } catch (RuntimeException exception) {
            if (!synchronizationRegistered) {
                releaseClaim(forgetTokenHash, claimId);
            }
            throw exception;
        }
    }

    private void registerAfterCompletion(
            HmacIdentifier forgetTokenHash,
            HmacIdentifier claimId,
            long userId,
            String normalizedEmail) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 无法把领取状态绑定到事务结果时立即释放，避免有效凭据因基础设施异常被永久占用。
            flowStore.releaseForgetToken(forgetTokenHash, claimId);
            throw error(
                    PasswordResetErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "密码暂时无法重置，请稍后再试。");
        }
        // afterCommit 内的操作已无法参与数据库回滚，因此只放置已提交数据所必需的外部副作用。
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        completeCommittedReset(
                                forgetTokenHash, claimId, userId, normalizedEmail);
                    }

                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            // 回滚或未知完成状态均归还领取权，保留用户使用同一有效凭据重试的机会。
                            releaseClaim(forgetTokenHash, claimId);
                        }
                    }
                });
    }

    private void completeCommittedReset(
            HmacIdentifier forgetTokenHash,
            HmacIdentifier claimId,
            long userId,
            String normalizedEmail) {
        try {
            flowStore.consumeForgetToken(forgetTokenHash, claimId);
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "event=password_reset_forget_token_consume_failed");
        }
        // 提交后密码已不可回滚；会话撤销采用有限重试，避免无限重试占用请求线程。
        try {
            revokeAllSessionsWithRetry(userId);
        } finally {
            // 密码变更已经提交，即使会话撤销暂时失败也必须发送安全通知。
            notificationService.notifyPasswordChanged(normalizedEmail);
        }
    }

    private void revokeAllSessionsWithRetry(long userId) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= SESSION_REVOCATION_MAX_ATTEMPTS; attempt++) {
            try {
                sessionService.revokeAllForUser(userId);
                return;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "event=password_reset_session_revoke_retry_failed attempt=" + attempt);
            }
        }
        throw new PasswordResetException(
                PasswordResetErrorCode.SESSION_REVOCATION_FAILED,
                "密码已经修改，但旧会话暂时无法全部撤销，请使用新密码重新登录并稍后重试。",
                lastFailure);
    }

    private void releaseClaim(HmacIdentifier forgetTokenHash, HmacIdentifier claimId) {
        try {
            flowStore.releaseForgetToken(forgetTokenHash, claimId);
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "event=password_reset_forget_token_release_failed");
        }
    }

    private long availableUserId(UserLoginIdentity identity) {
        if (identity == null || identity.getId() == null) {
            return 0L;
        }
        AuthenticationContext context =
                identityMapper.findAuthenticationById(identity.getId());
        return context != null && context.getAccountStatus() == AccountStatus.ACTIVE
                ? identity.getId()
                : 0L;
    }

    private String normalize(PasswordResetStartCommand command) {
        try {
            return command.channel() == VerificationChannel.EMAIL
                    ? inputNormalizer.normalizeEmail(command.email())
                    : inputNormalizer.normalizePhone(
                            command.countryIso2(), command.phoneNumber());
        } catch (RegistrationException exception) {
            throw new PasswordResetException(
                    PasswordResetErrorCode.INVALID_INPUT,
                    "邮箱或手机号格式不正确。",
                    exception);
        }
    }

    private void validatePassword(String password, String confirmation) {
        try {
            passwordPolicy.validateForWrite(password, confirmation);
        } catch (PasswordValidationException exception) {
            PasswordResetErrorCode code = exception.reason()
                    == PasswordValidationException.Reason.STRENGTH_INSUFFICIENT
                    ? PasswordResetErrorCode.PASSWORD_STRENGTH_INSUFFICIENT
                    : PasswordResetErrorCode.INVALID_INPUT;
            throw new PasswordResetException(
                    code,
                    "两次密码必须一致，且密码需要达到中等或更高强度。",
                    exception);
        }
    }

    private ProtectedPasswordResetAccess protect(
            PasswordResetAccess access, String normalizedTarget) {
        if (access == null) {
            throw invalid("找回密码流程参数不正确。");
        }
        try {
            HmacIdentifier flowId =
                    protector.passwordResetFlowToken(access.resetFlowToken());
            return new ProtectedPasswordResetAccess(
                    flowId,
                    protector.passwordResetChallenge(access.challengeHandle()),
                    protector.device(access.deviceInstallationId()),
                    protector.deviceBlock(access.deviceInstallationId()),
                    protector.passwordResetCodeKey(access.resetFlowToken()),
                    normalizedTarget == null
                            ? flowId
                            : protector.passwordResetTarget(normalizedTarget));
        } catch (RuntimeException exception) {
            throw new PasswordResetException(
                    PasswordResetErrorCode.RESET_FLOW_FORBIDDEN,
                    "找回密码流程无效，请重新开始。",
                    exception);
        }
    }

    private ProtectedPasswordResetAccess withTarget(
            ProtectedPasswordResetAccess access, String normalizedTarget) {
        return new ProtectedPasswordResetAccess(
                access.flowId(),
                access.challengeId(),
                access.deviceHash(),
                access.globalDeviceHash(),
                access.codeId(),
                protector.passwordResetTarget(normalizedTarget));
    }

    private static PasswordResetException invalid(String message) {
        return error(PasswordResetErrorCode.INVALID_INPUT, message);
    }

    private static PasswordResetException error(
            PasswordResetErrorCode code, String message) {
        return new PasswordResetException(code, message);
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank() ? "absent" : value;
    }
}
