package com.example.temperate.service.user.aiconversation.image;

/**
 * 向单个下游 SSE 观察者报告某个图片输出槽位的状态变化，不携带 Prompt、图片字节或签名地址。
 */
public record AiConversationImageOutputStatusData(
        short outputIndex,
        String status,
        String reasonCode) {
}
