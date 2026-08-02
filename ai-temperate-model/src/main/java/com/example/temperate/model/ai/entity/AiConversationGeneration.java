package com.example.temperate.model.ai.entity;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 保存一次与 SSE 观察者解耦的模型生成任务、所有权、取消意图和唯一事实终态。
 */
@Getter
@Setter
@NoArgsConstructor
public class AiConversationGeneration {

    private byte[] id;
    private Long loginIdentityId;
    private byte[] conversationId;
    private byte[] usageId;
    private byte[] idempotencyKeyDigest;
    private Long modelId;
    private Integer generationStatus;
    private Integer observerStatus;
    private Long observerEpoch;
    private String ownerInstanceId;
    private String cancelSource;
    private String terminalType;
    private String terminalReason;
    private Integer terminalVersion;
    private OffsetDateTime createdAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime detachedAt;
    private OffsetDateTime cancelRequestedAt;
    private OffsetDateTime terminalAt;
    private OffsetDateTime settledAt;
    private OffsetDateTime updatedAt;
}
