package com.example.temperate.service.user.aiconversation.attachment;

/**
 * 承载单个模型生成媒体的受限字节内容，只有完成上游调用后才会进入正式 OSS 落盘流程。
 */
public record AiConversationGeneratedMedia(
        String fileName,
        String contentType,
        byte[] bytes) {

    public AiConversationGeneratedMedia {
        bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
