package com.example.temperate.service.auth.login.service.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.service.auth.login.audit.enums.LoginAuditOutcome;
import com.example.temperate.service.auth.login.audit.enums.LoginAuditReason;
import com.example.temperate.service.auth.login.audit.observer.LoginAuditObserver;
import com.example.temperate.service.auth.login.component.normalizer.LoginInputNormalizer;
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
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.session.refresh.dto.command.NewRefreshSession;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionSnapshot;
import com.example.temperate.service.auth.session.refresh.store.RefreshSessionStore;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import java.util.Objects;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 编排密码登录的输入规范化、风控检查、账号校验、密码升级和会话创建。
 *
 * <p>密码比对对存在与不存在的账号保持相同的计算路径，以减少账号枚举时序差异；密码哈希升级在本地事务中
 * 使用 CAS 写入，避免并发登录回退已升级的哈希。</p>
 */
@Service
public final class LoginServiceImpl implements LoginService {

    private static final String DUMMY_PASSWORD_HASH =
            "{bcrypt}$2a$10$JieVY2BiTm1zdN1W07/YxurgOHVm0i5fEmyEnFvyKI3m4jJTukJb6";

    private final LoginInputNormalizer inputNormalizer;
    private final UserLoginIdentityMapper identityMapper;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimitService rateLimitService;
    private final LoginAuditObserver auditObserver;
    private final AuthTokenService authTokenService;
    private final RefreshSessionStore refreshSessionStore;
    private final AuthSessionSecretProtector secretProtector;
    private final PublicIdCodec publicIdCodec;

    public LoginServiceImpl(
            LoginInputNormalizer inputNormalizer,
            UserLoginIdentityMapper identityMapper,
            PasswordEncoder passwordEncoder,
            LoginRateLimitService rateLimitService,
            LoginAuditObserver auditObserver,
            AuthTokenService authTokenService,
            RefreshSessionStore refreshSessionStore,
            AuthSessionSecretProtector secretProtector,
            PublicIdCodec publicIdCodec) {
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
        this.authTokenService = Objects.requireNonNull(
                authTokenService, "authTokenService must not be null");
        this.refreshSessionStore = Objects.requireNonNull(
                refreshSessionStore, "refreshSessionStore must not be null");
        this.secretProtector = Objects.requireNonNull(
                secretProtector, "secretProtector must not be null");
        this.publicIdCodec = Objects.requireNonNull(
                publicIdCodec, "publicIdCodec must not be null");
    }

    @Override
    @Transactional
    public LoginResult login(LoginCommand command) {
        NormalizedLoginInput input = inputNormalizer.normalize(command);
        LoginAttempt attempt = new LoginAttempt(
                input.getIdentifier(),
                input.getDeviceInstallationId(),
                input.getCanonicalClientIp());

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
        LoginResult result = createSession(context, input);
        clearSubjectFailures(attempt);
        auditObserver.observe(LoginAuditOutcome.SUCCESS, LoginAuditReason.AUTHENTICATED);
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
        try {
            if (input.getIdentifierType() == LoginIdentifierType.EMAIL) {
                return identityMapper.findAuthenticationByNormalizedEmail(input.getIdentifier());
            }
            return identityMapper.findAuthenticationByNormalizedPhone(input.getIdentifier());
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

    private LoginResult createSession(
            AuthenticationContext context, NormalizedLoginInput input) {
        try {
            String publicId = publicIdCodec.encode(context.getIdentityId());
            String refreshToken = authTokenService.newRefreshToken();
            String csrfToken = authTokenService.newCsrfToken();
            // 原始 RT/CSRF 只在响应中短暂返回，Redis 会话持久化的是其受保护标识和设备绑定。
            RefreshSessionSnapshot session = refreshSessionStore.create(new NewRefreshSession(
                    context.getIdentityId(),
                    publicId,
                    secretProtector.refreshToken(refreshToken),
                    secretProtector.device(input.getDeviceInstallationId()),
                    secretProtector.csrf(csrfToken),
                    context.getEmail(),
                    context.getPhone()));
            String accessToken = authTokenService.issueAccessToken(context.getIdentityId());
            return new LoginResult(
                    publicId,
                    context.getDisplayName(),
                    accessToken,
                    refreshToken,
                    csrfToken,
                    session.expiresAt());
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().contains("limit")) {
                throw new LoginException(
                        LoginErrorCode.SESSION_LIMIT_REACHED,
                        "The account already has ten active sessions.",
                        exception);
            }
            throw infrastructure("Login session creation failed.", exception);
        } catch (RuntimeException exception) {
            throw infrastructure("Login session creation failed.", exception);
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
