package com.example.temperate.service.auth.login.dto.command;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 承载进入登录服务前的原始登录请求数据。
 *
 * <p>该对象包含明文密码，只能在认证调用链短暂使用，禁止写入日志、缓存、消息或外部响应。</p>
 */
@Getter
@RequiredArgsConstructor
public final class LoginCommand {

    private final String identifier;
    private final String rawPassword;
    private final String deviceInstallationId;
    private final String clientIp;
}
