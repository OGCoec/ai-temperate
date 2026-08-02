package com.example.temperate.service.user.aiconversation.compaction.model;

import java.util.List;

/**
 * 定义从管理员启用模型快照构建会话压缩候选集合的读取边界。
 */
public interface AiConversationCompactionModelCatalog {

    List<AiConversationCompactionModelRef> enabledModels();
}
