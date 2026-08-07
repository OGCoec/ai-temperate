package com.example.temperate.service.user.aiconversation.image;

/**
 * 描述一次本机预览发布是否被接受、是否可供重连重放以及当时的在线观察者数量。
 *
 * @param accepted Broker 是否接受本次发布
 * @param retained 是否保留为对应输出槽位的最新重连预览
 * @param observerCount 发布时的在线观察者数量
 */
public record AiConversationImagePreviewPublishResult(
        boolean accepted,
        boolean retained,
        int observerCount) {

    public AiConversationImagePreviewPublishResult {
        if (observerCount < 0) {
            throw new IllegalArgumentException(
                    "observerCount must not be negative");
        }
    }

    public static AiConversationImagePreviewPublishResult ignored() {
        return new AiConversationImagePreviewPublishResult(false, false, 0);
    }
}
