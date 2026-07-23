package com.example.temperate.service.registration.service.lifecycle.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.profile.UserProfileMapper;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.model.user.entity.UserProfile;
import com.example.temperate.service.auth.password.policy.PasswordStrengthPolicy;
import com.example.temperate.service.auth.password.policy.PasswordValidationException;
import com.example.temperate.service.registration.component.executor.RegistrationAfterCommitExecutor;
import com.example.temperate.service.registration.component.id.RegistrationIdGenerator;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import com.example.temperate.service.registration.component.token.RegistrationTokenGenerator;
import com.example.temperate.service.registration.dto.command.RegistrationCompleteCommand;
import com.example.temperate.service.registration.dto.command.RegistrationSendCodeCommand;
import com.example.temperate.service.registration.dto.command.RegistrationStartCommand;
import com.example.temperate.service.registration.dto.command.RegistrationTurnstileCommand;
import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodeCommand;
import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodesCommand;
import com.example.temperate.service.registration.dto.query.RegistrationStatusQuery;
import com.example.temperate.service.registration.dto.result.RegistrationCompleteResult;
import com.example.temperate.service.registration.dto.result.RegistrationStartResult;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.dto.result.VerificationDispatchResult;
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
import com.example.temperate.service.registration.service.turnstile.TurnstileVerificationService;
import com.example.temperate.service.registration.verification.delivery.coordinator.VerificationDeliveryCoordinator;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.operation.VerificationDeliveryOperationIdGenerator;
import com.example.temperate.service.registration.verification.generator.VerificationCodeGenerator;
import com.example.temperate.service.registration.verification.service.resolver.VerificationDeliveryMethodPolicy;
import com.example.temperate.service.registration.verification.service.registry.SixDigitVerificationCodeServiceRegistry;
import com.example.temperate.service.registration.verification.service.resolver.VerificationProviderResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册生命周期的业务编排实现。
 *
 * <p>用途：串联输入规范化、风险与人机校验、双通道验证码、Redis 注册状态机和 PostgreSQL 用户开户。</p>
 *
 * <p>事务与并发原理：完成注册前先由 Redis 原子领取流程完成权，防止多实例重复开户；随后在本地数据库事务中
 * 写入身份、资料状态和会员额度。事务提交后删除流程，事务回滚时释放领取权，以避免未提交数据对应的流程被错误消费。</p>
 */
