package com.example.temperate.service.user.aiconversation.response;

import reactor.core.publisher.Mono;

/**
 * 定义用户单次发送动作的会话、上下文、预扣、上游流、结算和压缩总体编排边界。
 */
public interface AiConversationResponseService {

    AiConversationResponseStream respond(AiConversationResponseCommand command);

    Mono<AiConversationResponseStream> respondAsync(
            AiConversationResponseCommand command);
}
