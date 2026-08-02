package com.example.temperate.service.user.aiconversation.generation;

/**
 * 定义浏览器观察者是否连接；DETACHED 只描述展示连接，禁止被解释为取消或资金终态。
 */
public enum AiConversationGenerationObserverStatus {
    ATTACHED(0),
    DETACHED(1);

    private final int code;

    AiConversationGenerationObserverStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
