package com.example.temperate.service.user.aiconversation.context;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentUploadReference;
import java.util.List;

/**
 * 表示一段可进入上下文的文字与通用附件；客户端预上传引用必须先校验并转换后才能进入模型或 Redis。
 */
public record AiConversationContent(
        String text,
        List<AiConversationAttachment> attachments,
        List<AiConversationAttachmentUploadReference> uploadReferences) {

    public AiConversationContent {
        text = text == null ? "" : text;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        uploadReferences = uploadReferences == null
                ? List.of()
                : List.copyOf(uploadReferences);
    }

    public AiConversationContent(
            String text,
            List<AiConversationAttachment> attachments) {
        this(text, attachments, List.of());
    }

    public AiConversationContent validated(
            List<AiConversationAttachment> validatedAttachments) {
        return new AiConversationContent(text, validatedAttachments, List.of());
    }
}
