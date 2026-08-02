package com.example.temperate.model.ai.entity;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 表示一次完整用户输入与最终助手响应组成的持久化会话消息，中断草稿不进入该实体。
 */
@Getter
@Setter
@NoArgsConstructor
public class AiConversationMessage {

    private Long id;
    private byte[] conversationId;
    private String contentText;
    private String contentAttachmentsJson;
    private String contentPartsJson;
    private String questionTokens;
    private String responseAttachmentsJson;
    private OffsetDateTime createdAt;
}
