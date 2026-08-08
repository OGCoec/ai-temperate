package com.example.temperate.model.ai.entity;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 保存 Worker 可恢复的生成输入以及取得唯一终态权后一次性冻结的回答、Token 或成本证据。
 */
@Getter
@Setter
@NoArgsConstructor
public class AiConversationGenerationPayload {

    private byte[] generationId;
    private String inputText;
    private String inputAttachmentsJson;
    private Integer reasoningEffort;
    private Integer meteringBasis;
    private String assistantText;
    private String assistantAttachmentsJson;
    private Long conversationMessageId;
    private String contextGeneration;
    private Long ephemeralOrdinal;
    private Long promptTokens;
    private Long completionTokens;
    private Long cachedPromptTokens;
    private Long reasoningTokens;
    private Long providerCostTicks;
    private String meteringEvidenceJson;
    private String modelFinishReason;
    private String upstreamRequestId;
    private OffsetDateTime updatedAt;
}
