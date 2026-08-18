package com.example.temperate.service.user.apiresponse.diagnostic;

import com.example.temperate.service.user.apiresponse.ApiResponseCreation;
import java.util.Objects;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 该切面是来为 Responses Controller 与 Service 建立同一诊断会话，并只用惰性操作符观察返回的 Mono、Flux 或 Creation Body。
 */
@Aspect
@Component
public final class ApiResponseStreamDiagnosticAspect {

    private final ApiResponseStreamDiagnosticService diagnostics;

    public ApiResponseStreamDiagnosticAspect(
            ApiResponseStreamDiagnosticService diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    @Around("@annotation(configuration)")
    public Object observe(
            ProceedingJoinPoint joinPoint,
            ApiResponseStreamDiagnostic configuration) throws Throwable {
        ApiResponseDiagnosticInvocation invocation = diagnostics.enter(
                configuration.stage(), joinPoint.getArgs());
        try {
            Object result = joinPoint.proceed();
            diagnostics.returned(invocation, result);
            if (result instanceof Flux<?> flux) {
                return diagnostics.observeLifecycle(flux, invocation);
            }
            if (result instanceof Mono<?> mono) {
                return diagnostics.observeLifecycle(mono, invocation);
            }
            if (result instanceof ApiResponseCreation.Stream stream) {
                return new ApiResponseCreation.Stream(
                        diagnostics.observeLifecycle(stream.body(), invocation));
            }
            if (result instanceof ApiResponseCreation.Json json) {
                return new ApiResponseCreation.Json(
                        diagnostics.observeLifecycle(json.body(), invocation));
            }
            if (invocation.owner()) {
                invocation.session().summarize(
                        reactor.core.publisher.SignalType.ON_COMPLETE, false);
            }
            return result;
        } catch (Throwable failure) {
            diagnostics.failed(invocation, failure);
            throw failure;
        } finally {
            diagnostics.close(invocation);
        }
    }
}
