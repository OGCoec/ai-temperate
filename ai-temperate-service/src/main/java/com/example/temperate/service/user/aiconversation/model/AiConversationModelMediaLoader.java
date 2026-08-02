package com.example.temperate.service.user.aiconversation.model;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedMedia;
import org.springframework.ai.content.Media;

/**
 * 把 Spring AI 助手媒体安全转换为有界字节内容，集中承担 HTTPS SSRF 与大小防护。
 */
public interface AiConversationModelMediaLoader {

    AiConversationGeneratedMedia load(
            Media media,
            int ordinal,
            long maximumBytes);
}
