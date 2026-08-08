package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import java.util.Locale;

/**
 * 定义 xAI 视频轮询协议的四种状态，未知值必须视为协议错误而不能静默降级。
 */
public enum XaiVideoStatus {
    PENDING,
    DONE,
    FAILED,
    EXPIRED;

    public static XaiVideoStatus fromUpstream(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("xAI video status is required.");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "xAI video status is unsupported.", failure);
        }
    }

    public boolean terminal() {
        return this != PENDING;
    }
}
