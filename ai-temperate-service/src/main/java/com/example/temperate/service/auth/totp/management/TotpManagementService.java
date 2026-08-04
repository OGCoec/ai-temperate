package com.example.temperate.service.auth.totp.management;

import com.example.temperate.service.auth.totp.management.dto.TotpSetupResult;
import com.example.temperate.service.auth.totp.management.dto.TotpStateChangeResult;
import com.example.temperate.service.auth.totp.management.dto.TotpStatusResult;

/**
 * 定义当前用户查询、开启、轮换和关闭 TOTP 的业务边界。
 *
 * <p>设置流程只在新验证码确认成功后写入数据库；关闭流程必须清空密钥并撤销全部刷新会话。</p>
 */
public interface TotpManagementService {

    TotpStatusResult status(long userId);

    TotpSetupResult startSetup(
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            String stepUpToken,
            String currentTotpCode);

    TotpStateChangeResult confirmSetup(
            long userId,
            String deviceInstallationId,
            String setupToken,
            String code);

    TotpStateChangeResult disable(
            long userId,
            String deviceInstallationId,
            String stepUpToken,
            String currentTotpCode);
}
