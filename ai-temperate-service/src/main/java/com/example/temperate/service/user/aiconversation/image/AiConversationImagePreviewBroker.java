package com.example.temperate.service.user.aiconversation.image;

import com.example.temperate.service.user.aiconversation.response.AiConversationStreamEvent;
import reactor.core.publisher.Flux;

/**
 * 定义图片预览在当前应用实例内从 Worker 到 SSE Observer 的短暂通道，不提供跨实例恢复或持久化。
 */
public interface AiConversationImagePreviewBroker {

    void publish(String generationPublicId, AiConversationGeneratedImage image);

    Flux<AiConversationStreamEvent> events(String generationPublicId);

    void release(String generationPublicId);

    static AiConversationImagePreviewBroker noOp() {
        return new AiConversationImagePreviewBroker() {
            @Override
            public void publish(
                    String generationPublicId,
                    AiConversationGeneratedImage image) {
            }

            @Override
            public Flux<AiConversationStreamEvent> events(
                    String generationPublicId) {
                return Flux.empty();
            }

            @Override
            public void release(String generationPublicId) {
            }
        };
    }
}
