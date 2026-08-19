package com.example.temperate.model.ai.entity;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 该实体是来记录每个外部 Chat Completions HTTP 请求的预扣、实际 Token 用量与最终计费状态，不承载消息或模型正文。
 */
@Getter
@Setter
@NoArgsConstructor
public class AiModelApiUsage {

    private byte[] id;
    private byte[] keyDigest;
    private Long aiModelId;
    private Integer billingStatus;
    private Long promptTokens;
    private Long completionTokens;
    private Long cachedPromptTokens;
    private Long chargedQuotaMinor;
    private String finishReason;
    private String failureCode;
    private OffsetDateTime createdAt;
    private OffsetDateTime settledAt;
}
