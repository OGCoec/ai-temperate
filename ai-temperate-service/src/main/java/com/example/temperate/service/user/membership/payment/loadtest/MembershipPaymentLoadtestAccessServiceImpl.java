package com.example.temperate.service.user.membership.payment.loadtest;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.auth.session.token.dto.result.VerifiedAccessToken;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 该实现是来执行会员支付压测专用 AT-only 认证，数据库仍实时裁决账号和会员额度是否可用。
 *
 * <p>它不验证或续签 Refresh Session，不改变账号及额度，也不向回调路由提供认证；调用方必须先用精确方法和路径策略缩小适用范围。</p>
 */
@Service
public final class MembershipPaymentLoadtestAccessServiceImpl
        implements MembershipPaymentLoadtestAccessService {

    private final MembershipPaymentLoadtestProperties properties;
    private final AuthTokenService tokenService;
    private final PublicIdCodec publicIdCodec;
    private final UserLoginIdentityMapper identityMapper;
    private final UserMembershipQuotaMapper quotaMapper;
    private final Set<Long> allowedUserIds;

    public MembershipPaymentLoadtestAccessServiceImpl(
            MembershipPaymentLoadtestProperties properties,
            AuthTokenService tokenService,
            PublicIdCodec publicIdCodec,
            UserLoginIdentityMapper identityMapper,
            UserMembershipQuotaMapper quotaMapper) {
        this.properties = Objects.requireNonNull(properties);
        this.tokenService = Objects.requireNonNull(tokenService);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.quotaMapper = Objects.requireNonNull(quotaMapper);
        this.allowedUserIds = Set.copyOf(properties.allowedUserIds());
    }

    @Override
    public SessionPrincipal authenticate(String rawAccessToken) {
        if (!properties.enabled()) {
            throw error(
                    SessionAuthenticationErrorCode.ACCESS_TOKEN_INVALID,
                    "Membership payment loadtest authentication is disabled.");
        }
        if (rawAccessToken == null || rawAccessToken.isBlank()) {
            throw error(
                    SessionAuthenticationErrorCode.ACCESS_TOKEN_REQUIRED,
                    "Access token is required.");
        }

        VerifiedAccessToken verified;
        long userId;
        try {
            verified = tokenService.verifyAccessToken(rawAccessToken);
            if (verified.expired()) {
                throw new IllegalArgumentException("Access token is expired.");
            }
            userId = publicIdCodec.decode(verified.publicId());
        } catch (RuntimeException exception) {
            throw error(
                    SessionAuthenticationErrorCode.ACCESS_TOKEN_INVALID,
                    "Access token is invalid.",
                    exception);
        }

        // 白名单必须先于数据库读取，避免任意合法用户借压测分支探测账号或会员记录。
        if (!allowedUserIds.contains(userId)) {
            throw error(
                    SessionAuthenticationErrorCode.ACCOUNT_UNAVAILABLE,
                    "Account is unavailable for membership payment loadtest.");
        }

        final AuthenticationContext context;
        final UserMembershipQuota quota;
        try {
            context = identityMapper.findAuthenticationById(userId);
            quota = quotaMapper.findByLoginIdentityId(userId);
        } catch (RuntimeException exception) {
            throw error(
                    SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "Membership payment loadtest account validation is temporarily unavailable.",
                    exception);
        }
        if (context == null
                || context.getIdentityId() != userId
                || context.getAccountStatus() != AccountStatus.ACTIVE
                || quota == null
                || quota.getLoginIdentityId() == null
                || quota.getLoginIdentityId() != userId) {
            throw error(
                    SessionAuthenticationErrorCode.ACCOUNT_UNAVAILABLE,
                    "Account is unavailable for membership payment loadtest.");
        }

        return new SessionPrincipal(
                userId,
                verified.publicId(),
                context.getDisplayName() == null || context.getDisplayName().isBlank()
                        ? "用户"
                        : context.getDisplayName());
    }

    private static SessionAuthenticationException error(
            SessionAuthenticationErrorCode code,
            String message) {
        return new SessionAuthenticationException(code, message, false);
    }

    private static SessionAuthenticationException error(
            SessionAuthenticationErrorCode code,
            String message,
            Throwable cause) {
        return new SessionAuthenticationException(code, message, false, cause);
    }
}
