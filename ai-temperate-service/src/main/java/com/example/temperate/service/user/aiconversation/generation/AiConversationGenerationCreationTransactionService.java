package com.example.temperate.service.user.aiconversation.generation;

/**
 * 定义 Usage 预扣、Generation 和 Payload 必须原子提交的短 PostgreSQL 事务边界。
 */
public interface AiConversationGenerationCreationTransactionService {

    AiConversationGenerationStart create(AiConversationGenerationCreateCommand command);
}
