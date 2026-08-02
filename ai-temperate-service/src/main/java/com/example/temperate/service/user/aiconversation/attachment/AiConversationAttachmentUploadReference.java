package com.example.temperate.service.user.aiconversation.attachment;

/**
 * 承载客户端在发送会话时提交的预上传引用，服务端必须重新构造对象路径并通过 HEAD 校验。
 */
public record AiConversationAttachmentUploadReference(
        String uploadSessionId,
        String attachmentId,
        String fileName,
        String contentType,
        long sizeBytes) {
}
