package com.example.temperate.service.user.aiconversation.compaction;

import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * 定义压缩阈值复核、Redis 单飞入队和硬容量非阻塞等待的业务边界。
 */
public interface AiConversationCompactionCoordinator {

    AiConversationCompactionRequestResult requestOwned(
            long userId,
            byte[] conversationId,
            String conversationPublicId,
            String modelPublicId,
            UUID idempotencyKey,
            AiConversationCompactionTrigger trigger);

    AiConversationCompactionRequestResult request(
            byte[] conversationId,
            String conversationPublicId,
            long modelId,
            AiConversationCompactionTrigger trigger);

    Mono<AiConversationCompactionOperation> awaitTerminal(
            String conversationPublicId,
            String operationPublicId);
}
