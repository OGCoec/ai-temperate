package com.example.temperate.web.apiresponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 该过滤器是来为 `/v1/responses` 生成安全 Trace ID、写入响应头和同步 MDC；它不读取正文、凭据或输出内容。
 */
public final class ApiResponsesTraceFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String TRACE_MDC_KEY = "apiChatTraceId";
    private static final String TRACE_ATTRIBUTE =
            ApiResponsesTraceFilter.class.getName() + ".traceId";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !"/v1/responses".equals(request.getRequestURI());
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = traceId(request);
        response.setHeader(TRACE_HEADER, traceId);
        String previous = MDC.get(TRACE_MDC_KEY);
        MDC.put(TRACE_MDC_KEY, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(TRACE_MDC_KEY);
            } else {
                MDC.put(TRACE_MDC_KEY, previous);
            }
        }
    }

    private static String traceId(HttpServletRequest request) {
        Object existing = request.getAttribute(TRACE_ATTRIBUTE);
        if (existing instanceof String value) {
            return value;
        }
        String created = UUID.randomUUID().toString();
        request.setAttribute(TRACE_ATTRIBUTE, created);
        return created;
    }
}
