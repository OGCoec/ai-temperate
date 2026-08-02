package com.example.temperate.service.user.aiconversation.diagnostic;

/**
 * 提供只用于持续时间计算的单调纳秒时间，隔离墙上时钟调整并允许确定性测试。
 */
@FunctionalInterface
public interface AiConversationStreamTimingClock {

    long nanoTime();
}
