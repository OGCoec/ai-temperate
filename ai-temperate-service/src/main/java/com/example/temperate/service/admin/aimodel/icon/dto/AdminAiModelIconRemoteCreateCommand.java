package com.example.temperate.service.admin.aimodel.icon.dto;

/**
 * 表示管理员登记外部 HTTPS 模型图标所需的输入。
 */
public record AdminAiModelIconRemoteCreateCommand(
        String iconName,
        String iconUrl,
        String description) {
}
