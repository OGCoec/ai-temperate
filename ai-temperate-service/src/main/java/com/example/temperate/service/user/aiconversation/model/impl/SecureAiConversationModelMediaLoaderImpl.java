package com.example.temperate.service.user.aiconversation.model.impl;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedMedia;
import com.example.temperate.service.user.aiconversation.attachment.config.AiConversationAttachmentProperties;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelMediaLoader;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.ai.content.Media;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * 读取 URI、Resource 或 byte[] 模型媒体，并在每次 DNS 解析和重定向前拒绝非公网目标。
 */
@Service
public final class SecureAiConversationModelMediaLoaderImpl
        implements AiConversationModelMediaLoader {

    private final AiConversationAttachmentProperties properties;
    private final CloseableHttpClient httpClient;

    public SecureAiConversationModelMediaLoaderImpl(
            AiConversationAttachmentProperties properties) {
        this.properties = Objects.requireNonNull(properties);
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new AiConversationPublicDnsResolver())
                .setMaxConnTotal(8)
                .setMaxConnPerRoute(4)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(
                        properties.mediaConnectTimeout().toMillis()))
                .setResponseTimeout(Timeout.ofMilliseconds(
                        properties.mediaReadTimeout().toMillis()))
                .setRedirectsEnabled(false)
                .build();
        this.httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .disableRedirectHandling()
                .build();
    }

    @Override
    public AiConversationGeneratedMedia load(
            Media media,
            int ordinal,
            long maximumBytes) {
        Objects.requireNonNull(media, "media must not be null");
        if (maximumBytes <= 0L || maximumBytes > properties.maxFileBytes()) {
            throw new IllegalArgumentException(
                    "maximumBytes must be within the configured single-file limit");
        }
        String contentType = media.getMimeType() == null
                ? "application/octet-stream"
                : media.getMimeType().toString();
        String fileName = "generated-" + ordinal + extension(contentType);
        Object data = media.getData();
        byte[] bytes;
        try {
            if (data instanceof byte[] raw) {
                if (raw.length == 0 || raw.length > maximumBytes) {
                    return new AiConversationGeneratedMedia(
                            fileName,
                            contentType,
                            new byte[0]);
                }
                bytes = raw.clone();
            } else if (data instanceof Resource resource) {
                try (InputStream input = resource.getInputStream()) {
                    bytes = readBounded(input, maximumBytes);
                }
            } else if (data instanceof URI uri) {
                bytes = download(uri, 0, maximumBytes);
            } else if (data instanceof java.net.URL url) {
                bytes = download(url.toURI(), 0, maximumBytes);
            } else {
                // 未知载体可能在转换时无界分配内存；仅接受可在读取前施加边界的三类明确输入。
                bytes = new byte[0];
            }
        } catch (AiConversationException exception) {
            // 上游媒体单项读取失败不能推翻已经产生费用的完整回答；空字节交给落盘层生成失败占位。
            return new AiConversationGeneratedMedia(
                    fileName,
                    contentType,
                    new byte[0]);
        } catch (Exception exception) {
            return new AiConversationGeneratedMedia(
                    fileName,
                    contentType,
                    new byte[0]);
        }
        if (bytes == null || bytes.length == 0 || bytes.length > maximumBytes) {
            return new AiConversationGeneratedMedia(
                    fileName,
                    contentType,
                    new byte[0]);
        }
        return new AiConversationGeneratedMedia(fileName, contentType, bytes);
    }

    private byte[] download(
            URI uri,
            int redirects,
            long maximumBytes) throws IOException {
        URI safeUri = requireHttpsUri(uri);
        FetchResult result = fetch(safeUri, maximumBytes);
        if (result.redirectLocation() == null) {
            return result.bytes();
        }
        if (redirects >= properties.mediaMaxRedirects()) {
            throw failed("模型媒体重定向次数超过限制。", null);
        }
        URI redirected;
        try {
            redirected = safeUri.resolve(result.redirectLocation());
        } catch (IllegalArgumentException exception) {
            throw failed("模型媒体重定向目标无效。", exception);
        }
        return download(redirected, redirects + 1, maximumBytes);
    }

    private FetchResult fetch(URI uri, long maximumBytes) throws IOException {
        HttpGet request = new HttpGet(uri);
        // 每一跳都关闭连接复用，使相同主机的重定向也必须重新经过建连阶段的公网 DNS Resolver。
        request.setHeader("Connection", "close");
        return httpClient.execute(request, response -> {
            int status = response.getCode();
            if (isRedirect(status)) {
                Header location = response.getFirstHeader("Location");
                if (location == null || location.getValue().isBlank()) {
                    throw failed("模型媒体重定向缺少目标。", null);
                }
                return new FetchResult(location.getValue(), null);
            }
            if (status < 200 || status >= 300) {
                throw failed("模型媒体下载失败。", null);
            }
            HttpEntity entity = response.getEntity();
            if (entity == null) {
                throw failed("模型媒体响应缺少内容。", null);
            }
            long declared = entity.getContentLength();
            if (declared > maximumBytes) {
                throw failed("模型媒体超过单文件大小限制。", null);
            }
            try (InputStream input = entity.getContent()) {
                return new FetchResult(null, readBounded(input, maximumBytes));
            }
        });
    }

    private byte[] readBounded(InputStream input, long maximumBytes) throws IOException {
        byte[] bytes = input.readNBytes(Math.toIntExact(maximumBytes + 1L));
        if (bytes.length > maximumBytes) {
            throw failed("模型媒体超过单文件大小限制。", null);
        }
        return bytes;
    }

    private static URI requireHttpsUri(URI uri) throws IOException {
        if (uri == null
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getPort() == 0
                || uri.getPort() < -1) {
            throw failed("模型媒体只允许无凭据的 HTTPS 地址。", null);
        }
        return uri;
    }

    private static boolean isRedirect(int status) {
        return status == 301
                || status == 302
                || status == 303
                || status == 307
                || status == 308;
    }

    private static String extension(String contentType) {
        String value = contentType.toLowerCase(Locale.ROOT);
        if (value.startsWith("image/")) {
            return "." + subtype(value);
        }
        if (value.startsWith("audio/")) {
            return "." + subtype(value);
        }
        if (value.startsWith("video/")) {
            return "." + subtype(value);
        }
        return ".bin";
    }

    private static String subtype(String contentType) {
        String subtype = contentType.substring(contentType.indexOf('/') + 1)
                .replaceAll("[^a-z0-9]", "");
        return subtype.isEmpty() || subtype.length() > 16 ? "bin" : subtype;
    }

    private static AiConversationException failed(String message, Throwable cause) {
        AiConversationException exception = new AiConversationException(
                AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                message,
                false);
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }

    @PreDestroy
    void close() {
        try {
            httpClient.close();
        } catch (IOException ignored) {
            // 应用关闭阶段无需把连接池关闭失败改写成业务错误。
        }
    }

    private record FetchResult(String redirectLocation, byte[] bytes) {
    }
}
