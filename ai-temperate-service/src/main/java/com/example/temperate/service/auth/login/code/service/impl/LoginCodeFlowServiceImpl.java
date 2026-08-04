package com.example.temperate.service.auth.login.code.service.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceDecision;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceFilter;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceKind;
import com.example.temperate.service.auth.login.code.dto.LoginCodeAccess;
import com.example.temperate.service.auth.login.code.dto.LoginCodeStartCommand;
import com.example.temperate.service.auth.login.code.dto.LoginCodeStartResult;
import com.example.temperate.service.auth.login.code.flow.LoginCodeFlowSnapshot;
import com.example.temperate.service.auth.login.code.flow.LoginCodeFlowStore;
import com.example.temperate.service.auth.login.code.flow.ProtectedLoginCodeAccess;
import com.example.temperate.service.auth.login.code.service.LoginCodeFlowService;
import com.example.temperate.service.auth.login.completion.LoginCompletionService;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.login.limit.dto.LoginAttempt;
import com.example.temperate.service.auth.login.limit.enums.LoginFailureBucket;
import com.example.temperate.service.auth.login.limit.enums.LoginLimitDecision;
import com.example.temperate.service.auth.login.limit.service.LoginRateLimitService;
import com.example.temperate.service.auth.login.notification.LoginAccountNotificationService;
import com.example.temperate.service.auth.login.strategy.LoginStrategyRequest;
import com.example.temperate.service.auth.login.strategy.LoginStrategyType;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
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
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 编排验证码登录的创建、风控、人机校验、异步投递、验证码消费和会话签发。
 *
 * <p>该服务以 Redis 流程状态作为一次性操作依据，并在每个外部副作用前后调用原子流程存储；它不在内存中
 * 缓存验证码或流程 Token，避免多实例部署下出现状态分叉。</p>
 */
@Service
public final class LoginCodeFlowServiceImpl implements LoginCodeFlowService {

    private final UserLoginIdentityMapper identityMapper;
    private final RegistrationInputNormalizer inputNormalizer;
    private final LoginCodeFlowStore flowStore;
    private final AuthSessionSecretProtector protector;
    private final AuthTokenService tokenService;
    private final HumanVerificationServiceRegistry humanVerificationServices;
    private final VerificationCodeGenerator codeGenerator;
    private final VerificationDeliveryOperationIdGenerator operationIdGenerator;
    private final VerificationDeliveryPublisher deliveryPublisher;
    private final LoginAccountNotificationService notificationService;
    private final LoginRateLimitService rateLimitService;
    private final LoginCompletionService completionService;
    private final IdentityPresenceFilter identityPresenceFilter;
    private final Clock clock;

