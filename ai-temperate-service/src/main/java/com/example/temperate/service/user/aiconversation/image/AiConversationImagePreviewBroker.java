package com.example.temperate.service.user.aiconversation.image;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.response.AiConversationStreamEvent;
import reactor.core.publisher.Flux;

/**
 * 定义图片预览在当前应用实例内从 Worker 到 SSE Observer 的短暂通道，不提供跨实例恢复或持久化。
 */
public interface AiConversationImagePreviewBroker {

    AiConversationImagePreviewPublishResult publish(
            String generationPublicId,
            AiConversationPreparedImagePreview preview);

    void publishFailure(
            String generationPublicId,
            short outputIndex,
            String reasonCode);

    /**
     * 用正式 OSS 附件替换同槽位易失预览并释放其保留字节，供当前连接和本实例重连立即恢复。
     *
     * @param generationPublicId Generation 公共 ID
     * @param outputIndex 图片槽位
     * @param attachment 已可公开读取的正式图片附件
     */
    void publishPersisted(
            String generationPublicId,
            short outputIndex,
            AiConversationAttachment attachment);

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
                    AiConversationPreparedImagePreview preview) {
                return AiConversationImagePreviewPublishResult.ignored();
            }

            @Override
            public void publishFailure(
                    String generationPublicId,
                    short outputIndex,
                    String reasonCode) {
            }

            @Override
            public void publishPersisted(
                    String generationPublicId,
                    short outputIndex,
                    AiConversationAttachment attachment) {
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
