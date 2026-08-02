package com.example.temperate.service.user.aiconversation.generation;

/**
 * 定义 Worker 通过 PostgreSQL CAS 冻结唯一事实终态和 Payload 证据的本地事务边界。
 */
public interface AiConversationGenerationTerminalService {

    AiConversationGenerationTerminalResult freeze(
            AiConversationGenerationTerminalCommand command);
}
