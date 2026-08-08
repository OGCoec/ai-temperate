package com.example.temperate.service.user.aiconversation.video;

/**
 * 调用独立阿里云 FC 把 xAI 临时视频流式搬运到 OSS，主业务 JVM 只处理小型 JSON。
 */
public interface AiConversationVideoTransferService {

    /**
     * 执行视频搬运并接收 OSS 上传进度；实现必须在 FC 已完成 HEAD 校验后才返回最终结果。
     */
    AiConversationVideoTransferResult transfer(
            AiConversationVideoTransferCommand command,
            AiConversationVideoTransferProgressListener progressListener);

    /**
     * 兼容既有不展示进度的调用入口，避免非生成链路被本次界面能力改造影响。
     */
    default AiConversationVideoTransferResult transfer(
            AiConversationVideoTransferCommand command) {
        return transfer(command, AiConversationVideoTransferProgressListener.noOp());
    }

}
