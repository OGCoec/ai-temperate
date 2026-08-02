package com.example.temperate.service.user.aiconversation.attachment;

import java.util.List;

/**
 * 表示输入附件复制和模型媒体上传的最终结果，并保留可用于数据库失败补偿的正式对象键。
 */
public record AiConversationAttachmentFinalization(
        List<AiConversationAttachment> inputAttachments,
        List<AiConversationAttachment> responseAttachments,
        List<String> createdObjectKeys,
        boolean partialFailure) {

    public AiConversationAttachmentFinalization {
        inputAttachments = List.copyOf(inputAttachments);
        responseAttachments = List.copyOf(responseAttachments);
        createdObjectKeys = List.copyOf(createdObjectKeys);
    }
}
