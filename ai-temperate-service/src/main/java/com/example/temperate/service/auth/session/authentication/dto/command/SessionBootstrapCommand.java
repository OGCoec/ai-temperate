package com.example.temperate.service.auth.session.authentication.dto.command;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 承载通过有效刷新会话重新建立 CSRF 绑定的启动参数。
 */
@Getter
@RequiredArgsConstructor
public final class SessionBootstrapCommand {

    private final String accessToken;
    private final String refreshToken;
    private final String deviceInstallationId;
}
