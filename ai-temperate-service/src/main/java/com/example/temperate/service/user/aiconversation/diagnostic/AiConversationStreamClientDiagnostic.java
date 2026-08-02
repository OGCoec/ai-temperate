package com.example.temperate.service.user.aiconversation.diagnostic;

/**
 * 承载浏览器在一次 SSE 结束后回传的聚合时间摘要；该对象只允许计数、revision 序号和耗时，
 * 不包含 Prompt、模型正文、认证凭据或页面状态快照。
 */
public record AiConversationStreamClientDiagnostic(
        String usagePublicId,
        String traceId,
        String outcome,
        long responseHeadersMs,
        long firstByteMs,
        long lastNetworkByteMs,
        long firstHeartbeatMs,
        long firstDeltaMs,
        long completedMs,
        long networkReads,
        long networkBytes,
        long parsedEvents,
        long renderedUpdates,
        long renderedTextCharacters,
        long lastDeltaSequence,
        long deltaSequenceGapCount) {
}
