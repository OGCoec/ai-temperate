package com.example.temperate.service.auth.login.completion;

import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.service.auth.login.dto.result.LoginResult;

/**
 * 定义第一因子成功后签发会话或进入 TOTP 二次认证的唯一业务决策边界。
 */
public interface LoginCompletionService {

    LoginResult complete(
            AuthenticationContext context,
            String deviceInstallationId);
}
