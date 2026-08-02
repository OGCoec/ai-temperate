package com.example.temperate.service.user.aiconversation.response;

/**
 * 定义一次 AI 对话是否向 Responses 上游提供或强制使用联网搜索工具。
 *
 * <p>OFF 保持现有 Chat Completions 链路；AUTO 和 REQUIRED 仅描述用户意图，模型能力与全局开关仍由服务端校验。
 */
public enum AiConversationWebSearchMode {
    OFF,
    AUTO,
    REQUIRED
}
