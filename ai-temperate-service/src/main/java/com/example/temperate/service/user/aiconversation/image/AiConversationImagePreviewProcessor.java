package com.example.temperate.service.user.aiconversation.image;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 定义原始生成图片到有界 SSE 预览的派生边界，压缩失败只放弃预览而不改变原图持久化结果。
 */
public interface AiConversationImagePreviewProcessor {

    /**
     * 小图直接保留原字节，大图在独立执行器中派生缩略图；任何派生失败都以空结果降级。
     *
     * @param image 上游已经完成解码的原始图片事件
     * @return 异步有界预览，空值表示只继续 OSS 链路
     */
    CompletableFuture<Optional<AiConversationPreparedImagePreview>> prepare(
            AiConversationGeneratedImage image);

    static AiConversationImagePreviewProcessor noOp() {
        return ignored -> CompletableFuture.completedFuture(Optional.empty());
    }
}
