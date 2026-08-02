package com.example.temperate.web.user.aiconversation.diagnostic;

import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingClock;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingPath;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTransportDiagnosticService;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 观察 AI SSE 实际交给 Servlet/Tomcat 的 write 与 flush 时刻，以区分事件就绪和网络写出阶段。
 * 包装器不缓存、不修改响应字节；记录内容只限事件名、revision、字节数和公共关联标识。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
@ConditionalOnProperty(
        prefix = "app.ai-conversation.stream-diagnostics",
        name = "enabled",
        havingValue = "true")
public final class AiConversationServletStreamTimingFilter
        extends OncePerRequestFilter {

    private static final Pattern GENERATION_EVENTS_PATH = Pattern.compile(
            "^/api/ai/conversations/generations/([A-Za-z0-9_-]{22})/events$");
    private static final Pattern REVISION_PATTERN = Pattern.compile(
            "\\\"revision\\\"\\s*:\\s*(\\d{1,19})");
    private static final int MAX_METADATA_LINE_BYTES = 512;
    private static final String UNAVAILABLE = "unavailable";

    private final AiConversationStreamTransportDiagnosticService diagnostics;
    private final AiConversationStreamTimingClock clock;

    public AiConversationServletStreamTimingFilter(
            AiConversationStreamTransportDiagnosticService diagnostics,
            AiConversationStreamTimingClock clock) {
        this.diagnostics = diagnostics;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        boolean responseStream = "POST".equalsIgnoreCase(request.getMethod())
                && path.startsWith("/api/ai/conversations/")
                && path.endsWith("/responses");
        return !responseStream && !GENERATION_EVENTS_PATH.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = traceId(request);
        // 异步 Observer 会在 Controller 返回前建立自己的时序上下文；即使生命周期诊断关闭，
        // 也要在这一小段请求线程内提供同一个 Trace，避免 SSE 写出与 Observer 无法关联。
        request.setAttribute(AiConversationRequestTraceFilter.TRACE_ATTRIBUTE, traceId);
        String previousTraceId = MDC.get(AiConversationRequestTraceFilter.TRACE_MDC_KEY);
        MDC.put(AiConversationRequestTraceFilter.TRACE_MDC_KEY, traceId);
        response.setHeader(AiConversationRequestTraceFilter.TRACE_HEADER, traceId);
        String generationPublicId = generationPublicId(request.getRequestURI());
        AiConversationStreamTimingContext context = new AiConversationStreamTimingContext(
                traceId,
                UNAVAILABLE,
                UNAVAILABLE,
                UNAVAILABLE,
                AiConversationStreamTimingPath.SERVLET_SSE_RESPONSE,
                clock.nanoTime());
        TimingResponseWrapper wrapped = new TimingResponseWrapper(
                response,
                diagnostics,
                context,
                request.getRequestURI(),
                generationPublicId);
        try {
            try {
                filterChain.doFilter(request, wrapped);
            } catch (IOException | ServletException failure) {
                wrapped.complete("ERROR", failure);
                throw failure;
            } catch (RuntimeException failure) {
                wrapped.complete("ERROR", failure);
                throw failure;
            }
            if (request.isAsyncStarted()) {
                request.getAsyncContext().addListener(new CompletionListener(wrapped));
            } else {
                wrapped.complete("COMPLETE", null);
            }
        } finally {
            restoreMdc(previousTraceId);
        }
    }

    private static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(AiConversationRequestTraceFilter.TRACE_ATTRIBUTE);
        if (value instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }
        String mdcTraceId = MDC.get(AiConversationRequestTraceFilter.TRACE_MDC_KEY);
        return mdcTraceId == null || mdcTraceId.isBlank()
                ? UUID.randomUUID().toString() : mdcTraceId;
    }

    private static String generationPublicId(String path) {
        Matcher matcher = GENERATION_EVENTS_PATH.matcher(path == null ? "" : path);
        return matcher.matches() ? matcher.group(1) : UNAVAILABLE;
    }

    private static void restoreMdc(String previousTraceId) {
        if (previousTraceId == null) {
            MDC.remove(AiConversationRequestTraceFilter.TRACE_MDC_KEY);
        } else {
            MDC.put(AiConversationRequestTraceFilter.TRACE_MDC_KEY, previousTraceId);
        }
    }

    private static final class CompletionListener implements AsyncListener {
        private final TimingResponseWrapper response;

        private CompletionListener(TimingResponseWrapper response) {
            this.response = response;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            response.complete("COMPLETE", null);
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            response.complete("TIMEOUT", event.getThrowable());
        }

        @Override
        public void onError(AsyncEvent event) {
            response.complete("ERROR", event.getThrowable());
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }

    private static final class TimingResponseWrapper extends HttpServletResponseWrapper {
        private final AiConversationStreamTransportDiagnosticService diagnostics;
        private final AiConversationStreamTimingContext context;
        private final String requestUri;
        private final String generationPublicId;
        private final AtomicBoolean completed = new AtomicBoolean();
        private final StringBuilder metadataLine = new StringBuilder();
        private ServletOutputStream outputStream;
        private PrintWriter writer;
        private long pendingBytes;
        private long totalBytes;
        private boolean firstWrite = true;
        private String activeEventType = "unknown";
        private long activeRevision = -1L;
        private String flushedEventType = "unknown";
        private long flushedRevision = -1L;

        private TimingResponseWrapper(
                HttpServletResponse response,
                AiConversationStreamTransportDiagnosticService diagnostics,
                AiConversationStreamTimingContext context,
                String requestUri,
                String generationPublicId) {
            super(response);
            this.diagnostics = diagnostics;
            this.context = context;
            this.requestUri = requestUri;
            this.generationPublicId = generationPublicId;
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (writer != null) {
                throw new IllegalStateException("SSE response writer was already requested.");
            }
            if (outputStream == null) {
                outputStream = new TimingServletOutputStream(
                        super.getOutputStream(), this);
            }
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (outputStream != null) {
                throw new IllegalStateException("SSE response output stream was already requested.");
            }
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(
                        getOutputStream(), StandardCharsets.UTF_8), false);
            }
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            super.flushBuffer();
            flushed();
        }

        private synchronized void wrote(byte[] bytes, int offset, int length) {
            if (length <= 0) {
                return;
            }
            pendingBytes += length;
            totalBytes += length;
            parseMetadata(bytes, offset, length);
            if (firstWrite) {
                firstWrite = false;
                record("ai_stream_servlet_write", pendingBytes, true);
            }
        }

        private synchronized void flushed() {
            if (pendingBytes <= 0L) {
                return;
            }
            record("ai_stream_servlet_flush", pendingBytes, false);
            pendingBytes = 0L;
        }

        private void complete(String outcome, Throwable failure) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            synchronized (this) {
                flushed();
                Map<String, Object> details = details(totalBytes, false);
                details.put("statusCode", getStatus());
                details.put("eventType", outcome);
                if (failure != null) {
                    details.put("failureType", failure.getClass().getName());
                }
                diagnostics.record(context, "ai_stream_servlet_complete", details);
            }
        }

        private void record(String event, long bytes, boolean first) {
            Map<String, Object> details = details(bytes, first);
            diagnostics.record(context, event, details);
        }

        private Map<String, Object> details(long bytes, boolean first) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("generationPublicId", effectiveGenerationPublicId());
            details.put("usagePublicId", headerPublicId("X-AI-Usage-Id", 1, 64));
            details.put("requestUri", requestUri);
            details.put("bytes", bytes);
            details.put("firstWrite", first);
            details.put("eventType", flushedEventType);
            if (flushedRevision > 0L) {
                details.put("revision", flushedRevision);
            }
            return details;
        }

        private String effectiveGenerationPublicId() {
            return UNAVAILABLE.equals(generationPublicId)
                    ? headerPublicId("X-AI-Generation-Id", 22, 22)
                    : generationPublicId;
        }

        private String headerPublicId(String name, int minimumLength, int maximumLength) {
            String value = getHeader(name);
            return value != null && value.matches(
                    "^[A-Za-z0-9_-]{" + minimumLength + "," + maximumLength + "}$")
                    ? value : UNAVAILABLE;
        }

        private void parseMetadata(byte[] bytes, int offset, int length) {
            for (int index = offset; index < offset + length; index++) {
                int value = bytes[index] & 0xFF;
                if (value == '\n') {
                    acceptMetadataLine(metadataLine.toString());
                    metadataLine.setLength(0);
                } else if (value != '\r' && metadataLine.length() < MAX_METADATA_LINE_BYTES) {
                    metadataLine.append((char) value);
                }
            }
        }

        private void acceptMetadataLine(String line) {
            if (line.isEmpty()) {
                flushedEventType = activeEventType;
                flushedRevision = activeRevision;
                activeEventType = "unknown";
                activeRevision = -1L;
                return;
            }
            if (line.startsWith("event:")) {
                activeEventType = safeEventType(line.substring("event:".length()));
                return;
            }
            if (line.startsWith("data:")) {
                Matcher matcher = REVISION_PATTERN.matcher(line);
                if (matcher.find()) {
                    try {
                        activeRevision = Long.parseLong(matcher.group(1));
                    } catch (NumberFormatException ignored) {
                        activeRevision = -1L;
                    }
                }
            }
        }

        private static String safeEventType(String value) {
            String normalized = value == null ? "" : value.trim();
            return normalized.matches("[A-Za-z_-]{1,32}") ? normalized : "unknown";
        }
    }

    private static final class TimingServletOutputStream extends ServletOutputStream {
        private final ServletOutputStream delegate;
        private final TimingResponseWrapper owner;

        private TimingServletOutputStream(
                ServletOutputStream delegate,
                TimingResponseWrapper owner) {
            this.delegate = delegate;
            this.owner = owner;
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            delegate.setWriteListener(writeListener);
        }

        @Override
        public void write(int value) throws IOException {
            delegate.write(value);
            owner.wrote(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            delegate.write(bytes);
            owner.wrote(bytes, 0, bytes.length);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            delegate.write(bytes, offset, length);
            owner.wrote(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
            owner.flushed();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
            owner.flushed();
        }
    }
}
