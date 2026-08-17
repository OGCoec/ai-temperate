package com.example.temperate.service.user.apichat.diagnostic;

import java.util.function.Function;
import java.util.function.ToLongFunction;
import reactor.core.publisher.Flux;

/**
 * 该服务是来建立公开 API Key 流诊断会话、传递 Reactor 上下文并观察终止信号，所有包装必须保持原流的背压和单订阅语义。
 */
public interface ApiChatStreamDiagnosticService {

    ApiChatDiagnosticInvocation enter(ApiChatDiagnosticStage stage, Object[] arguments);

    void returned(ApiChatDiagnosticInvocation invocation, Object result);

    void failed(ApiChatDiagnosticInvocation invocation, Throwable failure);

    void close(ApiChatDiagnosticInvocation invocation);

    <T> Flux<T> observeLifecycle(Flux<T> source, ApiChatDiagnosticInvocation invocation);

    <T> Flux<T> observeBoundary(
            Flux<T> source,
            ApiChatDiagnosticBoundary boundary,
            ToLongFunction<T> byteCounter,
            Function<T, ApiChatFrameKind> kindClassifier);
}
