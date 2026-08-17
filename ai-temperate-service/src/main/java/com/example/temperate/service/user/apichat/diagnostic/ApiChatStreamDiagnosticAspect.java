package com.example.temperate.service.user.apichat.diagnostic;

import java.util.Objects;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

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
