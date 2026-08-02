package com.example.temperate.service.user.aiconversation.attachment;

import java.util.List;

/**
 * 表示一次批量预上传会话及其最多八个文件的预签名结果。
 */
public record AiConversationPreuploadBatch(
        String uploadSessionId,
        List<AiConversationPreupload> files) {

    public AiConversationPreuploadBatch {
        files = List.copyOf(files);
    }
}
