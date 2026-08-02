package com.example.temperate.service.user.aiconversation.response;

/**
 * 说明一次流式持久化批次为何被刷出，供 Redis 传输诊断关联批大小与触发条件。
 * 该枚举不改变下游 SSE 转发节奏，只描述已有批处理边界。
 */
public enum AiConversationStreamFlushReason {
    SIZE_THRESHOLD,
    TIME_THRESHOLD,
    MAX_CHUNKS,
    TERMINAL,
    MEDIA
}
