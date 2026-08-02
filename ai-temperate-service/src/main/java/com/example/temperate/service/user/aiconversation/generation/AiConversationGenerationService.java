package com.example.temperate.service.user.aiconversation.generation;

import com.example.temperate.service.user.aiconversation.response.AiConversationResponseCommand;
import java.util.List;
import java.util.UUID;

/**
 * 定义异步 Generation 的预扣创建、幂等恢复、用户归属查询和活动任务查询边界。
 */
public interface AiConversationGenerationService {

    AiConversationGenerationStart create(AiConversationResponseCommand command);

    AiConversationGenerationView getOwned(long userId, byte[] generationId);

    AiConversationGenerationView getOwnedByIdempotency(long userId, UUID idempotencyKey);

    List<AiConversationGenerationView> listActiveOwned(long userId);
}
