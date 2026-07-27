package com.example.temperate.service.admin.aimodel.icon.dto;

import java.time.LocalDate;

/**
 * 表示对管理员公开的模型图标资源，不暴露内部数据库 ID 和 OSS Object Key。
 */
public record AiModelIconResult(
        String publicId,
        String iconName,
        String iconUrl,
        String description,
        LocalDate createdAt,
        LocalDate updatedAt) {
}
