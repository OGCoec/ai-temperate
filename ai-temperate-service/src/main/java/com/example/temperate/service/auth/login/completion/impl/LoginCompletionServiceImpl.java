package com.example.temperate.service.auth.login.completion.impl;

import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.service.auth.login.completion.LoginCompletionService;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.login.session.LoginSessionIssuer;
import com.example.temperate.service.auth.totp.login.TotpLoginService;
import com.example.temperate.service.auth.totp.login.dto.TotpLoginChallengeResult;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 根据数据库认证快照中的 TOTP 状态完成登录或创建短期二次认证挑战。
 *
 * <p>该服务是全部普通用户登录策略共享的安全门；启用 TOTP 时禁止提前调用会话签发器，确保第一因子结果
 * 不能直接转换成 Access Token、Refresh Token 或 CSRF Token。</p>
 */
@Service
public final class LoginCompletionServiceImpl implements LoginCompletionService {

    private final LoginSessionIssuer sessionIssuer;
    private final TotpLoginService totpLoginService;

    public LoginCompletionServiceImpl(
            LoginSessionIssuer sessionIssuer,
            TotpLoginService totpLoginService) {
        this.sessionIssuer = Objects.requireNonNull(sessionIssuer);
        this.totpLoginService = Objects.requireNonNull(totpLoginService);
    }

    @Override
    public LoginResult complete(
            AuthenticationContext context,
            String deviceInstallationId) {
        AuthenticationContext valid = Objects.requireNonNull(context);
        if (!valid.isTotpEnabled()) {
            return sessionIssuer.issue(valid, deviceInstallationId);
        }
        TotpLoginChallengeResult challenge = totpLoginService.start(
                valid.getIdentityId(), deviceInstallationId);
        return LoginResult.totpRequired(
                challenge.rawFlowToken(),
                challenge.expiresAt(),
                challenge.attemptsRemaining());
    }
}
