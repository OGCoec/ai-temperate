package com.example.temperate.service.user.aiconversation.generation;

/**
 * 定义服务端可信的显式取消来源，客户端不能通过请求体伪造管理员或失联超时来源。
 */
public enum AiConversationGenerationCancelSource {
    USER_STOP,
    ADMIN_CANCEL,
    CLIENT_EXIT_TIMEOUT
}
