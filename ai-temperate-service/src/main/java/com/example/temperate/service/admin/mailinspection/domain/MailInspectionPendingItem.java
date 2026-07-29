package com.example.temperate.service.admin.mailinspection.domain;

import java.util.Objects;

/**
 * 表示重启恢复任务中仍安全保存在 RabbitMQ 的脱敏待处理行，只向管理员暴露行号和脱敏邮箱。
 */
public record MailInspectionPendingItem(
        int lineNumber,
        String maskedEmail) {

    public MailInspectionPendingItem {
        if (lineNumber < 1) {
            throw new IllegalArgumentException(
                    "lineNumber must be positive");
        }
        Objects.requireNonNull(maskedEmail, "maskedEmail must not be null");
    }
}
