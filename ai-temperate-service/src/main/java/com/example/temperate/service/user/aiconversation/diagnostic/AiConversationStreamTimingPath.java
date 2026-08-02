package com.example.temperate.service.user.aiconversation.diagnostic;

/**
 * 标识时序诊断所属的独立订阅或传输链路，避免同一 Usage 的 Worker、SSE Observer、
 * Servlet 和浏览器日志被误认为同一个 Reactor 上下文。
 */
public enum AiConversationStreamTimingPath {
    DIRECT_RESPONSE,
    ASYNC_GENERATION_WORKER,
    ASYNC_GENERATION_OBSERVER,
    SERVLET_SSE_RESPONSE,
    BROWSER_CLIENT
}
