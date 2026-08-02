package com.example.temperate.service.user.aiconversation.model.stream.impl;

/**
 * 表示从 Responses 字节流中解出的单个上游 SSE 事件，仅在模型适配边界内短暂存在。
 */
record OpenAiResponsesSseEvent(String name, String data) {
}
