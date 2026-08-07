package com.example.temperate.service.user.aiconversation.image;

import com.example.temperate.service.user.aiconversation.response.AiConversationStreamEvent;
import reactor.core.publisher.Flux;

/**
 * 定义图片预览在当前应用实例内从 Worker 到 SSE Observer 的短暂通道，不提供跨实例恢复或持久化。
 */
public interface AiConversationImagePreviewBroker {

    AiConversationImagePreviewPublishResult publish(
            String generationPublicId,
            AiConversationGeneratedImage image);

    void publishFailure(
            String generationPublicId,
            short outputIndex,
            String reasonCode);

    Flux<AiConversationStreamEvent> events(String generationPublicId);

    /**
     * 标记本实例 Worker 已经结束，并在重连宽限后释放本机临时预览。
     *
     * @param generationPublicId Generation 公共 ID
     */
    void seal(String generationPublicId);

    void release(String generationPublicId);

    static AiConversationImagePreviewBroker noOp() {
        return new AiConversationImagePreviewBroker() {
            @Override
            public AiConversationImagePreviewPublishResult publish(
                    String generationPublicId,
                    AiConversationGeneratedImage image) {
                return AiConversationImagePreviewPublishResult.ignored();
            }

            @Override
            public void publishFailure(
                    String generationPublicId,
                    short outputIndex,
                    String reasonCode) {
            }

            @Override
            public Flux<AiConversationStreamEvent> events(
                    String generationPublicId) {
                return Flux.empty();
            }

            @Override
            public void release(String generationPublicId) {
            }

            @Override
            public void seal(String generationPublicId) {
            }
        };
    }
}
