package com.example.temperate.service.auth.totp.login;

import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.totp.login.dto.TotpLoginChallengeResult;

/**
 * 定义登录 TOTP 挑战创建和最终验证码校验、会话签发的业务边界。
 */
public interface TotpLoginService {

    TotpLoginChallengeResult start(long userId, String deviceInstallationId);

    LoginResult verify(
            String rawFlowToken,
            String deviceInstallationId,
            String code);
}
