package com.example.temperate.model.ai.entity;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 表示用户 AI 会话的持久状态，承载侧栏快照和最近一次持久上下文压缩检查点。
 */
@Getter
@Setter
@NoArgsConstructor
public class AiConversation {

    private byte[] id;
    private Long loginIdentityId;
    private Boolean active;
    private String title;
    private Long lastMessageId;
    private Long lastCompactedMessageId;
    private String compactedContextJson;
    private OffsetDateTime createdAt;
}
