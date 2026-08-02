package com.example.temperate.web.user.aiconversation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 表示客户端申请单个任意类型附件预上传时声明的文件元数据。
 */
public record AiConversationPreuploadFileRequest(
        @NotBlank @Size(max = 255)
        String fileName,
        @NotBlank @Size(max = 255)
        String contentType,
        @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,8}$")
        String sizeBytes) {
}
