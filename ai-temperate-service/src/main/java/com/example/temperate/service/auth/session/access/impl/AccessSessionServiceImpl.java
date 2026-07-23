package com.example.temperate.service.auth.session.access.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.service.auth.session.access.AccessSessionService;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.auth.session.token.dto.result.VerifiedAccessToken;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 验证短期访问令牌并基于数据库当前账号状态恢复会话主体的实现。
 *
 * <p>AT 仅提供经过签名的公共用户标识；每次认证仍查询账号当前状态，以使禁用和删除操作立即阻断后续 API 访问。</p>
 */
@Service
public final class AccessSessionServiceImpl implements AccessSessionService {

    private final AuthTokenService tokenService;
    private final UserLoginIdentityMapper identityMapper;
    private final PublicIdCodec publicIdCodec;

    public AccessSessionServiceImpl(
            AuthTokenService tokenService,
            UserLoginIdentityMapper identityMapper,
            PublicIdCodec publicIdCodec) {
        this.tokenService = Objects.requireNonNull(tokenService);
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
    }

    @Override
    public SessionPrincipal authenticate(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw error(SessionAuthenticationErrorCode.ACCESS_TOKEN_REQUIRED,
                    "Access token is required.");
        }
        final VerifiedAccessToken verified;
        try {
            verified = tokenService.verifyAccessToken(accessToken);
        } catch (RuntimeException exception) {
            throw new SessionAuthenticationException(
                    SessionAuthenticationErrorCode.ACCESS_TOKEN_INVALID,
                    "Access token is invalid.", true, exception);
        }
        if (verified.expired()) {
            throw error(SessionAuthenticationErrorCode.ACCESS_TOKEN_EXPIRED,
                    "Access token has expired.");
        }
        // 公共 ID 必须先还原为内部主键，再以数据库状态作为最终授权依据，不能信任令牌中的历史资料快照。
        long userId = publicIdCodec.decode(verified.publicId());
        AuthenticationContext context = identityMapper.findAuthenticationById(userId);
        if (context == null
                || context.getIdentityId() != userId
                || context.getAccountStatus() != AccountStatus.ACTIVE) {
            throw error(SessionAuthenticationErrorCode.ACCOUNT_UNAVAILABLE,
                    "Account is unavailable.");
        }
        return new SessionPrincipal(
                userId,
                verified.publicId(),
                context.getDisplayName());
    }

    private static SessionAuthenticationException error(
            SessionAuthenticationErrorCode code, String message) {
        return new SessionAuthenticationException(code, message, true);
    }
}
