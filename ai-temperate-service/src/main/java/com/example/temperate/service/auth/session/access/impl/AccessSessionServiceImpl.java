package com.example.temperate.service.auth.session.access.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.session.access.AccessSessionService;
import com.example.temperate.service.auth.session.access.dto.SessionAccessCommand;
import com.example.temperate.service.auth.session.access.dto.SessionAccessResult;
import com.example.temperate.service.auth.session.access.observability.AccessSessionMetrics;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
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
 * 编排普通用户 RT-first 会话认证，并仅在 AT 签名合法但过期时于当前请求中续签 AT。
 *
 * <p>Refresh Session 是会话是否仍有效的首要依据；RT 校验失败后禁止解析 AT 或查询数据库。AT
 * 缺失、被篡改或与 RT 不属于同一用户时也禁止通过 RT 修复，从而保证退出全部设备能够让旧 AT 在下一次请求立即失效。</p>
 */
@Service
public final class AccessSessionServiceImpl implements AccessSessionService {

    private static final System.Logger LOGGER =
            System.getLogger(AccessSessionServiceImpl.class.getName());

    private final AuthTokenService tokenService;
    private final RefreshSessionStore refreshSessionStore;
    private final AuthSessionSecretProtector secretProtector;
    private final UserLoginIdentityMapper identityMapper;
    private final AccessSessionMetrics metrics;

