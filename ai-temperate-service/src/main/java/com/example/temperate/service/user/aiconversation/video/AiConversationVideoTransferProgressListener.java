package com.example.temperate.service.user.aiconversation.video;

import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaUploadProgress;

/**
 * 接收视频从 FC 搬运到 OSS 时的临时进度；监听器只描述界面反馈，不参与视频最终附件的持久化或状态判定。
 */
@FunctionalInterface
public interface AiConversationVideoTransferProgressListener {

    /**
     * 接收当前搬运阶段的真实 OSS 字节进度或终态。
     */
    void onProgress(AiConversationMediaUploadProgress progress);

    /**
     * 为不需要展示进度的既有调用提供无副作用监听器，保持调用兼容性。
     */
    static AiConversationVideoTransferProgressListener noOp() {
        return progress -> {
            // 不展示上传进度的内部调用无需保留临时状态。
        };
    }
}
