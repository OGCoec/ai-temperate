package com.example.temperate.service.user.aiconversation.diagnostic;

/**
 * 为每个 Generation 的浏览器诊断摘要提供有界去重，防止客户端重试或恶意重复请求放大日志量。
 */
public interface AiConversationStreamClientDiagnosticRateLimitService {

    boolean tryAcquire(String generationPublicId);
}
