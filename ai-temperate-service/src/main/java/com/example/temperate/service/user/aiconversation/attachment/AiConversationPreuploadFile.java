package com.example.temperate.service.user.aiconversation.attachment;

/**
 * 表示创建会话附件预上传所需的服务端已解析文件元数据。
 */
public record AiConversationPreuploadFile(
        String fileName,
        String contentType,
        long sizeBytes) {
}
