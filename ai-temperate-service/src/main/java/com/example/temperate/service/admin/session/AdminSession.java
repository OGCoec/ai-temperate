package com.example.temperate.service.admin.session;

import java.time.Instant;

/**
 * 表示 Redis 管理员会话 Hash Field 中保存的最小受保护状态。
 *
 * <p>只保存设备摘要和时间元数据，不保存管理员身份、密码、密码哈希或原始 Token。</p>
 */
public record AdminSession(
        int schemaVersion,
        String deviceDigest,
        Instant createdAt,
        Instant lastSeenAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
}
