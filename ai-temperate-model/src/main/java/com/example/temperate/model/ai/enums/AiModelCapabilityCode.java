package com.example.temperate.model.ai.enums;

import java.util.Locale;

/**
 * 定义第一版 AI 模型允许声明的稳定能力大类代码。
 *
 * <p>枚举值用于描述模型支持的文本、图片、视频或音频 API 大类，同时作为数据库和 API 的白名单；
 * 新增能力必须同步更新建表约束，避免应用与数据库接受范围不一致。</p>
 */
public enum AiModelCapabilityCode {
    CHAT_COMPLETIONS,
    RESPONSES,
    IMAGE,
    VIDEO,
    AUDIO;

    public static AiModelCapabilityCode fromExternalCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AI model capability code is required.");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported AI model capability code.", exception);
        }
    }
}
