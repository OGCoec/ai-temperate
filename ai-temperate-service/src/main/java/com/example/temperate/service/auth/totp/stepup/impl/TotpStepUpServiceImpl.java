package com.example.temperate.service.auth.totp.stepup.impl;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.service.auth.login.code.dto.LoginCodeStartResult;
import com.example.temperate.service.auth.login.code.service.LoginCodeFlowService;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.login.limit.dto.LoginAttempt;
import com.example.temperate.service.auth.login.limit.enums.LoginFailureBucket;
import com.example.temperate.service.auth.login.limit.enums.LoginLimitDecision;
import com.example.temperate.service.auth.login.limit.service.LoginRateLimitService;
import com.example.temperate.service.auth.login.strategy.LoginStrategyRequest;
import com.example.temperate.service.auth.login.strategy.LoginStrategyType;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.auth.totp.config.TotpProperties;
import com.example.temperate.service.auth.totp.management.TotpManagementAction;
import com.example.temperate.service.auth.totp.stepup.TotpStepUpService;
import com.example.temperate.service.auth.totp.stepup.dto.TotpStepUpProofResult;
import com.example.temperate.service.auth.totp.stepup.store.TotpStepUpStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 复用现有密码策略和登录验证码流程，为 TOTP 开启、轮换、关闭签发动作绑定的一次性复验凭证。
 *
 * <p>验证码目标只从当前用户数据库身份读取；复验凭证绑定用户、设备和动作，并在管理操作开始时消费，
 * 防止一个验证码结果被替换为另一类敏感操作。</p>
 */
@Service
public final class TotpStepUpServiceImpl implements TotpStepUpService {

    private final UserLoginIdentityMapper identityMapper;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimitService rateLimitService;
    private final LoginCodeFlowService codeFlowService;
    private final TotpStepUpStore stepUpStore;
    private final AuthTokenService tokenService;
    private final TotpProperties properties;
    private final Clock clock;

