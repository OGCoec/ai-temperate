package com.example.temperate.service.admin.mailinspection.job.redis;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionPendingItem;
import java.util.List;
import java.util.Objects;

/**
 * 包装 Redis 元数据中的脱敏等待项并携带稳定 Schema，避免裸 JSON 数组失去演进边界。
 */
public record MailInspectionRedisPendingItemsDocument(
        int schemaVersion,
        List<MailInspectionPendingItem> items) {

    public static final int SCHEMA_VERSION = 2;

    public MailInspectionRedisPendingItemsDocument {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "mail inspection pending item schema is unsupported");
        }
        items = List.copyOf(Objects.requireNonNull(
                items,
                "items must not be null"));
    }
}
