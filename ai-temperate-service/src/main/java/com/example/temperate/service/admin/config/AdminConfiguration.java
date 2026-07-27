package com.example.temperate.service.admin.config;

import java.time.Instant;

/**
 * 表示隐藏 YAML 中唯一管理员的规范化身份、密码哈希和配置元数据。
 *
 * <p>该对象只允许在服务端文件边界内使用，禁止进入 Redis、RabbitMQ、日志或 HTTP 响应。</p>
 */
public record AdminConfiguration(
        int schemaVersion,
        AdminStatus status,
        String email,
        String countryIso2,
        String phoneE164,
        String passwordHash,
        Instant createdAt,
        Instant updatedAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    @Override
    public String toString() {
        return "AdminConfiguration[redacted]";
    }
}
