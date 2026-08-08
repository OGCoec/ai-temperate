package com.example.temperate.service.user.aiconversation.generation.observer;

import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import java.util.Map;

/**
 * 定义 Generation 部分输出快照、单调 revision 与 Redis Pub/Sub 通知边界。
 */
public interface AiConversationGenerationOutputStore {

    long appendDelta(String generationPublicId, String text);

    /**
     * 为一次 Delta 持久化附带脱敏批处理元数据；默认实现保持旧调用方的行为不变。
     */
    default long appendDelta(
            String generationPublicId,
            String text,
            AiConversationStreamTimingContext timingContext,
            Map<String, ?> diagnosticDetails) {
        return appendDelta(generationPublicId, text);
    }

    void publishTerminal(
            String generationPublicId,
            String eventName,
            String dataJson);

    /**
     * 发布不改变权威终态的短生命周期状态事件；重连客户端仍以数据库任务状态为准。
     */
    default void publishEvent(
            String generationPublicId,
            String eventName,
            String dataJson) {
    }

    AiConversationGenerationOutputSnapshot snapshot(String generationPublicId);
}
