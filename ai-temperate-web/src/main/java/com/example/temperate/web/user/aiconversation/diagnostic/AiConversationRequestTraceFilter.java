package com.example.temperate.web.user.aiconversation.diagnostic;

import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为 AI 会话 POST SSE 请求建立服务端 Trace，并接收不参与认证或幂等判断的客户端诊断关联 ID。
 *
 * <p>客户端关联 ID 仅在符合 UUIDv4 时进入请求属性和 MDC；非法值降级为 unavailable，不能改变业务响应。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
@ConditionalOnProperty(
        prefix = "app.ai-conversation.lifecycle-diagnostics",
        name = "enabled",
        havingValue = "true")
public final class AiConversationRequestTraceFilter
        extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String CLIENT_REQUEST_HEADER = "X-AI-Client-Request-Id";
    public static final String TRACE_MDC_KEY = "traceId";
    public static final String CLIENT_REQUEST_MDC_KEY = "aiClientRequestId";
    public static final String STARTED_NANOS_MDC_KEY = "aiRequestStartedNanos";
    public static final String TRACE_ATTRIBUTE =
            AiConversationRequestTraceFilter.class.getName() + ".traceId";
    public static final String CLIENT_REQUEST_ATTRIBUTE =
            AiConversationRequestTraceFilter.class.getName() + ".clientRequestId";
    public static final String STARTED_NANOS_ATTRIBUTE =
            AiConversationRequestTraceFilter.class.getName() + ".startedNanos";
    private static final String UNAVAILABLE = "unavailable";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return path == null
                || !path.startsWith("/api/ai/conversations/")
                || !path.endsWith("/responses");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = existingRequestTrace(request);
        long requestStartedNanos = System.nanoTime();
        String clientRequestId = validUuidV4(
                request.getHeader(CLIENT_REQUEST_HEADER));
        request.setAttribute(TRACE_ATTRIBUTE, traceId);
        request.setAttribute(CLIENT_REQUEST_ATTRIBUTE, clientRequestId);
        request.setAttribute(STARTED_NANOS_ATTRIBUTE, requestStartedNanos);
        response.setHeader(TRACE_HEADER, traceId);

        String previousTraceId = MDC.get(TRACE_MDC_KEY);
        String previousClientRequestId = MDC.get(CLIENT_REQUEST_MDC_KEY);
        String previousStartedNanos = MDC.get(STARTED_NANOS_MDC_KEY);
        MDC.put(TRACE_MDC_KEY, traceId);
        MDC.put(CLIENT_REQUEST_MDC_KEY, clientRequestId);
        MDC.put(STARTED_NANOS_MDC_KEY, Long.toString(requestStartedNanos));
        try {
            filterChain.doFilter(request, response);
        } finally {
            response.setHeader(TRACE_HEADER, traceId);
            restoreMdc(TRACE_MDC_KEY, previousTraceId);
            restoreMdc(CLIENT_REQUEST_MDC_KEY, previousClientRequestId);
            restoreMdc(STARTED_NANOS_MDC_KEY, previousStartedNanos);
        }
    }

    private static String validUuidV4(String value) {
        if (value == null || value.isBlank() || value.length() > 36) {
            return UNAVAILABLE;
        }
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.version() == 4 && uuid.variant() == 2
                    && uuid.toString().equals(value.toLowerCase(java.util.Locale.ROOT))
                    ? uuid.toString() : UNAVAILABLE;
        } catch (IllegalArgumentException exception) {
            return UNAVAILABLE;
        }
    }

    private static String existingRequestTrace(HttpServletRequest request) {
        Object value = request.getAttribute(AuthRequestTraceFilter.TRACE_ATTRIBUTE);
        if (value instanceof String traceId) {
            try {
                return UUID.fromString(traceId).toString();
            } catch (IllegalArgumentException ignored) {
                // 请求属性可能被其他组件污染；无效值只能降级为新的服务端 Trace，不能进入日志关联字段。
            }
        }
        return UUID.randomUUID().toString();
    }

    private static void restoreMdc(String key, String previous) {
        if (previous == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previous);
        }
    }
}
