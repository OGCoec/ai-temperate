package com.example.temperate.web.user.aiconversation.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 表示普通用户一次发送动作中的可选文字与最多八个服务端预上传引用。
 */
public record AiConversationInputRequest(
        @Size(max = 65536)
        String text,
        @Size(max = 8)
        List<@Valid AiConversationInputAttachmentRequest> attachments) {

    public AiConversationInputRequest {
        text = text == null ? "" : text;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
