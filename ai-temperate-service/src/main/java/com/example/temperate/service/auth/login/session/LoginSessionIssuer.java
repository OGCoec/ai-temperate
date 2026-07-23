package com.example.temperate.service.auth.login.session;

import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.service.auth.login.dto.result.LoginResult;

/**
 * 定义将已验证账号转换为新的认证会话和令牌结果的能力。
 */
public interface LoginSessionIssuer {

    LoginResult issue(AuthenticationContext context, String deviceInstallationId);
}
