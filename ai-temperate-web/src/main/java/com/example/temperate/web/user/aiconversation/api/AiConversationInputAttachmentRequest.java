package com.example.temperate.web.user.aiconversation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 表示客户端提交的单个会话附件预上传引用，不允许携带 Bucket、Object Key 或最终 URL。
 */
public record AiConversationInputAttachmentRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{22}$")
        String uploadSessionId,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{38}$")
        String attachmentId,
        @NotBlank @Size(max = 255)
        String fileName,
        @NotBlank @Size(max = 255)
        String contentType,
        @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,8}$")
        String sizeBytes) {
}
