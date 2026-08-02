package com.example.temperate.service.user.aiconversation.compaction.model;

/**
 * 表示一次会话压缩可选择的管理员启用模型引用，只携带内部 ID 和上游模型名称。
 */
public record AiConversationCompactionModelRef(long id, String modelName) {

    public AiConversationCompactionModelRef {
        if (id <= 0 || modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException(
                    "Compaction model reference requires a positive ID and model name.");
        }
        modelName = modelName.trim();
    }
}
