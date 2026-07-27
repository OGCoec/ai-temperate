package com.example.temperate.service.admin.registration.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.config.AdminConfiguration;
import com.example.temperate.service.admin.config.AdminConfigurationService;
import com.example.temperate.service.admin.config.AdminStatus;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.service.admin.registration.AdminRegistrationCompleteCommand;
import com.example.temperate.service.admin.registration.AdminRegistrationService;
import com.example.temperate.service.auth.password.policy.PasswordStrengthPolicy;
import com.example.temperate.service.auth.password.policy.PasswordValidationException;
import com.example.temperate.service.humanverification.HumanVerificationCommand;
import com.example.temperate.service.humanverification.HumanVerificationService;
import com.example.temperate.service.humanverification.HumanVerificationServiceRegistry;
import com.example.temperate.service.humanverification.HumanVerificationType;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import com.example.temperate.service.registration.component.token.RegistrationTokenGenerator;
import com.example.temperate.service.registration.dto.command.RegistrationSendCodeCommand;
import com.example.temperate.service.registration.dto.command.RegistrationStartCommand;
import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodesCommand;
import com.example.temperate.service.registration.dto.query.RegistrationStatusQuery;
import com.example.temperate.service.registration.dto.result.RegistrationStartResult;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.dto.result.VerificationDispatchResult;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.RegistrationStatus;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.flow.domain.RegistrationActor;
import com.example.temperate.service.registration.flow.domain.RegistrationCompletionClaim;
import com.example.temperate.service.registration.flow.domain.RegistrationFlow;
import com.example.temperate.service.registration.flow.domain.RegistrationFlowSnapshot;
import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import com.example.temperate.service.registration.flow.security.RegistrationAccess;
import com.example.temperate.service.registration.flow.store.RegistrationFlowStore;
import com.example.temperate.service.registration.verification.delivery.coordinator.VerificationDeliveryCoordinator;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationPurpose;
import com.example.temperate.service.registration.verification.delivery.operation.VerificationDeliveryOperationIdGenerator;
import com.example.temperate.service.registration.verification.generator.VerificationCodeGenerator;
import com.example.temperate.service.registration.verification.service.resolver.VerificationDeliveryMethodPolicy;
import com.example.temperate.service.registration.flow.security.RegistrationTokenProtector;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 复用注册 Redis 状态机完成管理员 hCaptcha、双验证码和一次性隐藏文件初始化。
 *
 * <p>与普通用户注册的区别仅在起点不查询 PostgreSQL、终点不开户；验证码摘要、发送风控、RabbitMQ 可靠投递、
 * 双码原子消费和完成权领取继续使用同一实现，避免两套安全状态机产生语义漂移。</p>
 */
