package com.example.temperate.web.admin.api;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 是管理员 API 的共享异常日志策略，按 HTTP 状态统一分级并仅记录可安全聚合的诊断字段。
 *
 * <p>该组件不负责 HTTP 响应映射，也不会记录异常消息、请求内容或异常堆栈，避免第三方响应与敏感输入进入日志。
 */
@Component
public final class AdminExceptionLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminExceptionLogger.class);
    private static final String TRACE_MDC_KEY = "traceId";
    private static final String ABSENT = "absent";
    private static final String LOG_TEMPLATE =
            "event={} code={} httpStatus={} traceId={} exceptionType={} rootCauseType={}";

    /**
     * 记录已知管理员异常；客户端可恢复拒绝使用 INFO，服务端或外部依赖故障使用 WARN。
     *
     * @param event 稳定且不包含用户输入的事件名称
     * @param code 稳定业务错误码
     * @param httpStatus 已由对应 Advice 确定的响应状态
     * @param failure 被对应 Advice 捕获的异常
     */
    public void logKnown(
            String event,
            String code,
            HttpStatus httpStatus,
            Throwable failure) {
        Objects.requireNonNull(event);
        Objects.requireNonNull(code);
        Objects.requireNonNull(httpStatus);
        Objects.requireNonNull(failure);

        Throwable diagnostic = failure.getCause() == null ? failure : failure.getCause();
        Throwable rootCause = deepestCause(diagnostic);
        String traceId = traceId();

        // 所有日志参数均转换为普通字符串或数值，禁止把 Throwable 作为最后参数触发隐式堆栈输出。
        if (httpStatus.is5xxServerError()) {
            LOGGER.warn(
                    LOG_TEMPLATE,
                    event,
                    code,
                    httpStatus.value(),
                    traceId,
                    diagnostic.getClass().getName(),
                    rootCause.getClass().getName());
            return;
        }
        LOGGER.info(
                LOG_TEMPLATE,
                event,
                code,
                httpStatus.value(),
                traceId,
                diagnostic.getClass().getName(),
                rootCause.getClass().getName());
    }

    private static String traceId() {
        String traceId = MDC.get(TRACE_MDC_KEY);
        return traceId == null || traceId.isBlank() ? ABSENT : traceId;
    }

    private static Throwable deepestCause(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current.getCause() != null
                && visited.add(current)
                && !visited.contains(current.getCause())) {
            current = current.getCause();
        }
        return current;
    }
}
