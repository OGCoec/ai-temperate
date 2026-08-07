package com.example.temperate.service.user.aiconversation.model.stream.impl;

/**
 * 描述单个图片 SSE 事件经过 Java Mapper 后的受控结果，用于诊断而不改变协议处理行为。
 */
enum OpenAiImagesGenerationMappingOutcome {
    PARTIAL,
    FINAL,
    FAILURE,
    IGNORED,
    DONE,
    EMPTY
}
