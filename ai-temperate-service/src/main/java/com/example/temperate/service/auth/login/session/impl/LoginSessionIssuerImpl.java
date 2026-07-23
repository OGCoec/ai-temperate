package com.example.temperate.service.auth.login.session.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.login.session.LoginSessionIssuer;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.session.refresh.dto.command.NewRefreshSession;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionSnapshot;
import com.example.temperate.service.auth.session.refresh.store.RefreshSessionStore;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 创建刷新会话、CSRF 绑定和短期访问令牌的会话签发实现。
 *
 * <p>刷新会话先以受保护的 RT、设备和 CSRF 标识写入 Redis；只有会话创建成功后才签发 AT，
 * 避免向客户端返回没有服务端会话依据的认证材料。</p>
 */
@Service
public final class LoginSessionIssuerImpl implements LoginSessionIssuer {

    private final AuthTokenService tokenService;
    private final RefreshSessionStore sessionStore;
    private final AuthSessionSecretProtector protector;
    private final PublicIdCodec publicIdCodec;

    public LoginSessionIssuerImpl(
            AuthTokenService tokenService,
            RefreshSessionStore sessionStore,
            AuthSessionSecretProtector protector,
            PublicIdCodec publicIdCodec) {
        this.tokenService = Objects.requireNonNull(tokenService);
        this.sessionStore = Objects.requireNonNull(sessionStore);
        this.protector = Objects.requireNonNull(protector);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
    }

    @Override
    public LoginResult issue(AuthenticationContext context, String deviceInstallationId) {
        Objects.requireNonNull(context);
        String refreshToken = tokenService.newRefreshToken();
        String csrfToken = tokenService.newCsrfToken();
        try {
            // 先落库式创建会话状态，再生成可用于普通 API 的 AT，保证二者对应同一用户和设备绑定。
            RefreshSessionSnapshot session = sessionStore.create(new NewRefreshSession(
                    context.getIdentityId(),
                    publicIdCodec.encode(context.getIdentityId()),
                    protector.refreshToken(refreshToken),
                    protector.device(deviceInstallationId),
                    protector.csrf(csrfToken),
                    context.getEmail(),
                    context.getPhone()));
            String accessToken = tokenService.issueAccessToken(context.getIdentityId());
            return new LoginResult(
                    session.publicId(),
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
            throw new LoginException(
                    LoginErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "Login session creation is unavailable.",
                    exception);
        }
    }
}
