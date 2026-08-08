package com.example.temperate.model.ai.enums;

import java.util.Locale;

/**
 * 定义 AI 模型在数据库、HTTP 接口和运行时能力判断中共享的稳定能力代码。
 *
 * <p>媒体输入、生成和编辑是彼此独立的声明，禁止根据模型名称、厂商或其他能力自动推导；新增或删除代码时必须同步
 * PostgreSQL CHECK 约束、管理员前端白名单和缓存版本，避免各层接受范围不一致。</p>
 */
public enum AiModelCapabilityCode {
    CHAT_COMPLETIONS,
    RESPONSES,
    WEB_SEARCH,
    IMAGE_INPUT,
    IMAGE_GENERATION,
    IMAGE_EDIT,
    AUDIO_INPUT,
    AUDIO_GENERATION,
    AUDIO_EDIT,
    VIDEO_INPUT,
    VIDEO_GENERATION,
    VIDEO_EDIT,
    VIDEO_EXTENSION;

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
