package com.example.temperate.service.admin.config;

import java.time.Instant;

/**
 * 向管理员入口暴露配置可用状态和无敏感信息提示，不暴露配置文件内容或绝对路径。
 */
public record AdminConfigurationSnapshot(
        AdminConfigurationState state,
        boolean registrationAllowed,
        boolean loginAllowed,
        String message,
        Instant checkedAt) {
}
