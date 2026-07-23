package com.example.temperate.service.auth.session.authentication.dto.command;

import lombok.Getter;

/**
 * 承载撤销刷新会话所需的刷新令牌、CSRF 与设备绑定材料。
 *
 * <p>这些字段均为敏感认证数据，只能用于当前登出请求的服务端校验，禁止记录或缓存。</p>
 */
@Getter
public final class LogoutCommand {

    private final String refreshToken;
    private final String presentedCsrfToken;
    private final String deviceInstallationId;

    public LogoutCommand(String refreshToken) {
        this(refreshToken, null, null);
    }

    public LogoutCommand(
            String refreshToken,
            String presentedCsrfToken,
            String deviceInstallationId) {
        this.refreshToken = refreshToken;
        this.presentedCsrfToken = presentedCsrfToken;
        this.deviceInstallationId = deviceInstallationId;
    }
}
