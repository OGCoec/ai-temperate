package com.example.temperate.service.user.aiinference.sse;

/**
 * 该事件是来表示从原始 UTF-8 字节流解出的单个 SSE 事件，保留 event 名称和合并后的多行 data。
 */
public record ApiInferenceSseEvent(String eventName, String data) {
}
