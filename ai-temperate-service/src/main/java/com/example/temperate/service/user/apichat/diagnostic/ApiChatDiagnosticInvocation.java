package com.example.temperate.service.user.apichat.diagnostic;

/**
 * 该调用对象是来关联一次 AOP 方法进入、返回和异步终止，并把会话作为不可变值传给惰性 Flux 包装器。
 */
public record ApiChatDiagnosticInvocation(
        ApiChatDiagnosticSession session,
        ApiChatDiagnosticStage stage,
        long startedNanos) {
}
