package com.example.temperate.service.user.apiresponse.diagnostic;

import com.example.temperate.service.user.apiresponse.upstream.ApiResponseSseFrame.TerminalKind;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 该服务是来建立 Responses 请求级诊断会话、观察 Mono/Flux 生命周期并记录安全边界，所有包装必须保持原背压和单订阅语义。
 */
public interface ApiResponseStreamDiagnosticService {

    ApiResponseDiagnosticInvocation enter(
            ApiResponseDiagnosticStage stage,
            Object[] arguments);

    void returned(ApiResponseDiagnosticInvocation invocation, Object result);

    void failed(ApiResponseDiagnosticInvocation invocation, Throwable failure);

    void close(ApiResponseDiagnosticInvocation invocation);

    <T> Flux<T> observeLifecycle(
            Flux<T> source,
            ApiResponseDiagnosticInvocation invocation);

    <T> Mono<T> observeLifecycle(
            Mono<T> source,
            ApiResponseDiagnosticInvocation invocation);

    void observeBoundary(
            ApiResponseDiagnosticSession session,
            ApiResponseDiagnosticBoundary boundary,
            long bytes,
            ApiResponseFrameClass frameClass,
            long sequence,
            TerminalKind terminalKind,
            boolean usagePresent);

    void recordFailure(
            ApiResponseDiagnosticSession session,
            ApiResponseFailureStage stage,
            Throwable failure);

    ApiResponseDiagnosticSession currentSession();
}
