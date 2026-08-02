package com.example.temperate.service.admin.aimodel.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 承载已通过 Web 层类型校验的 AI 模型字段 Merge Patch。
 *
 * <p>该命令只保存允许编辑的字段及存在标记，不允许把原始 JSON、未知属性或客户端动态 SQL
 * 传入 Service 和 Mapper。</p>
 */
public record AdminAiModelPatchCommand(
        AiModelPatchField<String> modelName,
        AiModelPatchField<String> description,
        AiModelPatchField<String> iconPublicId,
        AiModelPatchField<List<String>> tags,
        AiModelPatchField<String> vendor,
        AiModelPatchField<BigDecimal> inputRatio,
        AiModelPatchField<BigDecimal> cachedInputRatio,
        AiModelPatchField<BigDecimal> outputRatio,
        AiModelPatchField<Long> contextWindowTokens,
        AiModelPatchField<Long> maxOutputTokens,
        AiModelPatchField<List<String>> capabilities) {

    public boolean hasChanges() {
        return modelName.present()
                || description.present()
                || iconPublicId.present()
                || tags.present()
                || vendor.present()
                || inputRatio.present()
                || cachedInputRatio.present()
                || outputRatio.present()
                || contextWindowTokens.present()
                || maxOutputTokens.present()
                || capabilities.present();
    }
}
