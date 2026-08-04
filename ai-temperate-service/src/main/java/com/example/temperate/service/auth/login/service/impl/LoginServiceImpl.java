package com.example.temperate.service.auth.login.service.impl;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceDecision;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceFilter;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceKind;
import com.example.temperate.service.auth.login.audit.enums.LoginAuditOutcome;
import com.example.temperate.service.auth.login.audit.enums.LoginAuditReason;
import com.example.temperate.service.auth.login.audit.observer.LoginAuditObserver;
import com.example.temperate.service.auth.login.component.normalizer.LoginInputNormalizer;
import com.example.temperate.service.auth.login.completion.LoginCompletionService;
import com.example.temperate.service.auth.login.dto.command.LoginCommand;
import com.example.temperate.service.auth.login.dto.internal.NormalizedLoginInput;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.enums.LoginIdentifierType;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.login.limit.dto.LoginAttempt;
import com.example.temperate.service.auth.login.limit.enums.LoginLimitDecision;
import com.example.temperate.service.auth.login.limit.service.LoginRateLimitService;
import com.example.temperate.service.auth.login.service.LoginService;
import java.util.Objects;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 编排密码登录的输入规范化、风控检查、账号校验、密码升级和会话创建。
 *
 * <p>密码比对对存在与不存在的账号保持相同的计算路径，以减少账号枚举时序差异；密码哈希升级在本地事务中
 * 使用 CAS 写入，避免并发登录回退已升级的哈希。身份 Bloom 只允许在明确未命中时跳过查库，异常时仍
 * 回源 PostgreSQL。</p>
 */
@Service
public final class LoginServiceImpl implements LoginService {

    static final String DUMMY_PASSWORD_HASH =
            "{bcrypt}$2a$10$JieVY2BiTm1zdN1W07/YxurgOHVm0i5fEmyEnFvyKI3m4jJTukJb6";

    private final LoginInputNormalizer inputNormalizer;
    private final UserLoginIdentityMapper identityMapper;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimitService rateLimitService;
    private final LoginAuditObserver auditObserver;
    private final LoginCompletionService completionService;
    private final IdentityPresenceFilter identityPresenceFilter;

    public LoginServiceImpl(
            LoginInputNormalizer inputNormalizer,
            UserLoginIdentityMapper identityMapper,
            PasswordEncoder passwordEncoder,
            LoginRateLimitService rateLimitService,
            LoginAuditObserver auditObserver,
            LoginCompletionService completionService,
            IdentityPresenceFilter identityPresenceFilter) {
        this.inputNormalizer = Objects.requireNonNull(
                inputNormalizer, "inputNormalizer must not be null");
        this.identityMapper = Objects.requireNonNull(
                identityMapper, "identityMapper must not be null");
        this.passwordEncoder = Objects.requireNonNull(
                passwordEncoder, "passwordEncoder must not be null");
        this.rateLimitService = Objects.requireNonNull(
                rateLimitService, "rateLimitService must not be null");
        this.auditObserver = Objects.requireNonNull(
                auditObserver, "auditObserver must not be null");
        this.completionService = Objects.requireNonNull(
                completionService, "completionService must not be null");
        this.identityPresenceFilter = Objects.requireNonNull(
                identityPresenceFilter, "identityPresenceFilter must not be null");
    }

    @Override
    @Transactional
    public LoginResult login(LoginCommand command) {
        NormalizedLoginInput input = inputNormalizer.normalize(command);
        LoginAttempt attempt = new LoginAttempt(
                input.getIdentifier(),
                input.getDeviceInstallationId());

        if (checkLimit(attempt) == LoginLimitDecision.BLOCKED) {
            auditObserver.observe(LoginAuditOutcome.REJECTED, LoginAuditReason.BLOCKED);
            throw blocked();
        }

        AuthenticationContext context = findAuthenticationContext(input);
        // 账号不存在也执行一次同成本密码比对，避免响应时序泄露标识是否已注册。
        String encodedPassword = context == null || context.getPasswordHash() == null
                || context.getPasswordHash().isBlank()
                ? DUMMY_PASSWORD_HASH
                : context.getPasswordHash();
        boolean passwordMatches = matches(input.getRawPassword(), encodedPassword);
        if (context == null || !passwordMatches) {
            reject(attempt, LoginErrorCode.AUTHENTICATION_FAILED,
                    LoginAuditReason.INVALID_CREDENTIALS);
        }
        if (!isAvailable(context)) {
            reject(attempt, LoginErrorCode.ACCOUNT_UNAVAILABLE,
                    LoginAuditReason.ACCOUNT_STATUS);
        }
        upgradePasswordHashIfRequired(context, input.getRawPassword());
        LoginResult result = completionService.complete(
                context, input.getDeviceInstallationId());
        clearSubjectFailures(attempt);
        auditObserver.observe(
                LoginAuditOutcome.SUCCESS,
                result.isAuthenticated()
                        ? LoginAuditReason.AUTHENTICATED
                        : LoginAuditReason.PRIMARY_FACTOR_VERIFIED);
        return result;
    }

