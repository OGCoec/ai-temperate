package com.example.temperate.service.user.aiconversation.compaction.model;

import com.example.temperate.service.user.aiconversation.model.AiModelProvider;

/**
 * 表示一次会话压缩可选择的管理员启用模型引用，冻结供应商、内部 ID 和上游模型名称。
 */
public record AiConversationCompactionModelRef(
        long id,
        AiModelProvider provider,
        String modelName) {

    public AiConversationCompactionModelRef {
        if (id <= 0 || provider == null
                || modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException(
                    "Compaction model reference requires a positive ID and model name.");
        }
        modelName = modelName.trim();
    }

    public AiConversationCompactionModelRef(long id, String modelName) {
        this(id, AiModelProvider.OPENAI, modelName);
    }
}
