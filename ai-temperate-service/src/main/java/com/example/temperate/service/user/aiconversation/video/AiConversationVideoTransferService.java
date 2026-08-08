package com.example.temperate.service.user.aiconversation.video;

/**
 * 调用独立阿里云 FC 把 xAI 临时视频流式搬运到 OSS，主业务 JVM 只处理小型 JSON。
 */
public interface AiConversationVideoTransferService {

    AiConversationVideoTransferResult transfer(
            AiConversationVideoTransferCommand command);
}
