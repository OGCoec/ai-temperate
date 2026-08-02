package com.example.temperate.service.user.aiconversation.diagnostic;

/**
 * 负责在 SSE 终止边界生成安全失败分类，具体结构化日志由其 Spring AOP 切面统一记录。
 */
public interface AiConversationStreamFailureDiagnosticService {

    AiConversationStreamFailureClassification diagnose(
            AiConversationStreamFailureContext context,
            Throwable failure);
}
