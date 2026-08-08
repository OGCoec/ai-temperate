package com.example.temperate.service.user.aiconversation.attachment;

import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaUploadProgress;

/**
 * 接收单个生成图片上传任务的临时进度，调用方负责将其发布到对应 Generation 的 SSE 通道。
 */
@FunctionalInterface
public interface AiConversationGeneratedUploadProgressListener {

    void onProgress(AiConversationMediaUploadProgress progress);

    static AiConversationGeneratedUploadProgressListener noOp() {
        return progress -> {
        };
    }
}
