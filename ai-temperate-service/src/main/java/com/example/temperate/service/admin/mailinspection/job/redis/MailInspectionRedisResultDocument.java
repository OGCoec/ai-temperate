package com.example.temperate.service.admin.mailinspection.job.redis;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import java.util.Objects;

/**
 * 包装单行邮件检查结果并携带稳定 Schema 版本，使 Redis 结果桶可以独立演进。
 */
public record MailInspectionRedisResultDocument(
        int schemaVersion,
        MailInspectionResult result) {

    public static final int SCHEMA_VERSION = 2;

    public MailInspectionRedisResultDocument {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "mail inspection Redis result schema is unsupported");
        }
        Objects.requireNonNull(result, "result must not be null");
    }
}
