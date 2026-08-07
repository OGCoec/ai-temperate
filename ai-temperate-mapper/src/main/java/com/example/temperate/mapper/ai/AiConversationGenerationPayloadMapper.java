package com.example.temperate.mapper.ai;

import com.example.temperate.model.ai.entity.AiConversationGenerationPayload;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提供生成输入读取和唯一终态证据一次性冻结的 Payload 持久化契约。
 */
@Mapper
public interface AiConversationGenerationPayloadMapper {

    int insert(AiConversationGenerationPayload payload);

    AiConversationGenerationPayload findByGenerationId(
            @Param("generationId") byte[] generationId);

    AiConversationGenerationPayload findByGenerationIdForUpdate(
            @Param("generationId") byte[] generationId);

    int assignConversationMessageId(
            @Param("generationId") byte[] generationId,
            @Param("conversationMessageId") long conversationMessageId,
            @Param("now") OffsetDateTime now);

    int bindContextCursor(
            @Param("generationId") byte[] generationId,
            @Param("contextGeneration") String contextGeneration,
            @Param("ephemeralOrdinal") long ephemeralOrdinal,
            @Param("now") OffsetDateTime now);

    int deleteByGenerationIds(
            @Param("generationIds") List<byte[]> generationIds);

    int freezeTerminalEvidence(
            @Param("generationId") byte[] generationId,
            @Param("assistantText") String assistantText,
            @Param("assistantAttachmentsJson") String assistantAttachmentsJson,
            @Param("promptTokens") Long promptTokens,
            @Param("completionTokens") Long completionTokens,
            @Param("cachedPromptTokens") Long cachedPromptTokens,
            @Param("reasoningTokens") Long reasoningTokens,
            @Param("providerCostTicks") Long providerCostTicks,
            @Param("meteringEvidenceJson") String meteringEvidenceJson,
            @Param("modelFinishReason") String modelFinishReason,
            @Param("upstreamRequestId") String upstreamRequestId,
            @Param("now") OffsetDateTime now);
}
