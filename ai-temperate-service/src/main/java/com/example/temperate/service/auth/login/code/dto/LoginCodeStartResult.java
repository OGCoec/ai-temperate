package com.example.temperate.service.auth.login.code.dto;

import java.time.Instant;

/**
 * 返回新建登录验证码流程的访问凭据及其到期时间。
 */
public record LoginCodeStartResult(
        String loginFlowToken,
        String challengeHandle,
        Instant expiresAt) {
}
