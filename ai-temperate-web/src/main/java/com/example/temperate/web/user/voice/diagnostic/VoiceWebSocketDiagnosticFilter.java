package com.example.temperate.web.user.voice.diagnostic;

import com.example.temperate.service.user.voice.diagnostic.VoiceDiagnosticContext;
import com.example.temperate.web.edgeproxy.EdgeProxySignatureVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 在所有安全 Filter 和握手拦截器之外观察公开语音 WebSocket 的完整 HTTP Upgrade 边界。
 *
 * <p>该过滤器只读取请求与最终响应的非敏感形状并建立诊断关联，不包装请求或响应，不读取正文，
 * 不写入响应头、Cookie、状态或正文，也不改变异常传播。它排在安全链之前，是为了确认请求是否在
 * 到达 WebSocket HandshakeInterceptor 前已经被拒绝。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@ConditionalOnProperty(
        prefix = "app.voice",
        name = "enabled",
        havingValue = "true")
public final class VoiceWebSocketDiagnosticFilter extends OncePerRequestFilter {

    public static final String TRACE_MDC_KEY = "traceId";
    public static final String EDGE_RAY_MDC_KEY = "edgeRay";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(VoiceWebSocketDiagnosticFilter.class);
    private static final String VOICE_PATH = "/ws/voice";
    private static final String VOICE_PROTOCOL = "ait-voice-v2";
    private static final String ABSENT = "ABSENT";
    private static final String INVALID = "INVALID";
    private static final int SWITCHING_PROTOCOLS_STATUS = 101;
    private static final int MAX_PROTOCOL_DIAGNOSTIC_LENGTH = 1024;
    private static final int MAX_PROTOCOL_TOKENS = 64;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !VOICE_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        VoiceDiagnosticContext context = new VoiceDiagnosticContext(
                UUID.randomUUID().toString(),
                safeEdgeRay(request.getHeader(EdgeProxySignatureVerifier.RAY_HEADER)));
        request.setAttribute(VoiceDiagnosticContext.ATTRIBUTE, context);

        String previousTraceId = MDC.get(TRACE_MDC_KEY);
        String previousEdgeRay = MDC.get(EDGE_RAY_MDC_KEY);
        MDC.put(TRACE_MDC_KEY, context.traceId());
        MDC.put(EDGE_RAY_MDC_KEY, context.edgeRay());
        long startedNanos = System.nanoTime();
        Throwable failure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                logSummary(request, response, context, failure, startedNanos);
            } catch (RuntimeException ignored) {
                // 诊断日志后端异常不能覆盖握手结果或原始异常；MDC 仍必须在下一层 finally 中恢复。
            } finally {
                restoreMdc(TRACE_MDC_KEY, previousTraceId);
                restoreMdc(EDGE_RAY_MDC_KEY, previousEdgeRay);
            }
        }
    }

    private static void logSummary(
            HttpServletRequest request,
            HttpServletResponse response,
            VoiceDiagnosticContext context,
            Throwable failure,
            long startedNanos) {
        String cookie = request.getHeader("Cookie");
        ProtocolDiagnostic protocol = protocolDiagnostic(
                request.getHeader("Sec-WebSocket-Protocol"));
        boolean selectedProtocolMatched = VOICE_PROTOCOL.equals(
                response.getHeader("Sec-WebSocket-Protocol"));
        String outcome = failure != null
                ? "EXCEPTION"
                : response.getStatus() == SWITCHING_PROTOCOLS_STATUS
                        ? "UPGRADED"
                        : "REJECTED";
        String exceptionType = failure == null
                ? ABSENT
                : safeExceptionType(failure);
        String template = "event=voice_ws_handshake_summary traceId={} edgeRay={} "
                + "platform={} cookieHeaderPresent={} cookieHeaderBytes={} "
                + "upgradeRequested={} protocolHeaderPresent={} protocolTokenCount={} "
                + "voiceV2Present={} status={} selectedProtocolMatched={} "
                + "setCookiePresent={} outcome={} exceptionType={} elapsedMs={}";
        Object[] arguments = {
            context.traceId(),
            context.edgeRay(),
            safePlatform(request.getHeader("X-Client-Platform")),
            cookie != null,
            utf8Length(cookie),
            "websocket".equalsIgnoreCase(safeTrim(request.getHeader("Upgrade"))),
            protocol.present(),
            protocol.tokenCount(),
            protocol.voiceV2Present(),
            response.getStatus(),
            selectedProtocolMatched,
            response.containsHeader("Set-Cookie"),
            outcome,
            exceptionType,
            elapsedMillis(startedNanos)
        };
        if ("UPGRADED".equals(outcome)) {
            LOGGER.info(template, arguments);
        } else {
            LOGGER.warn(template, arguments);
        }
    }

    private static ProtocolDiagnostic protocolDiagnostic(String value) {
        if (value == null || value.isBlank()) {
            return new ProtocolDiagnostic(false, 0, false);
        }
        if (value.length() > MAX_PROTOCOL_DIAGNOSTIC_LENGTH
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return new ProtocolDiagnostic(true, -1, false);
        }
        String[] tokens = value.split(",", -1);
        if (tokens.length > MAX_PROTOCOL_TOKENS) {
            return new ProtocolDiagnostic(true, -1, false);
        }
        boolean voiceV2Present = false;
        for (String token : tokens) {
            if (VOICE_PROTOCOL.equals(token.trim())) {
                voiceV2Present = true;
            }
        }
        return new ProtocolDiagnostic(true, tokens.length, voiceV2Present);
    }

    private static String safeEdgeRay(String value) {
        if (value == null || value.isBlank()) {
            return ABSENT;
        }
        String normalized = value.trim();
        return normalized.length() <= 128
                        && normalized.matches("^[A-Za-z0-9-]+$")
                ? normalized
                : INVALID;
    }

    private static String safePlatform(String value) {
        if (value == null || value.isBlank()) {
            return ABSENT;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return "H5".equals(normalized) || "ANDROID".equals(normalized)
                ? normalized
                : INVALID;
    }

    private static String safeExceptionType(Throwable failure) {
        String type = failure.getClass().getSimpleName();
        return type.matches("^[A-Za-z0-9_$]{1,128}$") ? type : INVALID;
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static void restoreMdc(String key, String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }

    private record ProtocolDiagnostic(
            boolean present,
            int tokenCount,
            boolean voiceV2Present) {}
}
