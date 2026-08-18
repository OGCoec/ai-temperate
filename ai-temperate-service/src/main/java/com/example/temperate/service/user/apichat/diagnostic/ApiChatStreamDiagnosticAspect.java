package com.example.temperate.service.user.apichat.diagnostic;

import com.example.temperate.service.user.apichat.ApiChatCompletionCreation;
import com.example.temperate.service.user.apichat.ApiChatCompletionCreation.HttpJson;
import com.example.temperate.service.user.apichat.ApiChatCompletionCreation.HttpStream;
import java.util.Objects;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 该切面是来统一记录公开 API Chat 的 Controller 与 Service 同步入口，并仅对返回的 Flux 增加惰性生命周期观察。
 */
@Aspect
@Component
public final class ApiChatStreamDiagnosticAspect {

    private final ApiChatStreamDiagnosticService diagnostics;

    public ApiChatStreamDiagnosticAspect(ApiChatStreamDiagnosticService diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    @Around("@annotation(configuration)")
    public Object observe(
            ProceedingJoinPoint joinPoint,
            ApiChatStreamDiagnostic configuration) throws Throwable {
        ApiChatDiagnosticInvocation invocation = diagnostics.enter(
                configuration.value(), joinPoint.getArgs());
        try {
            Object result = joinPoint.proceed();
            diagnostics.returned(invocation, result);
            if (result instanceof Flux<?> flux) {
                return diagnostics.observeLifecycle(flux, invocation);
            }
            if (result instanceof Mono<?> mono) {
                return diagnostics.observeLifecycle(mono, invocation);
            }
            if (result instanceof ApiChatCompletionCreation.Stream stream) {
                return new ApiChatCompletionCreation.Stream(
                        diagnostics.observePreparation(stream.response(), invocation)
                                .map(response -> new HttpStream(
                                        diagnostics.observeLifecycle(
                                                response.body(), invocation),
                                        response.headers())));
            }
            if (result instanceof ApiChatCompletionCreation.Json json) {
                return new ApiChatCompletionCreation.Json(
                        diagnostics.observeLifecycle(json.response(), invocation)
                                .map(response -> new HttpJson(
                                        response.body(), response.headers())));
            }
            invocation.session().summarizeSynchronous(invocation.stage());
            return result;
        } catch (Throwable failure) {
            diagnostics.failed(invocation, failure);
            throw failure;
        } finally {
            diagnostics.close(invocation);
        }
    }
}
