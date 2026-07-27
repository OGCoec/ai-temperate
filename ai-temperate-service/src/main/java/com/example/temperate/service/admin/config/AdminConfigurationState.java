package com.example.temperate.service.admin.config;

/**
 * 描述单管理员配置文件在认证入口处可观察到的四种互斥状态。
 */
public enum AdminConfigurationState {
    UNINITIALIZED,
    ACTIVE,
    DISABLED,
    CORRUPT
}
