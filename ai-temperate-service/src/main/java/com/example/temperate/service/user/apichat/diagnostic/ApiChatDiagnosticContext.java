package com.example.temperate.service.user.apichat.diagnostic;

import reactor.util.context.ContextView;

/**
 * 该上下文工具是来在 Reactor 订阅链中传递诊断会话，键和值均为进程内对象，不进入响应、日志或持久化存储。
 */
public final class ApiChatDiagnosticContext {

    public static final String SESSION_KEY =
            ApiChatDiagnosticContext.class.getName() + ".session";

    private ApiChatDiagnosticContext() {
    }

    public static ApiChatDiagnosticSession session(ContextView context) {
        return context.getOrDefault(
                SESSION_KEY, ApiChatDiagnosticSession.disabled());
    }
}
