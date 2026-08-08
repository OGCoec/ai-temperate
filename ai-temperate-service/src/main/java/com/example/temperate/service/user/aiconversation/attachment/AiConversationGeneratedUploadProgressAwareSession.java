package com.example.temperate.service.user.aiconversation.attachment;

/**
 * 允许图片 Generation Worker 在会话创建后绑定上传进度观察者，避免附件服务反向依赖生成输出通道。
 */
public interface AiConversationGeneratedUploadProgressAwareSession
        extends AiConversationGeneratedUploadSession {

    void setProgressListener(AiConversationGeneratedUploadProgressListener listener);
}
