package com.example.temperate.service.auth.session.authentication.service.impl;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.auth.session.authentication.dto.command.LogoutCommand;
import com.example.temperate.service.auth.session.authentication.dto.command.SessionBootstrapCommand;
import com.example.temperate.service.auth.session.authentication.dto.result.SessionAuthenticationResult;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.auth.session.authentication.service.SessionAuthenticationService;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionRevocation;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionSnapshot;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionValidation;
import com.example.temperate.service.auth.session.refresh.store.RefreshSessionStore;
import com.example.temperate.service.auth.session.token.dto.result.VerifiedAccessToken;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 会话恢复与撤销的业务协调器。
 *
 * <p>该服务只负责 bootstrap、当前设备退出和全部设备撤销；普通业务请求的 RT-first 认证和 AT
 * 自动续签由 AccessSessionService 承担。</p>
 *
 * <p>签发新访问令牌前会再次读取数据库中的账号状态，因此禁用、删除等账号变更不会仅因 Redis
 * 会话快照仍存在而继续获得访问权限。</p>
 */
@Service
public final class SessionAuthenticationServiceImpl implements SessionAuthenticationService {

    private static final int LOGOUT_ALL_MAX_ATTEMPTS = 3;
    private static final System.Logger LOGGER =
            System.getLogger(SessionAuthenticationServiceImpl.class.getName());

    private final AuthTokenService authTokenService;
    private final RefreshSessionStore refreshSessionStore;
    private final AuthSessionSecretProtector secretProtector;
    private final UserLoginIdentityMapper identityMapper;

    public SessionAuthenticationServiceImpl(
            AuthTokenService authTokenService,
            RefreshSessionStore refreshSessionStore,
            AuthSessionSecretProtector secretProtector,
            UserLoginIdentityMapper identityMapper) {
        this.authTokenService = Objects.requireNonNull(authTokenService);
        this.refreshSessionStore = Objects.requireNonNull(refreshSessionStore);
        this.secretProtector = Objects.requireNonNull(secretProtector);
        this.identityMapper = Objects.requireNonNull(identityMapper);
    }

    /**
     * 使用有效刷新会话重新建立 CSRF 绑定。CSRF 摘要替换与会话续期必须是同一原子操作，成功后
     * 旧的 CSRF 请求头不能再通过后续写请求校验。
     */
    @Override
    public SessionAuthenticationResult bootstrap(SessionBootstrapCommand command) {
        return bootstrap(command, null);
    }

    /**
     * 在恢复浏览器会话时原子完成 CSRF 轮换、Refresh Session 续期和认证 PreAuth 续期。
     */
    @Override
    public SessionAuthenticationResult bootstrap(
            SessionBootstrapCommand command,
            PreAuthSessionBinding preAuthBinding) {
        requireRefreshCommand(command == null ? null : command.getRefreshToken());
        VerifiedAccessToken optionalAccess = verifyOptionalAccess(command.getAccessToken());
        // 原始 CSRF 仅在本次响应中返回；Redis 只保存其受保护标识，避免长期存放可直接重放的值。
        String newCsrfToken = authTokenService.newCsrfToken();
        RefreshSessionSnapshot session;
        try {
            session = requireValid(preAuthBinding == null
                    ? refreshSessionStore.bootstrapAndRenew(
                            secretProtector.refreshToken(command.getRefreshToken()),
                            secretProtector.device(command.getDeviceInstallationId()),
                            secretProtector.csrf(newCsrfToken))
                    : refreshSessionStore.bootstrapAndRenewWithPreAuth(
                            secretProtector.refreshToken(command.getRefreshToken()),
                            secretProtector.device(command.getDeviceInstallationId()),
                            secretProtector.csrf(newCsrfToken),
                            preAuthBinding));
        } catch (SessionAuthenticationException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw error(SessionAuthenticationErrorCode.REFRESH_TOKEN_INVALID,
                    "Refresh session is invalid.", true, exception);
        } catch (RuntimeException exception) {
            throw error(SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "Session bootstrap is temporarily unavailable.", false, exception);
        }
        requireAccessMatchesSession(optionalAccess, session);
        AuthenticationContext context = requireCurrentAccount(session);
        return new SessionAuthenticationResult(
                principal(context, session),
                authTokenService.issueAccessToken(session.userId()),
                newCsrfToken,
                session.expiresAt());
    }

    @Override
    public void logout(LogoutCommand command) {
        if (command == null || isBlank(command.getRefreshToken())) {
            return;
        }
        if (isBlank(command.getPresentedCsrfToken())
                || isBlank(command.getDeviceInstallationId())) {
            throw error(SessionAuthenticationErrorCode.INVALID_INPUT,
                    "Device and CSRF credentials are required for logout.", false);
        }
        final RefreshSessionRevocation revocation;
        try {
            revocation = refreshSessionStore.revoke(
                    secretProtector.refreshToken(command.getRefreshToken()),
                    secretProtector.device(command.getDeviceInstallationId()),
                    secretProtector.csrf(command.getPresentedCsrfToken()));
        } catch (RuntimeException exception) {
            throw error(SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "Logout is temporarily unavailable.", false, exception);
        }
        switch (revocation.status()) {
            case REVOKED -> {
                return;
            }
            case MISSING_OR_EXPIRED -> throw error(
                    SessionAuthenticationErrorCode.REFRESH_TOKEN_INVALID,
                    "Refresh session is missing or expired.", true);
            case DEVICE_MISMATCH -> throw error(
                    SessionAuthenticationErrorCode.DEVICE_MISMATCH,
                    "Refresh session device does not match.", true);
            case CSRF_MISMATCH -> throw error(
                    SessionAuthenticationErrorCode.CSRF_INVALID,
                    "CSRF token is invalid.", false);
            case INDEX_BOUND_EXCEEDED -> throw error(
                    SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "Session index is temporarily unavailable.", false);
        }
    }

