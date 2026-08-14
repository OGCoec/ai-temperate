package com.example.temperate.web.apikey;

import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 该过滤器是来在 JSON 反序列化期间强制执行公开 Chat Completions 请求体字节上限，覆盖 Content-Length 和分块传输两种情况。
 */
public final class ApiChatBodyLimitFilter extends OncePerRequestFilter {

    private final ApiKeyProperties properties;
    private final OpenAiErrorResponseWriter errorWriter;

    public ApiChatBodyLimitFilter(
            ApiKeyProperties properties,
            OpenAiErrorResponseWriter errorWriter) {
        this.properties = Objects.requireNonNull(properties);
        this.errorWriter = Objects.requireNonNull(errorWriter);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/v1/chat/completions".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        int maximumBytes = properties.getRequest().getMaxBodyBytes();
        long contentLength = request.getContentLengthLong();
        if (contentLength > maximumBytes) {
            errorWriter.write(
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    "The request body is too large.",
                    "invalid_request_error",
                    "invalid_request");
            return;
        }
        // 分块请求没有可信 Content-Length，必须包装输入流并在读取第 limit+1 字节时失败。
        filterChain.doFilter(new LimitedRequest(request, maximumBytes), response);
    }

    /** 该异常只携带固定消息，避免把请求内容或容器内部状态带入错误响应。 */
    public static final class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException() {
            super("API chat request body exceeds the configured byte limit");
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final ServletInputStream limitedInputStream;

        private LimitedRequest(HttpServletRequest request, int maximumBytes) throws IOException {
            super(request);
            this.limitedInputStream = new LimitedServletInputStream(
                    request.getInputStream(), maximumBytes);
        }

        @Override
        public ServletInputStream getInputStream() {
            return limitedInputStream;
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null
                    ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(limitedInputStream, charset));
        }
    }

    private static final class LimitedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maximumBytes;
        private long consumed;

        private LimitedServletInputStream(ServletInputStream delegate, long maximumBytes) {
            this.delegate = delegate;
            this.maximumBytes = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                account(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                account(read);
            }
            return read;
        }

        private void account(int read) throws PayloadTooLargeException {
            consumed += read;
            if (consumed > maximumBytes) {
                throw new PayloadTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
