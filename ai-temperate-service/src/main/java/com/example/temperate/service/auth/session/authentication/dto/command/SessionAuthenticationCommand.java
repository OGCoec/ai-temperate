package com.example.temperate.service.auth.session.authentication.dto.command;

import lombok.Getter;

/**
 * 承载常规刷新会话认证所需的 AT、RT、CSRF 和设备绑定材料。
 *
 * <p>访问令牌可用于一致性核对，但刷新会话才是续期的状态依据。</p>
 */
@Getter
public final class SessionAuthenticationCommand {

    private final String accessToken;
    private final String refreshToken;
    private final String presentedCsrfToken;
    private final String deviceInstallationId;

    public SessionAuthenticationCommand(
            String accessToken,
            String refreshToken,
            String presentedCsrfToken,
            String deviceInstallationId) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.presentedCsrfToken = presentedCsrfToken;
        this.deviceInstallationId = deviceInstallationId;
    }
}
