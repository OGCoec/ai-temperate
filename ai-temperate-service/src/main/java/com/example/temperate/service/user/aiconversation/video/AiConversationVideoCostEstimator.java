package com.example.temperate.service.user.aiconversation.video;

/**
 * 定义使用冻结官方价格计算视频供应商成本预扣 ticks 的业务边界。
 */
public interface AiConversationVideoCostEstimator {

    long estimateCostTicks(
            AiConversationVideoGenerationOptions options,
            AiConversationVideoPricingProfile pricing);
}
