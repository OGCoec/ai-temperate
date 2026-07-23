package com.example.temperate.service.auth.login.limit.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 表示用于登录风控判断的已规范化主体、设备与客户端 IP 组合。
 */
@Getter
@RequiredArgsConstructor
public final class LoginAttempt {

    private final String normalizedIdentifier;
    private final String deviceInstallationId;
    private final String canonicalClientIp;
}
