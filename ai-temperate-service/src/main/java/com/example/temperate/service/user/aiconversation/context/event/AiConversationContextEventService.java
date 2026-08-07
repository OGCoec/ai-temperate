package com.example.temperate.service.user.aiconversation.context.event;

import reactor.core.publisher.Flux;

/**
 * 定义按需观察上下文用量和压缩状态以及发布用量 revision 的业务边界。
 */
public interface AiConversationContextEventService {

    Flux<AiConversationContextEvent> observe(
            long userId,
            byte[] conversationId,
            String conversationPublicId,
            String modelPublicId,
            long afterRevision);

    long publishUsage(String conversationPublicId, long contextRevision);
}