    public TotpStepUpServiceImpl(
            UserLoginIdentityMapper identityMapper,
            PasswordEncoder passwordEncoder,
            LoginRateLimitService rateLimitService,
            LoginCodeFlowService codeFlowService,
            TotpStepUpStore stepUpStore,
            AuthTokenService tokenService,
            TotpProperties properties,
            Clock clock) {
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.rateLimitService = Objects.requireNonNull(rateLimitService);
        this.codeFlowService = Objects.requireNonNull(codeFlowService);
        this.stepUpStore = Objects.requireNonNull(stepUpStore);
        this.tokenService = Objects.requireNonNull(tokenService);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public TotpStepUpProofResult verifyPassword(
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            String rawPassword) {
        AuthenticationContext context = requireActive(userId);
        String identifier = context.getEmail() != null
                ? context.getEmail()
                : context.getPhone();
        LoginAttempt attempt = new LoginAttempt(identifier, deviceInstallationId);
        if (rateLimitService.check(attempt, LoginFailureBucket.PASSWORD)
                == LoginLimitDecision.BLOCKED) {
            throw blocked();
        }
        boolean matches;
        try {
            matches = rawPassword != null
                    && !rawPassword.isEmpty()
                    && rawPassword.getBytes(StandardCharsets.UTF_8).length <= 72
                    && passwordEncoder.matches(rawPassword, context.getPasswordHash());
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (!matches) {
            if (rateLimitService.recordFailure(attempt, LoginFailureBucket.PASSWORD)
                    == LoginLimitDecision.BLOCKED) {
                throw blocked();
            }
            throw new LoginException(
                    LoginErrorCode.AUTHENTICATION_FAILED,
                    "Security verification failed.");
        }
        rateLimitService.clearSubjectFailures(attempt);
        return createProof(userId, deviceInstallationId, action, null);
    }

    @Override
    public LoginCodeStartResult startCode(
            long userId,
            String deviceInstallationId,
            String clientIp,
            TotpManagementAction action,
            LoginStrategyType type) {
        requireCodeType(type);
        requireActive(userId);
        LoginCodeStartResult flow = codeFlowService.startForVerifiedIdentity(
                userId, type, deviceInstallationId, clientIp);
        Instant now = clock.instant();
        stepUpStore.bindCodeFlow(
                flow.loginFlowToken(),
                userId,
                deviceInstallationId,
                requireAction(action),
                now,
                properties.stepUpTtl());
        return flow;
    }

    @Override
    public TotpStepUpProofResult verifyCode(
            long userId,
            String deviceInstallationId,
            String clientIp,
            TotpManagementAction action,
            LoginStrategyType type,
            String rawFlowToken,
            String challengeHandle,
            String code) {
        requireCodeType(type);
        Instant now = clock.instant();
        stepUpStore.requireCodeFlow(
                rawFlowToken,
                userId,
                deviceInstallationId,
                requireAction(action),
                now);
        AuthenticationContext verified = codeFlowService.verifyPrimaryFactor(
                type,
                new LoginStrategyRequest(
                        null, null, null, null,
                        rawFlowToken, challengeHandle, code,
                        deviceInstallationId, clientIp));
        if (verified == null || verified.getIdentityId() != userId) {
            throw new LoginException(
                    LoginErrorCode.TOTP_STEP_UP_REQUIRED,
                    "Security verification does not belong to the current user.");
        }
        return createProof(userId, deviceInstallationId, action, rawFlowToken);
    }

    @Override
    public void requireProof(
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            String rawStepUpToken) {
        stepUpStore.requireProof(
                rawStepUpToken,
                userId,
                deviceInstallationId,
                requireAction(action),
                clock.instant());
    }

    @Override
    public void recordProofFailure(
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            String rawStepUpToken) {
        stepUpStore.recordProofFailure(
                rawStepUpToken,
                userId,
                deviceInstallationId,
                requireAction(action),
                clock.instant());
    }

    @Override
    public void consumeProof(
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            String rawStepUpToken) {
        stepUpStore.consumeProof(
                rawStepUpToken,
                userId,
                deviceInstallationId,
                requireAction(action),
                clock.instant());
    }

    private TotpStepUpProofResult createProof(
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            String sourceFlowToken) {
        String rawProofToken = tokenService.newFlowToken();
        Instant now = clock.instant();
        if (sourceFlowToken == null) {
            stepUpStore.createProof(
                    rawProofToken,
                    userId,
                    deviceInstallationId,
                    requireAction(action),
                    now,
                    properties.stepUpTtl());
        } else {
            stepUpStore.promoteCodeFlowToProof(
                    sourceFlowToken,
                    rawProofToken,
                    userId,
                    deviceInstallationId,
                    requireAction(action),
                    now,
                    properties.stepUpTtl());
        }
        return new TotpStepUpProofResult(
                rawProofToken, now.plus(properties.stepUpTtl()));
    }

    private AuthenticationContext requireActive(long userId) {
        AuthenticationContext context = identityMapper.findAuthenticationById(userId);
        if (context == null
                || context.getIdentityId() != userId
                || context.getAccountStatus() != AccountStatus.ACTIVE
                || context.getPasswordHash() == null
                || context.getPasswordHash().isBlank()) {
            throw new LoginException(
                    LoginErrorCode.ACCOUNT_UNAVAILABLE,
                    "Account is unavailable.");
        }
        return context;
    }

    private static void requireCodeType(LoginStrategyType type) {
        if (type != LoginStrategyType.EMAIL_CODE
                && type != LoginStrategyType.SMS_CODE) {
            throw new LoginException(
                    LoginErrorCode.INVALID_INPUT,
                    "Security verification channel is invalid.");
        }
    }

    private static TotpManagementAction requireAction(TotpManagementAction action) {
        return Objects.requireNonNull(action, "action must not be null");
    }

    private static LoginException blocked() {
        return new LoginException(
                LoginErrorCode.LOGIN_BLOCKED,
                "Security verification is temporarily blocked.");
    }

    private static LoginException unavailable(Throwable cause) {
        return new LoginException(
                LoginErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                "Security verification is unavailable.",
                cause);
    }
}
