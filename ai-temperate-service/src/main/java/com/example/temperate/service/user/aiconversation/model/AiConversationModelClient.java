package com.example.temperate.service.user.aiconversation.model;

import reactor.core.publisher.Flux;

/**
 * 定义将冻结 Prompt 发送到 CLIProxyAPI 并读取包含最终 usage 的流式模型响应边界。
 */
public interface AiConversationModelClient {

    Flux<AiConversationModelChunk> stream(AiConversationModelRequest request);

    String compact(String modelName, String compactionPrompt);
}
