package com.example.temperate.service.admin.login.impl;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.config.AdminConfiguration;
import com.example.temperate.service.admin.config.AdminConfigurationService;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.service.admin.login.AdminLoginAccess;
import com.example.temperate.service.admin.login.AdminLoginCompleteCommand;
import com.example.temperate.service.admin.login.AdminLoginFlow;
import com.example.temperate.service.admin.login.AdminLoginFlowStore;
import com.example.temperate.service.admin.login.AdminLoginService;
import com.example.temperate.service.admin.login.AdminLoginStartResult;
import com.example.temperate.service.admin.login.ProtectedAdminLoginAccess;
import com.example.temperate.service.admin.security.AdminSecretProtector;
import com.example.temperate.service.admin.session.AdminSessionIssue;
import com.example.temperate.service.admin.session.AdminSessionService;
import com.example.temperate.service.auth.login.limit.dto.LoginAttempt;
import com.example.temperate.service.auth.login.limit.enums.LoginLimitDecision;
import com.example.temperate.service.auth.login.limit.service.LoginRateLimitService;
import com.example.temperate.service.humanverification.HumanVerificationCommand;
import com.example.temperate.service.humanverification.HumanVerificationService;
import com.example.temperate.service.humanverification.HumanVerificationServiceRegistry;
import com.example.temperate.service.humanverification.HumanVerificationType;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import com.example.temperate.service.registration.component.token.RegistrationTokenGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 编排管理员登录 Flow、hCaptcha、三项凭证、登录风控和单一不透明会话签发。
 *
 * <p>hCaptcha 成功后才读取并比较凭证；邮箱、手机号或密码任一错误都使用同一个外部错误，成功签发会话后才
 * 原子消费 Flow，消费失败会立即撤销刚创建的会话以避免孤儿 Token。</p>
 */
@Service
public final class AdminLoginServiceImpl implements AdminLoginService {

    private static final Duration REQUIRED_FLOW_TTL = Duration.ofMinutes(10);

    private final AdminConfigurationService configurationService;
    private final AdminLoginFlowStore flowStore;
    private final AdminSecretProtector protector;
    private final HumanVerificationServiceRegistry humanVerificationServices;
    private final RegistrationInputNormalizer inputNormalizer;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimitService rateLimitService;
    private final AdminSessionService sessionService;
    private final RegistrationTokenGenerator tokenGenerator;
    private final Clock clock;
    private final Duration flowTtl;

    @Autowired
    public AdminLoginServiceImpl(
            AdminConfigurationService configurationService,
            AdminLoginFlowStore flowStore,
            AdminSecretProtector protector,
            HumanVerificationServiceRegistry humanVerificationServices,
            RegistrationInputNormalizer inputNormalizer,
            PasswordEncoder passwordEncoder,
            LoginRateLimitService rateLimitService,
            AdminSessionService sessionService,
            RegistrationTokenGenerator tokenGenerator,
            Clock clock,
            AdminProperties properties) {
        this(
                configurationService,
                flowStore,
                protector,
                humanVerificationServices,
                inputNormalizer,
                passwordEncoder,
                rateLimitService,
                sessionService,
                tokenGenerator,
                clock,
                properties.loginFlowTtl());
    }

    AdminLoginServiceImpl(
            AdminConfigurationService configurationService,
            AdminLoginFlowStore flowStore,
            AdminSecretProtector protector,
            HumanVerificationServiceRegistry humanVerificationServices,
            RegistrationInputNormalizer inputNormalizer,
            PasswordEncoder passwordEncoder,
            LoginRateLimitService rateLimitService,
            AdminSessionService sessionService,
            RegistrationTokenGenerator tokenGenerator,
            Clock clock,
            Duration flowTtl) {
        this.configurationService = Objects.requireNonNull(configurationService);
        this.flowStore = Objects.requireNonNull(flowStore);
        this.protector = Objects.requireNonNull(protector);
        this.humanVerificationServices = Objects.requireNonNull(humanVerificationServices);
        this.inputNormalizer = Objects.requireNonNull(inputNormalizer);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.rateLimitService = Objects.requireNonNull(rateLimitService);
        this.sessionService = Objects.requireNonNull(sessionService);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.clock = Objects.requireNonNull(clock);
        this.flowTtl = Objects.requireNonNull(flowTtl);
        if (!REQUIRED_FLOW_TTL.equals(flowTtl)) {
            throw new IllegalArgumentException(
                    "Admin login flow TTL must be exactly ten minutes.");
        }
    }

    @Override
    public AdminLoginStartResult start(
            String deviceInstallationId,
            String canonicalIp) {
        configurationService.requireActive();
        String flowToken = tokenGenerator.newRegisterToken();
        String flowCsrf = tokenGenerator.newFlowCsrf();
        String challenge = tokenGenerator.newChallengeHandle();
        AdminLoginAccess access = new AdminLoginAccess(
                flowToken, flowCsrf, challenge, deviceInstallationId, canonicalIp);
        Instant now = clock.instant();
        flowStore.create(new AdminLoginFlow(
                protector.protectLogin(access), now, now.plus(flowTtl)));
        return new AdminLoginStartResult(
                flowToken, flowCsrf, challenge, now.plus(flowTtl));
    }