    public AccessSessionServiceImpl(
            AuthTokenService tokenService,
            RefreshSessionStore refreshSessionStore,
            AuthSessionSecretProtector secretProtector,
            UserLoginIdentityMapper identityMapper,
            AccessSessionMetrics metrics) {
        this.tokenService = Objects.requireNonNull(tokenService);
        this.refreshSessionStore = Objects.requireNonNull(refreshSessionStore);
        this.secretProtector = Objects.requireNonNull(secretProtector);
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public SessionAccessResult authenticateOrRenew(SessionAccessCommand command) {
        return authenticateOrRenew(command, null);
    }

    @Override
    public SessionAccessResult authenticateOrRenew(
            SessionAccessCommand command,
            PreAuthSessionBinding preAuthBinding) {
        SessionAccessCommand validCommand = requireSessionCredentials(command);
        ProtectedCredentials credentials = protect(validCommand);

        // 此处的只读 Lua 必须先于 AT 解析和数据库访问，以便被撤销的 RT 立即终止整个请求。
        RefreshSessionSnapshot session = validateRefresh(
                readValidation(credentials, preAuthBinding));
        VerifiedAccessToken access = verifyRequiredAccess(validCommand.accessToken());
        requireSamePublicId(access.publicId(), session.publicId());
        AuthenticationContext context = requireCurrentAccount(session);

        SessionPrincipal principal = principal(context, session);
        if (!access.expired()) {
            metrics.accessValid();
            return new SessionAccessResult(principal, false, null, session.expiresAt());
        }

        // 只读检查与续期之间可能发生并发登出，因此签发新 AT 前必须再次执行原子校验与续期。
        RefreshSessionSnapshot renewedSession = validateRefresh(
                renewValidation(credentials, preAuthBinding));
        if (renewedSession.userId() != session.userId()) {
            metrics.sessionMismatch();
            throw error(SessionAuthenticationErrorCode.SESSION_MISMATCH,
                    "Renewed refresh session does not match the authenticated session.", true);
        }
        requireSamePublicId(renewedSession.publicId(), session.publicId());
        final String renewedAccessToken;
        try {
            renewedAccessToken = tokenService.issueAccessToken(renewedSession.userId());
        } catch (RuntimeException exception) {
            metrics.infrastructureFailure();
            throw error(SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "Access token issuance is temporarily unavailable.", false, exception);
        }
        metrics.accessRenewed();
        return new SessionAccessResult(
                principal,
                true,
                renewedAccessToken,
                renewedSession.expiresAt());
    }

    private RefreshSessionValidation readValidation(
            ProtectedCredentials credentials,
            PreAuthSessionBinding preAuthBinding) {
        try {
            return preAuthBinding == null
                    ? refreshSessionStore.validateForAccess(
                            credentials.refreshTokenHash(),
                            credentials.deviceHash(),
                            credentials.csrfHash())
                    : refreshSessionStore.validateForAccessWithPreAuth(
                            credentials.refreshTokenHash(),
                            credentials.deviceHash(),
                            credentials.csrfHash(),
                            preAuthBinding);
        } catch (IllegalArgumentException exception) {
            metrics.refreshInvalid();
            throw error(SessionAuthenticationErrorCode.REFRESH_TOKEN_INVALID,
                    "Refresh session is invalid.", true, exception);
        } catch (RuntimeException exception) {
            metrics.infrastructureFailure();
            throw error(SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "Session authentication is temporarily unavailable.", false, exception);
        }
    }

    private RefreshSessionValidation renewValidation(
            ProtectedCredentials credentials,
            PreAuthSessionBinding preAuthBinding) {
        try {
            return preAuthBinding == null
                    ? refreshSessionStore.validateAndRenew(
                            credentials.refreshTokenHash(),
                            credentials.deviceHash(),
                            credentials.csrfHash())
                    : refreshSessionStore.validateAndRenewWithPreAuth(
                            credentials.refreshTokenHash(),
                            credentials.deviceHash(),
                            credentials.csrfHash(),
                            preAuthBinding);
        } catch (IllegalArgumentException exception) {
            metrics.refreshInvalid();
            throw error(SessionAuthenticationErrorCode.REFRESH_TOKEN_INVALID,
                    "Refresh session is invalid.", true, exception);
        } catch (RuntimeException exception) {
            metrics.infrastructureFailure();
            throw error(SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "Session renewal is temporarily unavailable.", false, exception);
        }
    }

    private RefreshSessionSnapshot validateRefresh(RefreshSessionValidation validation) {
        if (validation == null) {
            metrics.infrastructureFailure();
            throw error(SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "Session validation returned no result.", false);
        }
        return switch (validation.status()) {
            case VALID -> {
                if (validation.session() == null) {
                    metrics.infrastructureFailure();
                    throw error(SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                            "Session validation returned an incomplete result.", false);
                }
                yield validation.session();
            }
            case MISSING_OR_EXPIRED, INDEX_MISSING -> {
                metrics.refreshInvalid();
                throw error(SessionAuthenticationErrorCode.REFRESH_TOKEN_INVALID,
                        "Refresh session is missing, expired, or revoked.", true);
            }
            case DEVICE_MISMATCH -> {
                metrics.refreshInvalid();
                throw error(SessionAuthenticationErrorCode.DEVICE_MISMATCH,
                        "Refresh session device does not match.", true);
            }
            case CSRF_MISMATCH -> {
                metrics.refreshInvalid();
                throw error(SessionAuthenticationErrorCode.CSRF_INVALID,
                        "CSRF token is invalid.", false);
            }
            case PREAUTH_MISMATCH -> {
                metrics.refreshInvalid();
                throw error(SessionAuthenticationErrorCode.PREAUTH_REQUIRED,
                        "Authenticated PreAuth is missing or no longer bound to this session.", false);
            }
            case TTL_INVARIANT_VIOLATION -> {
                metrics.ttlInvariantViolation();
                metrics.refreshInvalid();
                throw error(SessionAuthenticationErrorCode.REFRESH_TOKEN_INVALID,
                        "Refresh session is invalid.", true);
            }
        };
    }

    private SessionAccessCommand requireSessionCredentials(SessionAccessCommand command) {
        if (command == null || isBlank(command.refreshToken())) {
            metrics.refreshInvalid();
            throw error(SessionAuthenticationErrorCode.REFRESH_TOKEN_REQUIRED,
                    "Refresh token is required.", true);
        }
        if (isBlank(command.deviceInstallationId())) {
            metrics.refreshInvalid();
            throw error(SessionAuthenticationErrorCode.DEVICE_MISMATCH,
                    "Device installation ID is required.", true);
        }
        if (isBlank(command.presentedCsrfToken())) {
            metrics.refreshInvalid();
            throw error(SessionAuthenticationErrorCode.CSRF_INVALID,
                    "CSRF token is required.", false);
        }
        return command;
    }

    private ProtectedCredentials protect(SessionAccessCommand command) {
        try {
            return new ProtectedCredentials(
                    secretProtector.refreshToken(command.refreshToken()),
                    secretProtector.device(command.deviceInstallationId()),
                    secretProtector.csrf(command.presentedCsrfToken()));
        } catch (RuntimeException exception) {
            metrics.refreshInvalid();
            throw error(SessionAuthenticationErrorCode.REFRESH_TOKEN_INVALID,
                    "Refresh session credentials are invalid.", true, exception);
        }
    }

    private VerifiedAccessToken verifyRequiredAccess(String accessToken) {
        if (isBlank(accessToken)) {
            metrics.accessInvalid();
            throw error(SessionAuthenticationErrorCode.ACCESS_TOKEN_REQUIRED,
                    "Access token is required.", true);
        }
        try {
            return tokenService.verifyAccessToken(accessToken);
        } catch (RuntimeException exception) {
            metrics.accessInvalid();
            throw error(SessionAuthenticationErrorCode.ACCESS_TOKEN_INVALID,
                    "Access token is invalid.", true, exception);
        }
    }

    private void requireSamePublicId(String accessPublicId, String refreshPublicId) {
        if (!MessageDigest.isEqual(
                accessPublicId.getBytes(StandardCharsets.US_ASCII),
                refreshPublicId.getBytes(StandardCharsets.US_ASCII))) {
            metrics.sessionMismatch();
            throw error(SessionAuthenticationErrorCode.SESSION_MISMATCH,
                    "Access token does not belong to this refresh session.", true);
        }
    }

    private AuthenticationContext requireCurrentAccount(RefreshSessionSnapshot session) {
        final AuthenticationContext context;
        try {
            context = identityMapper.findAuthenticationById(session.userId());
        } catch (RuntimeException exception) {
            metrics.infrastructureFailure();
            throw error(SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "Account validation is temporarily unavailable.", false, exception);
        }
        if (context == null
                || context.getIdentityId() != session.userId()
                || context.getAccountStatus() != AccountStatus.ACTIVE) {
            try {
                // 账号不可用时批量撤销其 RT，使其他设备无需重复访问数据库即可收敛到会话失效状态。
                refreshSessionStore.revokeAllForUser(session.userId());
            } catch (RuntimeException exception) {
                metrics.infrastructureFailure();
                LOGGER.log(System.Logger.Level.WARNING,
                        "event=session_account_unavailable_revoke_failed");
            }
            metrics.accessInvalid();
            throw error(SessionAuthenticationErrorCode.ACCOUNT_UNAVAILABLE,
                    "Account is unavailable.", true);
        }
        return context;
    }

    private static SessionPrincipal principal(
            AuthenticationContext context,
            RefreshSessionSnapshot session) {
        return new SessionPrincipal(
                session.userId(),
                session.publicId(),
                isBlank(context.getDisplayName()) ? "用户" : context.getDisplayName());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static SessionAuthenticationException error(
            SessionAuthenticationErrorCode code,
            String message,
            boolean clearCookies) {
        return new SessionAuthenticationException(code, message, clearCookies);
    }

    private static SessionAuthenticationException error(
            SessionAuthenticationErrorCode code,
            String message,
            boolean clearCookies,
            Throwable cause) {
        return new SessionAuthenticationException(code, message, clearCookies, cause);
    }

    private record ProtectedCredentials(
            HmacIdentifier refreshTokenHash,
            HmacIdentifier deviceHash,
            HmacIdentifier csrfHash) {
    }
}