    public LoginCodeFlowServiceImpl(
            UserLoginIdentityMapper identityMapper,
            RegistrationInputNormalizer inputNormalizer,
            LoginCodeFlowStore flowStore,
            AuthSessionSecretProtector protector,
            AuthTokenService tokenService,
            HumanVerificationServiceRegistry humanVerificationServices,
            VerificationCodeGenerator codeGenerator,
            VerificationDeliveryOperationIdGenerator operationIdGenerator,
            VerificationDeliveryPublisher deliveryPublisher,
            LoginAccountNotificationService notificationService,
            LoginRateLimitService rateLimitService,
            LoginCompletionService completionService,
            IdentityPresenceFilter identityPresenceFilter,
            Clock clock) {
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.inputNormalizer = Objects.requireNonNull(inputNormalizer);
        this.flowStore = Objects.requireNonNull(flowStore);
        this.protector = Objects.requireNonNull(protector);
        this.tokenService = Objects.requireNonNull(tokenService);
        this.humanVerificationServices = Objects.requireNonNull(humanVerificationServices);
        this.codeGenerator = Objects.requireNonNull(codeGenerator);
        this.operationIdGenerator = Objects.requireNonNull(operationIdGenerator);
        this.deliveryPublisher = Objects.requireNonNull(deliveryPublisher);
        this.notificationService = Objects.requireNonNull(notificationService);
        this.rateLimitService = Objects.requireNonNull(rateLimitService);
        this.completionService = Objects.requireNonNull(completionService);
        this.identityPresenceFilter = Objects.requireNonNull(identityPresenceFilter);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public LoginCodeStartResult start(LoginCodeStartCommand command) {
        Objects.requireNonNull(command);
        requireCodeType(command.strategyType());
        String identifier = normalize(command);
        IdentityPresenceKind kind =
                command.strategyType() == LoginStrategyType.EMAIL_CODE
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
        return createFlow(
                command.strategyType(),
                identifier,
                identity == null || identity.getId() == null ? 0L : identity.getId(),
                command.deviceInstallationId(),
                command.clientIp());
    }

    @Override
    public LoginCodeStartResult startForVerifiedIdentity(
            long userId,
            LoginStrategyType type,
            String deviceInstallationId,
            String clientIp) {
        requireCodeType(type);
        AuthenticationContext context = identityMapper.findAuthenticationById(userId);
        if (context == null
                || context.getIdentityId() != userId
                || context.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new LoginException(
                    LoginErrorCode.ACCOUNT_UNAVAILABLE,
                    "Account is unavailable.");
        }
        String identifier = type == LoginStrategyType.EMAIL_CODE
                ? context.getEmail()
                : context.getPhone();
        if (identifier == null || identifier.isBlank()) {
            throw new LoginException(
                    LoginErrorCode.INVALID_INPUT,
                    "The selected verification channel is unavailable.");
        }
        return createFlow(type, identifier, userId, deviceInstallationId, clientIp);
    }

    private LoginCodeStartResult createFlow(
            LoginStrategyType type,
            String identifier,
            long userId,
            String deviceInstallationId,
            String clientIp) {
        LoginAttempt attempt = new LoginAttempt(identifier, deviceInstallationId);
        requireAllowed(attempt);
        String flowToken = tokenService.newFlowToken();
        String challenge = tokenService.newFlowToken();
        ProtectedLoginCodeAccess access = protect(
                new LoginCodeAccess(flowToken, challenge,
                        deviceInstallationId, clientIp));
        Instant now = clock.instant();
        // 无论身份是否存在都创建同样的流程并返回相同结构，避免启动接口成为账号枚举探针。
        flowStore.create(access, type, identifier, userId, now);
        return new LoginCodeStartResult(flowToken, challenge, now.plusSeconds(600));
    }

    @Override
    public Mono<Void> verifyTurnstile(LoginCodeAccess access, String turnstileToken) {
        return Mono.defer(() -> {
            String requestTraceId = traceId();
            ProtectedLoginCodeAccess protectedAccess = protect(access);
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
                                    "login")))
                    .contextWrite(context -> context.put(
                            HumanVerificationService.TRACE_ID_CONTEXT_KEY,
                            requestTraceId))
                    .onErrorMap(
                            RegistrationException.class,
                            exception -> new LoginException(
                                    LoginErrorCode.TURNSTILE_REJECTED,
                                    "Turnstile verification was rejected.",
                                    exception));
            // 只有 Siteverify 成功后才在线程池执行阻塞 Redis Lua，失败时不写入 humanVerified。
            Mono<Void> markVerified = Mono.fromRunnable(
                            () -> flowStore.markHumanVerified(
                                    protectedAccess, clock.instant()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .then();
            return loadFlow.then(siteverify).then(markVerified);
        });
    }

    @Override
    public void sendCode(LoginCodeAccess access) {
        sendCode(access, null);
    }

    @Override
    public void sendCode(
            LoginCodeAccess access, VerificationDeliveryMethod requestedDeliveryMethod) {
        ProtectedLoginCodeAccess protectedAccess = protect(access);
        LoginCodeFlowSnapshot flow = flowStore.getRequired(protectedAccess, clock.instant());
        if (!flow.humanVerified()) {
            throw new LoginException(LoginErrorCode.HUMAN_VERIFICATION_REQUIRED,
                    "Human verification is required.");
        }
        LoginAttempt attempt = new LoginAttempt(
                flow.identifier(), access.deviceInstallationId());
        requireAllowed(attempt);
        VerificationChannel channel = flow.strategyType() == LoginStrategyType.EMAIL_CODE
                ? VerificationChannel.EMAIL : VerificationChannel.SMS;
        VerificationDeliveryMethod deliveryMethod = requestedDeliveryMethod == null
                ? VerificationDeliveryMethod.defaultFor(channel)
                : requestedDeliveryMethod;
        try {
            VerificationDeliveryMethodPolicy.requireSupported(
                    channel, deliveryMethod, flow.identifier());
        } catch (RegistrationException exception) {
            throw new LoginException(
                    LoginErrorCode.INVALID_INPUT,
                    "Verification delivery method is unsupported.",
                    exception);
        }
        String code = codeGenerator.generate();
        HmacIdentifier digest = protector.loginCodeDigest(access.flowToken(), code);
        HmacIdentifier operationId = protector.loginDeliveryOperation(
                operationIdGenerator.generateRawOperationId());
        flowStore.issueCode(protectedAccess, digest, operationId, clock.instant());
        if (flow.userId() <= 0) {
            if (flow.strategyType() == LoginStrategyType.EMAIL_CODE) {
                notificationService.notifyEmailNotRegistered(flow.identifier());
            }
            flowStore.markDeliverySucceeded(protectedAccess, operationId);
            return;
        }
        // 先在 Redis 原子登记待投递操作，再发布 MQ；发布确认失败只补偿当前 operation，避免误删新的发码状态。
        try {
            deliveryPublisher.publishLogin(
                    protectedAccess,
                    channel,
                    deliveryMethod,
                    operationId,
                    new VerificationDeliveryRequest(
                            flow.identifier(), code, VerificationPurpose.LOGIN),
                    clock.instant().plus(Duration.ofMinutes(5)));
        } catch (RuntimeException exception) {
            flowStore.compensateDeliveryFailure(protectedAccess, operationId);
            throw new LoginException(LoginErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "Verification delivery is unavailable.", exception);
        }
    }

