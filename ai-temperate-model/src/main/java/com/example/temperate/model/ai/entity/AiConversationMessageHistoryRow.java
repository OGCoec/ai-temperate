package com.example.temperate.model.ai.entity;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 承载会话历史一次联查得到的完整消息、Usage 和模型快照，避免按消息执行 N+1 查询。
 */
@Getter
@Setter
@NoArgsConstructor
public class AiConversationMessageHistoryRow {

    private Long messageId;
    private byte[] conversationId;
    private String contentText;
    private String contentAttachmentsJson;
    private String questionTokens;
    private String responseAttachmentsJson;
    private OffsetDateTime createdAt;
    private byte[] usageId;
    private Long aiModelId;
    private String modelName;
    private Long promptTokens;
    private Long cachedPromptTokens;
    private Long completionTokens;
    private Long reasoningTokens;
    private Long chargedQuotaMinor;
    private String finishReason;
}
