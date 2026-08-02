package com.example.temperate.service.user.aiconversation.diagnostic;

import org.springframework.stereotype.Component;

/**
 * 使用 JVM 单调时钟为流式诊断提供持续时间基准，不负责生成业务时间戳。
 */
@Component
public final class SystemAiConversationStreamTimingClock
        implements AiConversationStreamTimingClock {

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
}
