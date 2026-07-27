package com.example.temperate.service.admin.aimodel.icon.dto;

/**
 * 表示管理员对模型图标名称、描述和外部 URL 的部分修改。
 */
public record AdminAiModelIconPatchCommand(
        AiModelIconPatchField<String> iconName,
        AiModelIconPatchField<String> description,
        AiModelIconPatchField<String> iconUrl) {

    public boolean hasChanges() {
        return iconName.present() || description.present() || iconUrl.present();
    }
}
