package com.example.temperate.web.user.aiconversation.api;

/**
 * 表示直接 SSE 显式 Stop 的脱敏结果，只向浏览器返回有限状态而不暴露内部流和计费数据。
 */
public record AiConversationDirectResponseCancelResponse(String status) {
}
