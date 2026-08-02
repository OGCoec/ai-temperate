package com.example.temperate.web.user.aiconversation.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 表示一次批量会话附件预上传申请，单条消息最多申请八个文件。
 */
public record AiConversationPreuploadRequest(
        @NotEmpty @Size(max = 8)
        List<@Valid AiConversationPreuploadFileRequest> files) {

    public AiConversationPreuploadRequest {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
