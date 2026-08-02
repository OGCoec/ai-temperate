package com.example.temperate.service.user.aiconversation.generation.observer;

import com.example.temperate.service.user.aiconversation.response.AiConversationStreamEvent;
import reactor.core.publisher.Flux;

/**
 * 表示一次具有唯一 observerEpoch 的 SSE 观察会话、所属 Usage 公共 ID 和快照加实时事件流，
 * 供 Web 响应头和诊断日志使用安全关联键而不暴露内部数据库 ID。
 */
public record AiConversationGenerationObserverSession(
        String generationPublicId,
        String usagePublicId,
        long observerEpoch,
        Flux<AiConversationStreamEvent> events) {
}
