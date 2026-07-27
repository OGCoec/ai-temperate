package com.example.temperate.service.admin.aimodel.icon.dto;

/**
 * 表示管理员上传本地模型图标所需的元数据和有界文件内容。
 */
public record AdminAiModelIconUploadCommand(
        String iconName,
        String description,
        byte[] bytes,
        String contentType) {

    public AdminAiModelIconUploadCommand {
        bytes = bytes == null ? null : bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes == null ? null : bytes.clone();
    }
}
