package com.example.temperate.service.user.aiconversation.generation.progress;

/**
 * 把上传进度发送到生成任务的临时输出通道，使浏览器可以通过既有 SSE 接收实时状态。
 */
public interface AiConversationMediaUploadProgressPublisher {

    void publish(String generationPublicId, AiConversationMediaUploadProgress progress);

    static AiConversationMediaUploadProgressPublisher noOp() {
        return (generationPublicId, progress) -> {
        };
    }
}
