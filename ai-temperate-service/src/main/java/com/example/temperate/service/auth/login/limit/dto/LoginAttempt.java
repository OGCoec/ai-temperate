package com.example.temperate.service.auth.login.limit.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 表示用于登录限流判断的已规范化主体与设备组合。
 */
@Getter
@RequiredArgsConstructor
public final class LoginAttempt {

    private final String normalizedIdentifier;
    private final String deviceInstallationId;
}
