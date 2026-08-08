package com.example.temperate.service.user.aiconversation.generation.worker;

import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationStage;

/**
 * 定义 Worker 使用短事务领取 Generation 和读取最新控制状态的边界，禁止在模型调用期间持有数据库事务。
 */
public interface AiConversationGenerationControlService {

    AiConversationGenerationClaim claim(byte[] generationId);

    AiConversationGenerationWorkItem load(byte[] generationId);

	void bindContextCursor(
			byte[] generationId,
			String contextGeneration,
			long ephemeralOrdinal);

    void bindUpstreamRequestId(
            byte[] generationId,
            String upstreamRequestId);

	void updateVideoStage(
			byte[] generationId,
			AiConversationVideoGenerationStage stage);
}
