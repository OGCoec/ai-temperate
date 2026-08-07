package com.example.temperate.service.user.aiconversation.image;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import java.util.Objects;

/**
 * 表示单个图片槽位已经获得正式 OSS 附件，可在整批 completed 前提前通知当前 SSE 观察者。
 */
public record AiConversationImagePersistedData(
        short outputIndex,
        AiConversationAttachment attachment) {

    public AiConversationImagePersistedData {
        if (outputIndex < 0 || outputIndex > 9) {
            throw new IllegalArgumentException("Image output index is out of range.");
        }
        attachment = Objects.requireNonNull(attachment);
    }
}
