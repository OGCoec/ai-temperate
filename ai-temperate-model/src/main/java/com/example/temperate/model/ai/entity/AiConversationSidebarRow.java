package com.example.temperate.model.ai.entity;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 承载会话侧栏游标查询所需的最小字段，避免读取与标题列表无关的持久上下文和压缩检查点。
 */
@Getter
@Setter
@NoArgsConstructor
public class AiConversationSidebarRow {

    private byte[] id;
    private String title;
    private Long lastMessageId;
    private OffsetDateTime createdAt;
}
