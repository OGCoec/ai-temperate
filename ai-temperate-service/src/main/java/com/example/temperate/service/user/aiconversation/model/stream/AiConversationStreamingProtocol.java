package com.example.temperate.service.user.aiconversation.model.stream;

/**
 * 定义普通对话、Responses 联网研究和 Images Generation 分别使用的稳定上游协议策略键。
 */
public enum AiConversationStreamingProtocol {
    CHAT_COMPLETIONS,
    RESPONSES_WEB_SEARCH,
    IMAGES_GENERATION
}