    @Override
    public Mono<AdminSessionIssue> complete(AdminLoginCompleteCommand command) {
        return Mono.defer(() -> {
            Objects.requireNonNull(command, "command must not be null");
            AdminLoginAccess access = Objects.requireNonNull(command.access());
            ProtectedAdminLoginAccess protectedAccess = protector.protectLogin(access);
            // 在任何 boundedElastic 切换前捕获关联 ID，避免异步线程没有 Servlet MDC。
            String subscriptionTraceId = traceId();

            Mono<LoginPrerequisites> prerequisites = Mono.fromCallable(() -> {
                        AdminConfiguration configuration = configurationService.requireActive();
                        AdminLoginFlow flow =
                                flowStore.getRequired(protectedAccess, clock.instant());
                        return new LoginPrerequisites(configuration, flow);
                    })
                    .subscribeOn(Schedulers.boundedElastic());

            HumanVerificationService hcaptcha =
                    humanVerificationServices.getRequired(HumanVerificationType.HCAPTCHA);
            return prerequisites.flatMap(required -> hcaptcha.verify(
                            HumanVerificationCommand.hcaptcha(
                                    command.hcaptchaToken(),
                                    access.canonicalIp(),
                                    access.challengeId()))
                    .contextWrite(context -> context.put(
                            HumanVerificationService.TRACE_ID_CONTEXT_KEY,
                            subscriptionTraceId))
                    .then(Mono.fromCallable(() -> authenticateAndIssue(
                                    command,
                                    protectedAccess,
                                    required.configuration()))
                            .subscribeOn(Schedulers.boundedElastic())));
        });
    }

    private AdminSessionIssue authenticateAndIssue(
            AdminLoginCompleteCommand command,
            ProtectedAdminLoginAccess protectedAccess,
            AdminConfiguration configuration) {
        String normalizedEmail;
        String normalizedPhone;
        String country;
        try {
            normalizedEmail = inputNormalizer.normalizeEmail(command.email());
            country = command.countryIso2() == null
                    ? ""
                    : command.countryIso2().trim().toUpperCase(Locale.ROOT);
            normalizedPhone = inputNormalizer.normalizePhone(country, command.phoneNumber());
        } catch (RuntimeException exception) {
            throw credentialsInvalid(exception);
        }

        LoginAttempt attempt = new LoginAttempt(
                normalizedEmail,
                command.access().deviceInstallationId());
        if (rateLimitService.check(attempt) == LoginLimitDecision.BLOCKED) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_RATE_LIMITED,
                    "Administrator login is temporarily limited.");
        }

        // 三项身份和密码均执行比较，外部只暴露统一失败语义，避免指出具体错误字段。
        boolean emailMatches = constantTimeEquals(configuration.email(), normalizedEmail);
        boolean countryMatches = constantTimeEquals(configuration.countryIso2(), country);
        boolean phoneMatches = constantTimeEquals(configuration.phoneE164(), normalizedPhone);
        boolean passwordMatches = passwordEncoder.matches(
                command.password() == null ? "" : command.password(),
                configuration.passwordHash());
        if (!(emailMatches && countryMatches && phoneMatches && passwordMatches)) {
            LoginLimitDecision decision = rateLimitService.recordFailure(attempt);
            if (decision == LoginLimitDecision.BLOCKED) {
                throw new AdminException(
                        AdminErrorCode.ADMIN_RATE_LIMITED,
                        "Administrator login is temporarily limited.");
            }
            throw credentialsInvalid(null);
        }

        if (passwordEncoder.upgradeEncoding(configuration.passwordHash())) {
            String upgraded = passwordEncoder.encode(command.password());
            configurationService.upgradePasswordHash(
                    configuration.passwordHash(), upgraded);
        }
        rateLimitService.clearSubjectFailures(attempt);
        AdminSessionIssue issue =
                sessionService.issue(command.access().deviceInstallationId());
        try {
            flowStore.consume(protectedAccess);
        } catch (RuntimeException exception) {
            try {
                sessionService.logout(issue.rawToken());
            } catch (RuntimeException revokeFailure) {
                exception.addSuppressed(revokeFailure);
            }
            throw exception;
        }
        return issue;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] left = (expected == null ? "" : expected).getBytes(StandardCharsets.UTF_8);
        byte[] right = (actual == null ? "" : actual).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }

    private static AdminException credentialsInvalid(Throwable cause) {
        return new AdminException(
                AdminErrorCode.ADMIN_CREDENTIALS_INVALID,
                "Administrator credentials are invalid.",
                cause);
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank() ? "absent" : value;
    }

    private record LoginPrerequisites(
            AdminConfiguration configuration,
            AdminLoginFlow flow) {
    }
}
