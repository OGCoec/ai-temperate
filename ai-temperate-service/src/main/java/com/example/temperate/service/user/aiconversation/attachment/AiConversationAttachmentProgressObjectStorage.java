package com.example.temperate.service.user.aiconversation.attachment;

import com.aliyun.sdk.service.oss2.progress.ProgressListener;

/**
 * 为生成媒体正式写入 OSS 提供真实字节回调；普通附件存储实现仍可继续只实现基础对象存储接口。
 */
public interface AiConversationAttachmentProgressObjectStorage
        extends AiConversationAttachmentObjectStorage {

    String putPublic(
            String destinationObjectKey,
            byte[] bytes,
            String contentType,
            ProgressListener progressListener);
}