    @Override
    public LoginResult verifyAndLogin(
            LoginStrategyType type, LoginStrategyRequest request) {
        AuthenticationContext context = verifyPrimaryFactor(type, request);
        return completionService.complete(context, request.deviceInstallationId());
    }

    @Override
    public AuthenticationContext verifyPrimaryFactor(
            LoginStrategyType type, LoginStrategyRequest request) {
        requireCodeType(type);
        if (request == null) throw invalid();
        LoginCodeAccess access = new LoginCodeAccess(
                request.loginFlowToken(), request.challengeHandle(),
                request.deviceInstallationId(), request.clientIp());
        ProtectedLoginCodeAccess protectedAccess = protect(access);
        LoginCodeFlowSnapshot flow = flowStore.getRequired(protectedAccess, clock.instant());
        if (flow.strategyType() != type) {
            throw new LoginException(LoginErrorCode.LOGIN_FLOW_FORBIDDEN,
                    "Login strategy does not match the flow.");
        }
        LoginAttempt attempt = new LoginAttempt(
                flow.identifier(), request.deviceInstallationId());
        requireAllowed(attempt);
        final LoginCodeFlowSnapshot verified;
        try {
            verified = flowStore.verifyCode(
                    protectedAccess,
                    protector.loginCodeDigest(
                            request.loginFlowToken(), request.verificationCode()),
                    clock.instant());
        } catch (LoginException exception) {
            if (exception.code() == LoginErrorCode.VERIFICATION_CODE_INVALID
                    || exception.code() == LoginErrorCode.VERIFICATION_CODE_EXPIRED) {
                if (rateLimitService.recordFailure(attempt, LoginFailureBucket.CODE)
                        == LoginLimitDecision.BLOCKED) {
                    throw new LoginException(LoginErrorCode.LOGIN_BLOCKED,
                            "Login is temporarily blocked.");
                }
            }
            throw exception;
        }
        if (verified.userId() <= 0) {
            rateLimitService.recordFailure(attempt, LoginFailureBucket.CODE);
            throw new LoginException(LoginErrorCode.AUTHENTICATION_FAILED,
                    "Verification code is invalid.");
        }
        AuthenticationContext context = identityMapper.findAuthenticationById(verified.userId());
        if (context == null || context.getAccountStatus() != AccountStatus.ACTIVE
                || context.getIdentityId() != verified.userId()) {
            throw new LoginException(LoginErrorCode.ACCOUNT_UNAVAILABLE,
                    "Account is unavailable.");
        }
        // 验证码已原子消费且账号仍可用后立即删除第一因子流程；后续登录完成或敏感操作复验不得再次使用该验证码。
        flowStore.delete(protectedAccess);
        rateLimitService.clearSubjectFailures(attempt);
        return context;
    }

    private String normalize(LoginCodeStartCommand command) {
        if (command.strategyType() == LoginStrategyType.EMAIL_CODE) {
            return inputNormalizer.normalizeEmail(command.email());
        }
        return inputNormalizer.normalizePhone(command.countryIso2(), command.phoneNumber());
    }

    private ProtectedLoginCodeAccess protect(LoginCodeAccess access) {
        if (access == null) throw invalid();
        return new ProtectedLoginCodeAccess(
                protector.loginFlowToken(access.flowToken()),
                protector.loginChallenge(access.challengeHandle()),
                protector.device(access.deviceInstallationId()),
                protector.loginCodeKey(access.flowToken()));
    }

    private void requireAllowed(LoginAttempt attempt) {
        if (rateLimitService.check(attempt, LoginFailureBucket.CODE)
                == LoginLimitDecision.BLOCKED) {
            throw new LoginException(LoginErrorCode.LOGIN_BLOCKED,
                    "Login is temporarily blocked.");
        }
    }

    private static void requireCodeType(LoginStrategyType type) {
        if (type != LoginStrategyType.EMAIL_CODE && type != LoginStrategyType.SMS_CODE) {
            throw invalid();
        }
    }

    private static LoginException invalid() {
        return new LoginException(LoginErrorCode.INVALID_INPUT,
                "Code login request is invalid.");
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank() ? "absent" : value;
    }
}