@Service
public class RegistrationServiceImpl implements RegistrationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationServiceImpl.class);
    private static final Duration REQUIRED_FLOW_TTL = Duration.ofMinutes(10);
    private static final Duration REQUIRED_FLOW_ABSOLUTE_TTL = Duration.ofMinutes(30);
    private static final Map<VerificationChannel, Function<RegistrationFlowSnapshot, String>>
            DESTINATION_RESOLVERS = destinationResolvers();

    private final UserLoginIdentityMapper identityMapper;
    private final UserProfileMapper profileMapper;
    private final UserMembershipQuotaMapper membershipQuotaMapper;
    private final RegistrationFlowStore flowStore;
    private final RegistrationInputNormalizer inputNormalizer;
    private final PasswordStrengthPolicy passwordPolicy;
    private final RegistrationTokenProtector tokenProtector;
    private final RegistrationTokenGenerator tokenGenerator;
    private final VerificationCodeGenerator codeGenerator;
    private final VerificationDeliveryOperationIdGenerator operationIdGenerator;
    private final VerificationDeliveryCoordinator deliveryCoordinator;
    private final SixDigitVerificationCodeServiceRegistry verificationCodeServiceRegistry;
    private final VerificationProviderResolver verificationProviderResolver;
    private final TurnstileVerificationService turnstileVerificationService;
    private final PasswordEncoder passwordEncoder;
    private final RegistrationIdGenerator idGenerator;
    private final PublicIdCodec publicIdCodec;
    private final RegistrationAfterCommitExecutor afterCommitExecutor;
    private final Clock clock;
    private final Duration flowTtl;

    public RegistrationServiceImpl(
            UserLoginIdentityMapper identityMapper,
            UserProfileMapper profileMapper,
            UserMembershipQuotaMapper membershipQuotaMapper,
            RegistrationFlowStore flowStore,
            RegistrationInputNormalizer inputNormalizer,
            PasswordStrengthPolicy passwordPolicy,
            RegistrationTokenProtector tokenProtector,
            RegistrationTokenGenerator tokenGenerator,
            VerificationCodeGenerator codeGenerator,
            VerificationDeliveryOperationIdGenerator operationIdGenerator,
            VerificationDeliveryCoordinator deliveryCoordinator,
            SixDigitVerificationCodeServiceRegistry verificationCodeServiceRegistry,
            VerificationProviderResolver verificationProviderResolver,
            TurnstileVerificationService turnstileVerificationService,
            PasswordEncoder passwordEncoder,
            RegistrationIdGenerator idGenerator,
            PublicIdCodec publicIdCodec,
            RegistrationAfterCommitExecutor afterCommitExecutor,
            Clock clock,
            @Value("${app.registration.flow-ttl:600s}") Duration flowTtl) {
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.profileMapper = Objects.requireNonNull(profileMapper);
        this.membershipQuotaMapper = Objects.requireNonNull(membershipQuotaMapper);
        this.flowStore = Objects.requireNonNull(flowStore);
        this.inputNormalizer = Objects.requireNonNull(inputNormalizer);
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy);
        this.tokenProtector = Objects.requireNonNull(tokenProtector);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.codeGenerator = Objects.requireNonNull(codeGenerator);
        this.operationIdGenerator = Objects.requireNonNull(operationIdGenerator);
        this.deliveryCoordinator = Objects.requireNonNull(deliveryCoordinator);
        this.verificationCodeServiceRegistry =
                Objects.requireNonNull(verificationCodeServiceRegistry);
        this.verificationProviderResolver =
                Objects.requireNonNull(verificationProviderResolver);
        this.turnstileVerificationService = Objects.requireNonNull(turnstileVerificationService);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.idGenerator = Objects.requireNonNull(idGenerator);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.afterCommitExecutor = Objects.requireNonNull(afterCommitExecutor);
        this.clock = Objects.requireNonNull(clock);
        if (!REQUIRED_FLOW_TTL.equals(flowTtl)) {
            throw new IllegalArgumentException("Registration flow TTL must be exactly 600 seconds.");
        }
        this.flowTtl = flowTtl;
    }

    @Override
    public RegistrationStartResult start(RegistrationStartCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String email = inputNormalizer.normalizeEmail(command.email());
        String phone = inputNormalizer.normalizePhone(
                command.countryIso2(), command.nationalPhoneNumber());
        RegistrationActor actor = tokenProtector.protectActor(
                command.deviceInstallationId(), command.canonicalIp());
        if (flowStore.isBlocked(actor)) {
            throw unavailable();
        }

        List<UserLoginIdentity> conflicts = identityMapper.findConflicts(email, phone);
        if (!conflicts.isEmpty()) {
            // 对冲突账户始终返回统一不可用结果，避免注册接口成为账号枚举渠道。
            boolean phoneConflict = conflicts.stream()
                    .anyMatch(identity -> phone.equals(identity.getPhone()));
            boolean emailConflict = conflicts.stream()
                    .anyMatch(identity -> email.equalsIgnoreCase(identity.getEmail()));
            flowStore.recordConflict(actor, phoneConflict, emailConflict, clock.instant());
            throw unavailable();
        }

        String registerToken = tokenGenerator.newRegisterToken();
        String flowCsrf = tokenGenerator.newFlowCsrf();
        String challengeHandle = tokenGenerator.newChallengeHandle();
        RegistrationAccess access = new RegistrationAccess(
                registerToken,
                flowCsrf,
                challengeHandle,
                command.deviceInstallationId(),
                command.canonicalIp());
        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plus(flowTtl);
        flowStore.create(new RegistrationFlow(
                RegistrationFlow.CURRENT_SCHEMA_VERSION,
                email,
                phone,
                tokenProtector.protect(access),
                createdAt,
                expiresAt,
                createdAt.plus(REQUIRED_FLOW_ABSOLUTE_TTL)));
        return new RegistrationStartResult(
                registerToken, flowCsrf, challengeHandle, expiresAt);
    }

    @Override
    public RegistrationStatusResult status(RegistrationStatusQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return toStatus(flowStore.getRequired(
                tokenProtector.protect(query.access()), clock.instant()));
    }

    @Override
    public RegistrationStatusResult verifyTurnstile(RegistrationTurnstileCommand command) {
        long startedNanos = System.nanoTime();
        Objects.requireNonNull(command, "command must not be null");
        // 必须先确认当前浏览器流程仍有效，再调用 Cloudflare，最后原子消费 challenge 并落入已验证状态；
        // 调换顺序会造成过期流程消耗一次性 Token，或供应商已成功但本地状态无法确认的不可恢复窗口。
        long stageStartedNanos = startedNanos;
        ProtectedRegistrationAccess access;
        HmacIdentifier turnstileTokenId = null;
        HmacIdentifier clientIpId = null;
        try {
            access = tokenProtector.protect(command.access());
        } catch (RegistrationException exception) {
            throw recordTurnstileFailure(
                    "protect_access",
                    null,
                    null,
                    null,
                    diagnosed(exception, RegistrationDiagnosticCode.FLOW_ACCESS_REJECTED),
                    startedNanos,
                    stageStartedNanos);
        }
        long accessProtectedNanos = System.nanoTime();
        stageStartedNanos = accessProtectedNanos;
        String canonicalIp = command.access().canonicalIp();
        if (canonicalIp != null && !canonicalIp.isBlank()) {
            clientIpId = tokenProtector.clientIpDiagnosticDigest(canonicalIp);
        }
        try {
            turnstileTokenId = tokenProtector.turnstileResponseDigest(command.responseToken());
        } catch (RegistrationException exception) {
            throw recordTurnstileFailure(
                    "protect_token",
                    access,
                    null,
                    clientIpId,
                    diagnosed(exception, RegistrationDiagnosticCode.INPUT_INVALID),
                    startedNanos,
                    stageStartedNanos);
        }
        long tokenProtectedNanos = System.nanoTime();
        stageStartedNanos = tokenProtectedNanos;
        try {
            flowStore.getRequired(access, clock.instant());
        } catch (RegistrationException exception) {
            throw recordTurnstileFailure(
                    "load_flow",
                    access,
                    turnstileTokenId,
                    clientIpId,
                    diagnosed(exception, flowLookupDiagnostic(exception.code())),
                    startedNanos,
                    stageStartedNanos);
        }
        long flowLoadedNanos = System.nanoTime();
        stageStartedNanos = flowLoadedNanos;
        try {
            turnstileVerificationService.verify(
                    command.responseToken(),
                    command.access().canonicalIp(),
                    command.access().challengeHandle());
        } catch (RegistrationException exception) {
            throw recordTurnstileFailure(
                    "siteverify",
                    access,
                    turnstileTokenId,
                    clientIpId,
                    exception,
                    startedNanos,
                    stageStartedNanos);
        }
        long siteverifyCompletedNanos = System.nanoTime();
        stageStartedNanos = siteverifyCompletedNanos;
        try {
            RegistrationStatusResult result = toStatus(
                    flowStore.markHumanVerified(access, clock.instant()));
            long finalizedNanos = System.nanoTime();
            LOGGER.info(
                    "registration_turnstile_completed traceId={} tokenId={} clientIpId={} "
                            + "flowId={} challengeId={} accessProtectMs={} tokenProtectMs={} "
                            + "flowLookupMs={} siteverifyMs={} redisFinalizeMs={} elapsedMs={} "
                            + "humanVerified={}",
                    traceId(),
                    fingerprint(turnstileTokenId),
                    clientIpId == null ? "absent" : fingerprint(clientIpId),
                    fingerprint(access.flowId()),
                    fingerprint(access.challengeId()),
                    elapsedMillis(startedNanos, accessProtectedNanos),
                    elapsedMillis(accessProtectedNanos, tokenProtectedNanos),
                    elapsedMillis(tokenProtectedNanos, flowLoadedNanos),
                    elapsedMillis(flowLoadedNanos, siteverifyCompletedNanos),
                    elapsedMillis(siteverifyCompletedNanos, finalizedNanos),
                    elapsedMillis(startedNanos),
                    result.humanVerified());
            return result;
        } catch (RegistrationException exception) {
            RegistrationDiagnosticCode diagnosticCode =
                    exception.code() == RegistrationErrorCode.TURNSTILE_REJECTED
                            ? RegistrationDiagnosticCode.CHALLENGE_ALREADY_CONSUMED
                            : flowFinalizeDiagnostic(exception.code());
            throw recordTurnstileFailure(
                    "finalize_redis",
                    access,
                    turnstileTokenId,
                    clientIpId,
                    diagnosed(exception, diagnosticCode),
                    startedNanos,
                    stageStartedNanos);
        }
    }

    @Override
    public VerificationDispatchResult sendCode(RegistrationSendCodeCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        VerificationChannel channel = Objects.requireNonNull(command.channel(), "channel");
        VerificationDeliveryMethod deliveryMethod =
                Objects.requireNonNull(command.deliveryMethod(), "deliveryMethod");
        ProtectedRegistrationAccess access = tokenProtector.protect(command.access());
        RegistrationFlowSnapshot snapshot = flowStore.getRequired(access, clock.instant());
        if (!snapshot.humanVerified()) {
            throw new RegistrationException(
                    RegistrationErrorCode.HUMAN_VERIFICATION_REQUIRED,
                    "Human verification is required.");
        }
        String destination = DESTINATION_RESOLVERS.get(channel).apply(snapshot);
        VerificationDeliveryMethodPolicy.requireSupported(
                channel, deliveryMethod, destination);
        String code = codeGenerator.generate();
        HmacIdentifier digest = tokenProtector.codeDigest(
                command.access().registerToken(), channel, code);
        HmacIdentifier sendOperationId = tokenProtector.deliveryOperationDigest(
                operationIdGenerator.generateRawOperationId());
        Instant acceptedAt = clock.instant();
        // 先在状态机中原子登记待投递操作；投递协调器随后按操作标识确认成功或执行补偿。
        flowStore.issueCode(access, channel, digest, sendOperationId, acceptedAt);
        try {
            deliveryCoordinator.deliver(
                    access,
                    channel,
                    deliveryMethod,
                    sendOperationId,
                    new VerificationDeliveryRequest(destination, code),
                    acceptedAt.plus(Duration.ofMinutes(5)));
        } catch (RuntimeException exception) {
            // 发布确认失败代表队列没有可靠接管本次验证码；这里只补偿当前 operation，避免删除后续并发新发码。
            flowStore.compensateCodeDeliveryFailure(access, channel, sendOperationId);
            if (exception instanceof RegistrationException registrationException) {
                throw registrationException;
            }
            throw new RegistrationException(
                    RegistrationErrorCode.DELIVERY_UNAVAILABLE,
                    "Verification delivery is temporarily unavailable.",
                    exception);
        }
        return new VerificationDispatchResult(channel, acceptedAt);
    }

    @Override
    public RegistrationStatusResult verifyCode(RegistrationVerifyCodeCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        VerificationChannel channel =
                Objects.requireNonNull(command.channel(), "channel must not be null");
        ProtectedRegistrationAccess access = tokenProtector.protect(command.access());
        RegistrationFlowSnapshot snapshot = flowStore.getRequired(access, clock.instant());
        String destination = DESTINATION_RESOLVERS.get(channel).apply(snapshot);
        VerificationProvider provider =
                verificationProviderResolver.resolve(channel, destination);
        // 供应商实现只决定发送适配；所有实现的 verifyCode 最终都委托同一个 Redis 原子校验器。
        return verificationCodeServiceRegistry
                .getRequired(provider)
                .verifyCode(command);
    }

    @Override
    public RegistrationStatusResult verifyCodes(RegistrationVerifyCodesCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (!isSixDigitCode(command.emailCode()) || !isSixDigitCode(command.smsCode())) {
            throw new RegistrationException(
                    RegistrationErrorCode.VERIFICATION_CODE_INVALID,
                    "Both verification codes are required.");
        }
        ProtectedRegistrationAccess access = tokenProtector.protect(command.access());
        flowStore.getRequired(access, clock.instant());
        HmacIdentifier emailDigest = tokenProtector.codeDigest(
                command.access().registerToken(), VerificationChannel.EMAIL, command.emailCode());
        HmacIdentifier phoneDigest = tokenProtector.codeDigest(
                command.access().registerToken(), VerificationChannel.SMS, command.smsCode());
        return toStatus(flowStore.verifyCodes(
                access, emailDigest, phoneDigest, clock.instant()));
    }

    @Override
    @Transactional
    public RegistrationCompleteResult complete(RegistrationCompleteCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validatePassword(command);
        ProtectedRegistrationAccess access = tokenProtector.protect(command.access());
        flowStore.getRequired(access, clock.instant());
        HmacIdentifier claimId = tokenProtector.completionClaimDigest(
                tokenGenerator.newCompletionClaim());
        // Redis Lua 原子领取完成权，阻止同一流程在多线程或多实例下同时执行数据库开户事务。
        RegistrationCompletionClaim claim =
                flowStore.claimCompletion(access, claimId, clock.instant());

        try {
            // 在写库前登记事务完成回调：提交后删除流程，回滚后释放领取权以允许安全重试。
            afterCommitExecutor.execute(
                    () -> flowStore.delete(access),
                    () -> flowStore.releaseCompletionClaim(access, claimId));
        } catch (RuntimeException registrationFailure) {
            try {
                flowStore.releaseCompletionClaim(access, claimId);
            } catch (RuntimeException releaseFailure) {
                registrationFailure.addSuppressed(releaseFailure);
            }
            throw registrationFailure;
        }

        try {
            RegistrationFlowSnapshot snapshot = claim.snapshot();
            // 起始阶段的查重不能替代事务内复核；并发注册可能在两次检查之间占用了邮箱或手机号。
            if (!identityMapper.findConflicts(snapshot.email(), snapshot.phone()).isEmpty()) {
                throw unavailable();
            }

            long internalId = idGenerator.nextPositiveId();
            if (internalId <= 0) {
                throw new IllegalStateException("Registration ID must be positive.");
            }
            String publicId = publicIdCodec.encode(internalId);

            UserLoginIdentity identity = new UserLoginIdentity();
            identity.setId(internalId);
            identity.setEmail(snapshot.email());
            identity.setPhone(snapshot.phone());
            identity.setPasswordHash(passwordEncoder.encode(command.password()));
            if (identityMapper.insert(identity) != 1) {
                throw persistenceFailure();
            }

            UserProfile profile = new UserProfile();
            profile.setLoginIdentityId(internalId);
            profile.setDisplayName("用户" + publicId.substring(publicId.length() - 7));
            profile.setAccountStatus(0);
            if (profileMapper.insert(profile) != 1) {
                throw persistenceFailure();
            }

            // 会员等级和额度由 005 表的数据库默认值统一初始化，避免注册代码与持久化默认规则发生漂移。
            UserMembershipQuota membershipQuota = new UserMembershipQuota();
            membershipQuota.setLoginIdentityId(internalId);
            if (membershipQuotaMapper.insert(membershipQuota) != 1) {
                throw persistenceFailure();
            }

            return new RegistrationCompleteResult(
                    publicId, command.access().registerToken());
        } catch (DataIntegrityViolationException exception) {
            // 数据库约束是并发写入时的最终一致性边界；失败时由事务回滚回调释放完成权，禁止在提交前删除注册流程。
            throw new RegistrationException(
                    RegistrationErrorCode.AUTH_REGISTER_UNAVAILABLE,
                    "Registration is unavailable.",
                    exception);
        }
    }

    private void validatePassword(RegistrationCompleteCommand command) {
        try {
            passwordPolicy.validateForWrite(command.password(), command.passwordConfirmation());
        } catch (PasswordValidationException exception) {
            RegistrationErrorCode code = exception.reason()
                    == PasswordValidationException.Reason.CONFIRMATION_MISMATCH
                    ? RegistrationErrorCode.PASSWORD_MISMATCH
                    : RegistrationErrorCode.PASSWORD_STRENGTH_INSUFFICIENT;
            throw new RegistrationException(code, exception.getMessage(), exception);
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

    private static boolean isSixDigitCode(String code) {
        return code != null && code.matches("^[0-9]{6}$");
    }

    private static Map<VerificationChannel, Function<RegistrationFlowSnapshot, String>>
            destinationResolvers() {
        EnumMap<VerificationChannel, Function<RegistrationFlowSnapshot, String>> resolvers =
                new EnumMap<>(VerificationChannel.class);
        resolvers.put(VerificationChannel.EMAIL, RegistrationFlowSnapshot::email);
        resolvers.put(VerificationChannel.SMS, RegistrationFlowSnapshot::phone);
        return Map.copyOf(resolvers);
    }

    private static RegistrationException unavailable() {
        return new RegistrationException(
                RegistrationErrorCode.AUTH_REGISTER_UNAVAILABLE,
                "Registration is unavailable.");
    }

    private static RegistrationException persistenceFailure() {
        return new RegistrationException(
                RegistrationErrorCode.REGISTRATION_PERSISTENCE_FAILED,
                "Registration persistence failed.");
    }

    private static RegistrationException recordTurnstileFailure(
            String stage,
            ProtectedRegistrationAccess access,
            HmacIdentifier turnstileTokenId,
            HmacIdentifier clientIpId,
            RegistrationException exception,
            long startedNanos,
            long stageStartedNanos) {
        LOGGER.warn(
                "registration_turnstile_stage_failed traceId={} stage={} diagnosticCode={} "
                        + "businessCode={} tokenId={} clientIpId={} flowId={} challengeId={} "
                        + "stageElapsedMs={} elapsedMs={}",
                traceId(),
                stage,
                exception.diagnosticCode().map(Enum::name).orElse("absent"),
                exception.code(),
                turnstileTokenId == null ? "absent" : fingerprint(turnstileTokenId),
                clientIpId == null ? "absent" : fingerprint(clientIpId),
                access == null ? "absent" : fingerprint(access.flowId()),
                access == null ? "absent" : fingerprint(access.challengeId()),
                elapsedMillis(stageStartedNanos),
                elapsedMillis(startedNanos));
        return exception;
    }

    private static RegistrationException diagnosed(
            RegistrationException exception, RegistrationDiagnosticCode diagnosticCode) {
        if (exception.diagnosticCode().isPresent()) {
            return exception;
        }
        return new RegistrationException(
                exception.code(),
                exception.getMessage(),
                diagnosticCode,
                exception);
    }

    private static RegistrationDiagnosticCode flowLookupDiagnostic(
            RegistrationErrorCode errorCode) {
        return switch (errorCode) {
            case REGISTRATION_FLOW_NOT_FOUND -> RegistrationDiagnosticCode.FLOW_NOT_FOUND;
            case REGISTRATION_FLOW_EXPIRED -> RegistrationDiagnosticCode.FLOW_EXPIRED;
            case REGISTRATION_FLOW_FORBIDDEN -> RegistrationDiagnosticCode.FLOW_ACCESS_REJECTED;
            default -> RegistrationDiagnosticCode.REDIS_FLOW_LOOKUP_FAILED;
        };
    }

    private static RegistrationDiagnosticCode flowFinalizeDiagnostic(
            RegistrationErrorCode errorCode) {
        return switch (errorCode) {
            case REGISTRATION_FLOW_NOT_FOUND -> RegistrationDiagnosticCode.FLOW_NOT_FOUND;
            case REGISTRATION_FLOW_EXPIRED -> RegistrationDiagnosticCode.FLOW_EXPIRED;
            case REGISTRATION_FLOW_FORBIDDEN -> RegistrationDiagnosticCode.FLOW_ACCESS_REJECTED;
            default -> RegistrationDiagnosticCode.REDIS_FINALIZE_FAILED;
        };
    }

    private static String fingerprint(HmacIdentifier identifier) {
        String value = identifier == null ? "" : identifier.value();
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank() ? "absent" : value;
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static long elapsedMillis(long startedNanos, long completedNanos) {
        return Math.max(0L, (completedNanos - startedNanos) / 1_000_000L);
    }
}
