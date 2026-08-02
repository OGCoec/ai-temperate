package com.example.temperate.web.user.aiconversation.api;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationPreuploadBatch;
import java.util.List;

/**
 * 表示一批会话附件共同的上传会话 ID 及各文件预签名 PUT 信息。
 */
public record AiConversationPreuploadResponse(
        String uploadSessionId,
        List<AiConversationPreuploadFileResponse> files) {

    public static AiConversationPreuploadResponse from(
            AiConversationPreuploadBatch value) {
        return new AiConversationPreuploadResponse(
                value.uploadSessionId(),
                value.files().stream()
                        .map(AiConversationPreuploadFileResponse::from)
                        .toList());
    }
}