@Service
public final class AdminRegistrationServiceImpl implements AdminRegistrationService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AdminRegistrationServiceImpl.class);
    private static final Pattern SAFE_TRACE_ID =
            Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Duration REQUIRED_FLOW_TTL = Duration.ofMinutes(10);
    private static final Duration ABSOLUTE_FLOW_TTL = Duration.ofMinutes(30);
    private static final Duration CODE_TTL = Duration.ofMinutes(5);

    private final AdminConfigurationService configurationService;
    private final RegistrationFlowStore flowStore;
    private final RegistrationInputNormalizer inputNormalizer;
    private final RegistrationTokenProtector tokenProtector;
    private final RegistrationTokenGenerator tokenGenerator;
    private final VerificationCodeGenerator codeGenerator;
    private final VerificationDeliveryOperationIdGenerator operationIdGenerator;
    private final VerificationDeliveryCoordinator deliveryCoordinator;
    private final HumanVerificationServiceRegistry humanVerificationServices;
    private final PasswordStrengthPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Duration flowTtl;

    public AdminRegistrationServiceImpl(
            AdminConfigurationService configurationService,
            RegistrationFlowStore flowStore,
            RegistrationInputNormalizer inputNormalizer,
            RegistrationTokenProtector tokenProtector,
            RegistrationTokenGenerator tokenGenerator,
            VerificationCodeGenerator codeGenerator,
            VerificationDeliveryOperationIdGenerator operationIdGenerator,
            VerificationDeliveryCoordinator deliveryCoordinator,
            HumanVerificationServiceRegistry humanVerificationServices,
            PasswordStrengthPolicy passwordPolicy,
            PasswordEncoder passwordEncoder,
            Clock clock,
            AdminProperties properties) {
        this.configurationService = Objects.requireNonNull(configurationService);
        this.flowStore = Objects.requireNonNull(flowStore);
        this.inputNormalizer = Objects.requireNonNull(inputNormalizer);
        this.tokenProtector = Objects.requireNonNull(tokenProtector);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.codeGenerator = Objects.requireNonNull(codeGenerator);
        this.operationIdGenerator = Objects.requireNonNull(operationIdGenerator);
        this.deliveryCoordinator = Objects.requireNonNull(deliveryCoordinator);
        this.humanVerificationServices = Objects.requireNonNull(humanVerificationServices);
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.clock = Objects.requireNonNull(clock);
        this.flowTtl = Objects.requireNonNull(properties).registrationFlowTtl();
        if (!REQUIRED_FLOW_TTL.equals(flowTtl)) {
            throw new IllegalArgumentException(
                    "Admin registration flow TTL must be exactly ten minutes.");
        }
    }

    @Override
    public RegistrationStartResult start(RegistrationStartCommand command) {
        configurationService.requireUninitialized();
        Objects.requireNonNull(command, "command must not be null");
        try {
            String email = inputNormalizer.normalizeEmail(command.email());
            String phone = inputNormalizer.normalizePhone(
                    command.countryIso2(), command.nationalPhoneNumber());
            RegistrationActor actor = tokenProtector.protectActor(
                    command.deviceInstallationId(), command.canonicalIp());
            if (flowStore.isBlocked(actor)) {
                throw new AdminException(
                        AdminErrorCode.ADMIN_RATE_LIMITED,
                        "Administrator registration is temporarily limited.");
            }

            String registerToken = tokenGenerator.newRegisterToken();
            String flowCsrf = tokenGenerator.newFlowCsrf();
            String challenge = tokenGenerator.newChallengeHandle();
            RegistrationAccess access = new RegistrationAccess(
                    registerToken,
                    flowCsrf,
                    challenge,
                    command.deviceInstallationId(),
                    command.canonicalIp());
            Instant createdAt = clock.instant();
            flowStore.create(new RegistrationFlow(
                    RegistrationFlow.CURRENT_SCHEMA_VERSION,
                    email,
                    phone,
                    tokenProtector.protect(access),
                    createdAt,
                    createdAt.plus(flowTtl),
                    createdAt.plus(ABSOLUTE_FLOW_TTL)));
            return new RegistrationStartResult(
                    registerToken,
                    flowCsrf,
                    challenge,
                    createdAt.plus(flowTtl));
        } catch (AdminException exception) {
            throw exception;
        } catch (RegistrationException exception) {
            throw translate(exception);
        }
    }

    @Override
    public RegistrationStatusResult status(RegistrationStatusQuery query) {
        configurationService.requireUninitialized();
        try {
            return toStatus(flowStore.getRequired(
                    tokenProtector.protect(query.access()), clock.instant()));
        } catch (RegistrationException exception) {
            throw translate(exception);
        }
    }

    @Override
    public Mono<RegistrationStatusResult> verifyHcaptcha(
            RegistrationAccess access,
            String hcaptchaToken) {
        // 阻塞 Redis 读取和原子标记分别放到 boundedElastic，Siteverify 保持 WebClient 事件链非阻塞。
        return Mono.defer(() -> {
            configurationService.requireUninitialized();
            String currentTraceId = traceId();
            ProtectedRegistrationAccess protectedAccess;
            try {
                protectedAccess = tokenProtector.protect(access);
            } catch (RegistrationException exception) {
                return Mono.error(translate(exception));
            }
            HumanVerificationService hcaptcha =
                    humanVerificationServices.getRequired(HumanVerificationType.HCAPTCHA);
            Mono<Void> load = Mono.fromCallable(
                            () -> flowStore.getRequired(protectedAccess, clock.instant()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorMap(RegistrationException.class, AdminRegistrationServiceImpl::translate)
                    .then();
            return load.then(hcaptcha.verify(HumanVerificationCommand.hcaptcha(
                            hcaptchaToken,
                            access.canonicalIp(),
                            access.challengeHandle()))
                    .contextWrite(context -> context.put(
                            HumanVerificationService.TRACE_ID_CONTEXT_KEY,
                            currentTraceId)))
                    .then(Mono.fromCallable(() -> finalizeHumanVerification(
                                    protectedAccess,
                                    currentTraceId))
                            .subscribeOn(Schedulers.boundedElastic()));
        });
    }

    @Override
    public VerificationDispatchResult sendCode(RegistrationSendCodeCommand command) {
        configurationService.requireUninitialized();
        Objects.requireNonNull(command);
        try {
            VerificationChannel channel = Objects.requireNonNull(command.channel());
            VerificationDeliveryMethod method = Objects.requireNonNull(command.deliveryMethod());
            ProtectedRegistrationAccess access = tokenProtector.protect(command.access());
            RegistrationFlowSnapshot snapshot = flowStore.getRequired(access, clock.instant());
            if (!snapshot.humanVerified()) {
                throw new AdminException(
                        AdminErrorCode.HCAPTCHA_REJECTED,
                        "Human verification is required.");
            }
            String destination = channel == VerificationChannel.EMAIL
                    ? snapshot.email()
                    : snapshot.phone();
            try {
                VerificationDeliveryMethodPolicy.requireSupported(channel, method, destination);
            } catch (RegistrationException exception) {
                throw translate(exception);
            }
            String code = codeGenerator.generate();
            HmacIdentifier codeDigest = tokenProtector.codeDigest(
                    command.access().registerToken(), channel, code);
            HmacIdentifier operationId = tokenProtector.deliveryOperationDigest(
                    operationIdGenerator.generateRawOperationId());
            Instant acceptedAt = clock.instant();
            flowStore.issueCode(access, channel, codeDigest, operationId, acceptedAt);
            try {
                deliveryCoordinator.deliver(
                        access,
                        channel,
                        method,
                        operationId,
                        new VerificationDeliveryRequest(
                                destination,
                                code,
                                VerificationPurpose.ADMIN_REGISTRATION),
                        acceptedAt.plus(CODE_TTL));
            } catch (RuntimeException exception) {
                flowStore.compensateCodeDeliveryFailure(access, channel, operationId);
                if (exception instanceof RegistrationException registrationException) {
                    throw translate(registrationException);
                }
                throw new AdminException(
                        AdminErrorCode.ADMIN_INFRASTRUCTURE_UNAVAILABLE,
                        "Verification delivery is temporarily unavailable.",
                        exception);
            }
            return new VerificationDispatchResult(channel, acceptedAt);
        } catch (AdminException exception) {
            throw exception;
        } catch (RegistrationException exception) {
            throw translate(exception);
        }
    }

    @Override
    public RegistrationStatusResult verifyCodes(RegistrationVerifyCodesCommand command) {
        configurationService.requireUninitialized();
        if (command == null
                || !isCode(command.emailCode())
                || !isCode(command.smsCode())) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_VERIFICATION_INVALID,
                    "Both verification codes are required.");
        }
        try {
            ProtectedRegistrationAccess access = tokenProtector.protect(command.access());
            flowStore.getRequired(access, clock.instant());
            HmacIdentifier emailDigest = tokenProtector.codeDigest(
                    command.access().registerToken(),
                    VerificationChannel.EMAIL,
                    command.emailCode());
            HmacIdentifier phoneDigest = tokenProtector.codeDigest(
                    command.access().registerToken(),
                    VerificationChannel.SMS,
                    command.smsCode());
            return toStatus(flowStore.verifyCodes(
                    access, emailDigest, phoneDigest, clock.instant()));
        } catch (RegistrationException exception) {
            throw translate(exception);
        }
    }

    @Override
    public void complete(AdminRegistrationCompleteCommand command) {
        configurationService.requireUninitialized();
        Objects.requireNonNull(command);
        validatePassword(command.password(), command.passwordConfirmation());
        ProtectedRegistrationAccess access;
        RegistrationCompletionClaim claim;
        try {
            access = tokenProtector.protect(command.access());
            RegistrationFlowSnapshot snapshot =
                    flowStore.getRequired(access, clock.instant());
            if (!snapshot.readyToComplete()) {
                throw new AdminException(
                        AdminErrorCode.ADMIN_VERIFICATION_INVALID,
                        "Administrator verification is incomplete.");
            }
            String passwordHash = passwordEncoder.encode(command.password());
            HmacIdentifier claimId = tokenProtector.completionClaimDigest(
                    tokenGenerator.newCompletionClaim());
            claim = flowStore.claimCompletion(access, claimId, clock.instant());
            try {
                Instant now = clock.instant();
                RegistrationFlowSnapshot claimed = claim.snapshot();
                AdminConfiguration configuration = new AdminConfiguration(
                        AdminConfiguration.CURRENT_SCHEMA_VERSION,
                        AdminStatus.ACTIVE,
                        claimed.email(),
                        countryIso2(claimed.phone()),
                        claimed.phone(),
                        passwordHash,
                        now,
                        now);
                // 文件 Store 在独占锁内再次检查 UNINITIALIZED，形成绕过前端和并发初始化的最终防线。
                configurationService.initialize(configuration);
                flowStore.delete(access);
            } catch (RuntimeException exception) {
                flowStore.releaseCompletionClaim(access, claimId);
                throw exception;
            }
        } catch (AdminException exception) {
            throw exception;
        } catch (RegistrationException exception) {
            throw translate(exception);
        }
    }

    private void validatePassword(String password, String confirmation) {
        try {
            passwordPolicy.validateForWrite(password, confirmation);
        } catch (PasswordValidationException exception) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_PASSWORD_INVALID,
                    "Administrator password does not meet the security policy.",
                    exception);
        }
    }

    private static RegistrationStatusResult toStatus(RegistrationFlowSnapshot snapshot) {
        RegistrationStatus status = snapshot.completing()
                ? RegistrationStatus.COMPLETING
                : snapshot.readyToComplete()
                        ? RegistrationStatus.READY_TO_COMPLETE
                        : RegistrationStatus.ACTIVE;
        return new RegistrationStatusResult(
                status,
                snapshot.humanVerified(),
                snapshot.emailVerified(),
                snapshot.phoneVerified(),
                snapshot.createdAt(),
                snapshot.expiresAt(),
                snapshot.absoluteExpiresAt(),
                snapshot.email(),
                snapshot.phone());
    }

    private static String countryIso2(String phoneE164) {
        try {
            PhoneNumberUtil util = PhoneNumberUtil.getInstance();
            var parsed = util.parse(phoneE164, "ZZ");
            String region = util.getRegionCodeForNumber(parsed);
            if (region == null || !region.matches("^[A-Z]{2}$")) {
                throw new IllegalArgumentException("Phone region cannot be derived.");
            }
            return region;
        } catch (NumberParseException exception) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_CONFIG_INVALID,
                    "Administrator phone region is invalid.",
                    exception);
        }
    }

    private static AdminException translate(RegistrationException exception) {
        AdminErrorCode code = switch (exception.code()) {
            case INVALID_INPUT -> AdminErrorCode.ADMIN_IDENTITY_INVALID;
            case REGISTRATION_FLOW_EXPIRED, REGISTRATION_FLOW_NOT_FOUND ->
                    AdminErrorCode.ADMIN_FLOW_EXPIRED;
            case REGISTRATION_FLOW_FORBIDDEN, REGISTRATION_ALREADY_COMPLETING ->
                    AdminErrorCode.ADMIN_FLOW_INVALID;
            case VERIFICATION_COOLDOWN, VERIFICATION_SEND_LIMIT ->
                    AdminErrorCode.ADMIN_RATE_LIMITED;
            case VERIFICATION_CHANNEL_UNSUPPORTED ->
                    AdminErrorCode.ADMIN_PHONE_CHANNEL_INVALID;
            case VERIFICATION_CODE_INVALID, VERIFICATION_CODE_EXPIRED,
                    VERIFICATION_CODE_ATTEMPTS_EXHAUSTED,
                    EMAIL_VERIFICATION_REQUIRED, PHONE_VERIFICATION_REQUIRED ->
                    AdminErrorCode.ADMIN_VERIFICATION_INVALID;
            case TURNSTILE_REJECTED, HUMAN_VERIFICATION_REQUIRED ->
                    AdminErrorCode.HCAPTCHA_REJECTED;
            case PASSWORD_MISMATCH, PASSWORD_STRENGTH_INSUFFICIENT, INVALID_PASSWORD ->
                    AdminErrorCode.ADMIN_PASSWORD_INVALID;
            default -> AdminErrorCode.ADMIN_INFRASTRUCTURE_UNAVAILABLE;
        };
        boolean clearFlow = code == AdminErrorCode.ADMIN_FLOW_EXPIRED
                || code == AdminErrorCode.ADMIN_FLOW_INVALID;
        return new AdminException(
                code,
                "Administrator registration request was rejected.",
                exception,
                clearFlow,
                false);
    }

    /**
     * 在供应商校验成功后原子消费当前 Challenge，并把 Redis 状态机失败与供应商拒绝明确分开记录。
     *
     * <p>该边界只输出稳定枚举和当前 traceId，不读取受保护访问对象中的摘要、Flow Token、设备标识或 IP。</p>
     */
    private RegistrationStatusResult finalizeHumanVerification(
            ProtectedRegistrationAccess protectedAccess,
            String currentTraceId) {
        try {
            RegistrationStatusResult result = toStatus(
                    flowStore.markHumanVerified(protectedAccess, clock.instant()));
            LOGGER.info(
                    "event=admin_hcaptcha_flow_finalized traceId={} outcome=succeeded",
                    currentTraceId);
            return result;
        } catch (RegistrationException exception) {
            String safeReason =
                    exception.code() == RegistrationErrorCode.TURNSTILE_REJECTED
                            ? "challenge_already_consumed_or_invalid"
                            : "registration_flow_finalize_failed";
            LOGGER.warn(
                    "event=admin_hcaptcha_flow_finalize_rejected traceId={} "
                            + "failureStage=redis_finalize safeReason={} registrationCode={}",
                    currentTraceId,
                    safeReason,
                    exception.code().name());
            throw translate(exception);
        }
    }

    private static boolean isCode(String value) {
        return value != null && value.matches("^[0-9]{6}$");
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value != null && SAFE_TRACE_ID.matcher(value).matches()
                ? value
                : "absent";
    }
}