    private LoginLimitDecision checkLimit(LoginAttempt attempt) {
        try {
            return rateLimitService.check(attempt);
        } catch (RuntimeException exception) {
            throw infrastructure("Login rate limiting is unavailable.", exception);
        }
    }

    private AuthenticationContext findAuthenticationContext(NormalizedLoginInput input) {
        IdentityPresenceKind kind = input.getIdentifierType() == LoginIdentifierType.EMAIL
                ? IdentityPresenceKind.EMAIL
                : IdentityPresenceKind.PHONE;
        IdentityPresenceDecision presence = kind == IdentityPresenceKind.EMAIL
                ? identityPresenceFilter.checkEmail(input.getIdentifier())
                : identityPresenceFilter.checkPhone(input.getIdentifier());
        if (presence == IdentityPresenceDecision.DEFINITELY_ABSENT) {
            return null;
        }
        try {
            AuthenticationContext context = kind == IdentityPresenceKind.EMAIL
                    ? identityMapper.findAuthenticationByNormalizedEmail(input.getIdentifier())
                    : identityMapper.findAuthenticationByNormalizedPhone(input.getIdentifier());
            identityPresenceFilter.recordDatabaseVerification(
                    kind, presence, context != null);
            return context;
        } catch (RuntimeException exception) {
            throw infrastructure("Login authentication lookup failed.", exception);
        }
    }

    private boolean matches(String rawPassword, String encodedPassword) {
        try {
            return passwordEncoder.matches(rawPassword, encodedPassword);
        } catch (RuntimeException exception) {
            throw infrastructure("Password verification failed.", exception);
        }
    }

    private void reject(
            LoginAttempt attempt, LoginErrorCode errorCode, LoginAuditReason reason) {
        LoginLimitDecision decision;
        try {
            decision = rateLimitService.recordFailure(attempt);
        } catch (RuntimeException exception) {
            throw infrastructure("Login failure recording is unavailable.", exception);
        }
        if (decision == LoginLimitDecision.BLOCKED) {
            auditObserver.observe(LoginAuditOutcome.REJECTED, LoginAuditReason.BLOCKED);
            throw blocked();
        }
        auditObserver.observe(LoginAuditOutcome.REJECTED, reason);
        throw new LoginException(errorCode, externalMessage(errorCode));
    }

    private static boolean isAvailable(AuthenticationContext context) {
        return context.getIdentityId() > 0
                && context.getPasswordVersion() > 0
                && context.getAccountStatus() == AccountStatus.ACTIVE
                && context.getPasswordHash() != null
                && !context.getPasswordHash().isBlank()
                && context.getDisplayName() != null
                && !context.getDisplayName().isBlank();
    }

    private void upgradePasswordHashIfRequired(
            AuthenticationContext context, String rawPassword) {
        final boolean upgradeRequired;
        try {
            upgradeRequired = passwordEncoder.upgradeEncoding(context.getPasswordHash());
        } catch (RuntimeException exception) {
            throw infrastructure("Password upgrade check failed.", exception);
        }
        if (!upgradeRequired) {
            return;
        }

        final String upgradedHash;
        final int updated;
        try {
            upgradedHash = passwordEncoder.encode(rawPassword);
            // 以读取到的旧哈希作为 CAS 条件；并发请求先完成升级后，后到请求只能安全地放弃覆盖。
            updated = identityMapper.upgradePasswordHashCas(
                    context.getIdentityId(), context.getPasswordHash(), upgradedHash);
        } catch (RuntimeException exception) {
            throw infrastructure("Password hash upgrade failed.", exception);
        }
        if (updated == 1) {
            auditObserver.observe(LoginAuditOutcome.SUCCESS, LoginAuditReason.PASSWORD_UPGRADED);
            return;
        }
        if (updated == 0) {
            auditObserver.observe(
                    LoginAuditOutcome.SUCCESS, LoginAuditReason.PASSWORD_UPGRADE_CONFLICT);
            return;
        }
        throw infrastructure("Password hash upgrade affected an invalid row count.", null);
    }

    private void clearSubjectFailures(LoginAttempt attempt) {
        try {
            rateLimitService.clearSubjectFailures(attempt);
        } catch (RuntimeException exception) {
            throw infrastructure("Login failure counter clearing is unavailable.", exception);
        }
    }

    private LoginException infrastructure(String message, Throwable cause) {
        auditObserver.observe(LoginAuditOutcome.FAILURE, LoginAuditReason.INFRASTRUCTURE);
        return new LoginException(
                LoginErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                message,
                cause);
    }

    private static LoginException blocked() {
        return new LoginException(LoginErrorCode.LOGIN_BLOCKED, "Login is temporarily blocked.");
    }

    private static String externalMessage(LoginErrorCode code) {
        if (code == LoginErrorCode.AUTHENTICATION_FAILED) {
            return "Login identifier or password is invalid.";
        }
        if (code == LoginErrorCode.ACCOUNT_UNAVAILABLE) {
            return "Account is unavailable.";
        }
        return "Login failed.";
    }
}
