package com.example.temperate.service.auth.totp.stepup;

import com.example.temperate.service.auth.login.code.dto.LoginCodeStartResult;
import com.example.temperate.service.auth.login.strategy.LoginStrategyType;
import com.example.temperate.service.auth.totp.management.TotpManagementAction;
import com.example.temperate.service.auth.totp.stepup.dto.TotpStepUpProofResult;

/**
 * 定义 TOTP 敏感操作使用密码、邮箱码或短信码完成第一因子复验的业务边界。
 */
public interface TotpStepUpService {

    TotpStepUpProofResult verifyPassword(
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            String rawPassword);

    LoginCodeStartResult startCode(
            long userId,
            String deviceInstallationId,
            String clientIp,
            TotpManagementAction action,
            LoginStrategyType type);

    TotpStepUpProofResult verifyCode(
            long userId,
            String deviceInstallationId,
            String clientIp,
            TotpManagementAction action,
            LoginStrategyType type,
            String rawFlowToken,
            String challengeHandle,
            String code);

    void requireProof(
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            String rawStepUpToken);

    void recordProofFailure(
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            String rawStepUpToken);

    void consumeProof(
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            String rawStepUpToken);
}
