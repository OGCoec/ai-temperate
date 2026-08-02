package com.example.temperate.service.user.aiconversation.generation;

/**
 * 提供观察者代际的无状态比较，确保旧连接结束事件和旧延迟消息不会覆盖新连接。
 */
public final class AiConversationObserverEpoch {

    private AiConversationObserverEpoch() {
    }

    public static boolean matches(long candidateEpoch, long currentEpoch) {
        return candidateEpoch == currentEpoch;
    }
}
