package com.example.temperate.service.user.apichat.diagnostic;

/**
 * 该枚举是来标识公开 Chat Completions 请求经过的同步入口，便于确认异常发生在 Controller 还是业务编排阶段。
 */
public enum ApiChatDiagnosticStage {
    HTTP_CONTROLLER,
    COMPLETION_SERVICE
}
