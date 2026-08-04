package com.example.temperate.service.auth.totp.management.dto;

/**
 * 表示 TOTP 状态修改后的启用值和客户端重新登录要求。
 */
public record TotpStateChangeResult(
        boolean enabled,
        boolean reauthenticationRequired) {
}