    @Override
    public int revokeAllForUser(long userId) {
        if (userId <= 0) {
            throw error(SessionAuthenticationErrorCode.INVALID_INPUT,
                    "User is invalid.", false);
        }
        try {
            return refreshSessionStore.revokeAllForUser(userId);
        } catch (RuntimeException exception) {
            throw error(SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "Session revocation is temporarily unavailable.", false, exception);
        }
    }

    @Override
    public int logoutAllForUser(long userId) {
        if (userId <= 0) {
            throw error(SessionAuthenticationErrorCode.INVALID_INPUT,
                    "User is invalid.", false);
        }
        RuntimeException lastFailure = null;
        // 退出所有设备只重试完整的批量 UNLINK；不把单个 Refresh Token 拆成逐条 Redis I/O。
        for (int attempt = 1; attempt <= LOGOUT_ALL_MAX_ATTEMPTS; attempt++) {
            try {
                return refreshSessionStore.revokeAllForUser(userId);
            } catch (RuntimeException exception) {
                lastFailure = exception;
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "event=session_logout_all_retry_failed attempt=" + attempt);
            }
        }
        throw error(
                SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                "Session revocation is temporarily unavailable.",
                false,
                lastFailure);
    }

    private RefreshSessionSnapshot requireValid(RefreshSessionValidation validation) {
        return switch (validation.status()) {
            case VALID -> validation.session();
            case MISSING_OR_EXPIRED, INDEX_MISSING -> throw error(
                    SessionAuthenticationErrorCode.REFRESH_TOKEN_INVALID,
                    "Refresh session is missing or expired.", true);
            case DEVICE_MISMATCH -> throw error(
                    SessionAuthenticationErrorCode.DEVICE_MISMATCH,
                    "Refresh session device does not match.", true);
            case CSRF_MISMATCH -> throw error(
                    SessionAuthenticationErrorCode.CSRF_INVALID,
                    "CSRF token is invalid.", false);
            case PREAUTH_MISMATCH -> throw error(
                    SessionAuthenticationErrorCode.PREAUTH_REQUIRED,
                    "Authenticated PreAuth is missing or no longer bound to this session.",
                    false);
            case TTL_INVARIANT_VIOLATION -> throw error(
                    SessionAuthenticationErrorCode.REFRESH_TOKEN_INVALID,
                    "Refresh session is invalid.", true);
        };
    }

    private AuthenticationContext requireCurrentAccount(RefreshSessionSnapshot session) {
        final AuthenticationContext context;
        try {
            context = identityMapper.findAuthenticationById(session.userId());
        } catch (RuntimeException exception) {
            throw error(SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "Account validation is temporarily unavailable.", false, exception);
        }
        // Redis 会话中的联系方式是创建时快照，账号可用性必须以数据库当前状态为最终依据。
        if (context == null
                || context.getIdentityId() != session.userId()
                || context.getAccountStatus() != AccountStatus.ACTIVE) {
            refreshSessionStore.revokeAllForUser(session.userId());
            throw error(SessionAuthenticationErrorCode.ACCOUNT_UNAVAILABLE,
                    "Account is unavailable.", true);
        }
        return context;
    }

    private VerifiedAccessToken verifyOptionalAccess(String rawAccessToken) {
        if (isBlank(rawAccessToken)) {
            return null;
        }
        try {
            return authTokenService.verifyAccessToken(rawAccessToken);
        } catch (RuntimeException exception) {
            throw error(SessionAuthenticationErrorCode.ACCESS_TOKEN_INVALID,
                    "Access token is invalid.", true, exception);
        }
    }

    private void requireAccessMatchesSession(
            VerifiedAccessToken access, RefreshSessionSnapshot session) {
        if (access == null) {
            return;
        }
        // 使用规范公共 ID 的常量时间比较，避免把其他用户的 AT 与当前 RT 会话组合使用。
        if (!MessageDigest.isEqual(
                access.publicId().getBytes(StandardCharsets.US_ASCII),
                session.publicId().getBytes(StandardCharsets.US_ASCII))) {
            throw error(SessionAuthenticationErrorCode.SESSION_MISMATCH,
                    "Access token does not belong to this refresh session.", true);
        }
    }

    private static SessionPrincipal principal(
            AuthenticationContext context, RefreshSessionSnapshot session) {
        return new SessionPrincipal(
                session.userId(),
                session.publicId(),
                isBlank(context.getDisplayName()) ? "用户" : context.getDisplayName());
    }

    private static void requireRefreshCommand(String refreshToken) {
        if (isBlank(refreshToken)) {
            throw error(SessionAuthenticationErrorCode.REFRESH_TOKEN_REQUIRED,
                    "Refresh token is required.", true);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static SessionAuthenticationException error(
            SessionAuthenticationErrorCode code, String message, boolean clearCookies) {
        return new SessionAuthenticationException(code, message, clearCookies);
    }

    private static SessionAuthenticationException error(
            SessionAuthenticationErrorCode code,
            String message,
            boolean clearCookies,
            Throwable cause) {
        return new SessionAuthenticationException(code, message, clearCookies, cause);
    }
}
