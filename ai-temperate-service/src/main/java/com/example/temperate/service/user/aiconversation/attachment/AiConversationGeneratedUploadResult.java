package com.example.temperate.service.user.aiconversation.attachment;

import java.util.Objects;

/**
 * 表示一个稳定图片槽位的 OSS 持久化结果，并携带终态失败时可补偿删除的对象键。
 */
public record AiConversationGeneratedUploadResult(
        short outputIndex,
        AiConversationAttachment attachment,
        String createdObjectKey) {

    public AiConversationGeneratedUploadResult {
        if (outputIndex < 0 || outputIndex > 9) {
            throw new IllegalArgumentException("Image output index is out of range.");
        }
        attachment = Objects.requireNonNull(attachment);
        if (attachment.state() == AiConversationAttachmentState.AVAILABLE
                && (createdObjectKey == null || createdObjectKey.isBlank())) {
            throw new IllegalArgumentException(
                    "Available generated attachment requires an object key.");
        }
        if (attachment.state() != AiConversationAttachmentState.AVAILABLE) {
            createdObjectKey = null;
        }
    }

    public boolean successful() {
        return attachment.state() == AiConversationAttachmentState.AVAILABLE;
    }
}
