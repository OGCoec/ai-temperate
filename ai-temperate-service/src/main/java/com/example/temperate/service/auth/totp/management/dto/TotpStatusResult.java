package com.example.temperate.service.auth.totp.management.dto;

/**
 * 表示当前用户可公开给个人中心的最小 TOTP 启用状态。
 */
public record TotpStatusResult(boolean enabled) {
}
