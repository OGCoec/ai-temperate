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
 * 该过滤器是来对公开 Chat Completions 与 Responses 请求强制统一字节上限，覆盖 Content-Length 和分块传输且不读取业务 JSON。
 */
public final class ApiInferenceBodyLimitFilter extends OncePerRequestFilter {

    private final ApiKeyProperties properties;
    private final OpenAiErrorResponseWriter errorWriter;

    public ApiInferenceBodyLimitFilter(
            ApiKeyProperties properties,
            OpenAiErrorResponseWriter errorWriter) {
        this.properties = Objects.requireNonNull(properties);
        this.errorWriter = Objects.requireNonNull(errorWriter);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !(ApiKeyV1Paths.CHAT_COMPLETIONS.equals(uri)
                || ApiKeyV1Paths.RESPONSES.equals(uri));
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

    /** 该异常只携带固定消息，供两个公开推理 Controller 映射为一致的 OpenAI JSON 错误。 */
    public static final class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException() {
            super("Public inference request body exceeds the configured byte limit");
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
