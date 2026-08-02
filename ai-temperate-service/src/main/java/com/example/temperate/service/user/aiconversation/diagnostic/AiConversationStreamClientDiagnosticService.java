package com.example.temperate.service.user.aiconversation.diagnostic;

/**
 * 接收已认证浏览器对指定 Generation 的流式时间摘要，并负责在归属校验和去重后写入统一诊断日志。
 * 该服务不保存正文，也不参与会话、额度或终态业务决策。
 */
public interface AiConversationStreamClientDiagnosticService {

    void record(
            long userId,
            byte[] generationId,
            AiConversationStreamClientDiagnostic diagnostic);
}
