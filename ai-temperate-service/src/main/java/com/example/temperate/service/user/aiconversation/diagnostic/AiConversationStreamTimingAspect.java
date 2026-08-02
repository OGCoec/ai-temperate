package com.example.temperate.service.user.aiconversation.diagnostic;

import java.util.Objects;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 在模型客户端公开流方法返回后惰性增加订阅终态观察，不建立内部订阅也不读取信号正文。
 */
@Aspect
@Component
public final class AiConversationStreamTimingAspect {

    private final AiConversationStreamTimingDiagnosticService diagnosticService;

    public AiConversationStreamTimingAspect(
            AiConversationStreamTimingDiagnosticService diagnosticService) {
        this.diagnosticService = Objects.requireNonNull(diagnosticService);
    }

    /**
     * 只包装 Flux 返回值；同步建流异常保持原始传播方式，非流返回值不做处理。
     *
     * @param joinPoint 被标记的公开模型流方法
     * @return 原返回值或保持惰性的诊断 Flux
     * @throws Throwable 原方法同步异常
     */
    @Around("@annotation("
            + "com.example.temperate.service.user.aiconversation.diagnostic."
            + "AiConversationStreamTiming)")
    public Object observe(ProceedingJoinPoint joinPoint) throws Throwable {
        Object result = joinPoint.proceed();
        if (!(result instanceof Flux<?> flux)) {
            return result;
        }
        return observeFlux(flux);
    }

    @SuppressWarnings("unchecked")
    private <T> Flux<T> observeFlux(Flux<?> source) {
        return diagnosticService.observeLifecycle((Flux<T>) source);
    }
}
