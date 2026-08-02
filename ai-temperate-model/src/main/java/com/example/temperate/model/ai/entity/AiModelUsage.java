package com.example.temperate.model.ai.entity;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 表示一次上游模型 HTTP/SSE 调用的核心 Token 用量、额度结果和结算状态持久化实体。
 */
@Getter
@Setter
@NoArgsConstructor
public class AiModelUsage {

    private byte[] id;
    private Long loginIdentityId;
    private Long aiModelId;
    private Integer billingStatus;
    private Long promptTokens;
    private Long completionTokens;
    private Long cachedPromptTokens;
    private Long reasoningTokens;
    private Long chargedQuotaMinor;
    private String finishReason;
    private String failureCode;
    private OffsetDateTime createdAt;
    private OffsetDateTime settledAt;
}
