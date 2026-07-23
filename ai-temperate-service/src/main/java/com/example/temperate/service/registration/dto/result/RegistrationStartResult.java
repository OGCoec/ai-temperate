package com.example.temperate.service.registration.dto.result;

import java.time.Instant;

/**
 * 返回新建注册流程的访问凭据、CSRF 材料和到期时间。
 */
public record RegistrationStartResult(
        String registerToken,
        String flowCsrf,
        String challengeHandle,
        Instant expiresAt) {
}
