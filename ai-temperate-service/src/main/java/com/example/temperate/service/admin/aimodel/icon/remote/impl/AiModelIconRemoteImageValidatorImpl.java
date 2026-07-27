package com.example.temperate.service.admin.aimodel.icon.remote.impl;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageValidator;
import com.example.temperate.service.admin.aimodel.icon.remote.AiModelIconRemoteImageValidator;
import com.example.temperate.service.admin.aimodel.icon.remote.AiModelIconTrustedOriginRegistry;
import com.example.temperate.service.admin.aimodel.icon.remote.PinnedPublicDnsResolver;
import com.example.temperate.service.admin.aimodel.icon.remote.ValidatedRemoteIcon;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/**
 * 使用固定公网 DNS 解析、有界 GET 和手工重定向验证外部模型图标。
 *
 * <p>每一跳都会重新执行 HTTPS 与公网地址校验，响应体最多读取 2 MiB 加一字节，并由图片验证器核对
 * Content-Type 与真实格式。本实现还保留网络失败的原始异常链，供仅管理员可访问的接口诊断，但不会主动把完整
 * URL 写入业务异常消息或日志字段。
 */
@Component
public final class AiModelIconRemoteImageValidatorImpl
        implements AiModelIconRemoteImageValidator {

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    static final String ACCEPTED_IMAGE_TYPES =
            "image/png,image/jpeg,image/jpg,image/webp,image/gif,"
                    + "image/x-icon,image/vnd.microsoft.icon,image/avif,image/svg+xml";

    private final AiModelIconImageValidator imageValidator;
    private final AiModelIconTrustedOriginRegistry trustedOriginRegistry;
    private final CloseableHttpClient httpClient;

    public AiModelIconRemoteImageValidatorImpl(
            AiModelIconImageValidator imageValidator,
            AiModelIconTrustedOriginRegistry trustedOriginRegistry) {
        this.imageValidator = Objects.requireNonNull(imageValidator);
        this.trustedOriginRegistry =
                Objects.requireNonNull(trustedOriginRegistry);
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PinnedPublicDnsResolver())
                .setMaxConnTotal(4)
                .setMaxConnPerRoute(2)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(3))
                .setResponseTimeout(Timeout.ofSeconds(5))
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
    public ValidatedRemoteIcon validate(String url) {
        URI current = requireHttpsUri(url);
        try {
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                FetchResult result = fetch(current);
                if (result.redirectLocation() == null) {
                    // 只有完成全部重定向后的最终主机能够选择可信官方档位，原始 URL 不能继承信任。
                    validateFetchedImage(
                            result.bytes(),
                            result.contentType(),
                            current);
                    String finalUrl = current.toASCIIString();
                    if (finalUrl.length() > 1024) {
                        throw invalidRedirect(null);
                    }
                    return new ValidatedRemoteIcon(finalUrl);
                }
                if (redirect == MAX_REDIRECTS) {
                    throw invalidRedirect(null);
                }
                current = resolveRedirect(current, result.redirectLocation());
            }
            throw invalidRedirect(null);
        } catch (AiModelIconException exception) {
            throw mapImageValidationFailure(exception);
        } catch (IOException | RuntimeException exception) {
            throw mapTransportFailure(exception);
        }
    }

    /**
     * 使用最终响应 URI 选择 SVG 档位并验证已经有界读取的响应体。
     *
     * <p>该边界独立于网络调用，确保测试可以在不连接外部站点时证明重定向后的最终主机才决定信任。</p>
     */
    void validateFetchedImage(
            byte[] bytes,
            String contentType,
            URI finalUri) {
        imageValidator.validate(
                bytes,
                contentType,
                trustedOriginRegistry.resolve(finalUri.getHost()));
    }

    private FetchResult fetch(URI uri) throws IOException {
        HttpGet request = new HttpGet(uri);
        request.setHeader("Accept", ACCEPTED_IMAGE_TYPES);
        // 每一跳关闭连接复用，确保重定向后的同名主机也重新经过公网 DNS 校验。
        request.setHeader("Connection", "close");
        return httpClient.execute(request, response -> {
            int status = response.getCode();
            if (isRedirect(status)) {
                Header location = response.getFirstHeader("Location");
                if (location == null || location.getValue().isBlank()) {
                    throw invalidRedirect(null);
                }
                return new FetchResult(location.getValue(), null, null);
            }
            if (status >= 300 && status < 400) {
                throw invalidRedirect(null);
            }
            if (status < 200 || status >= 300) {
                throw new AiModelIconException(
                        AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_HTTP_STATUS_INVALID,
                        "Remote AI model icon returned HTTP status " + status + ".");
            }
            HttpEntity entity = response.getEntity();
            Header contentType = response.getFirstHeader("Content-Type");
            if (entity == null
                    || contentType == null
                    || contentType.getValue() == null
                    || contentType.getValue().isBlank()) {
                throw invalidResponse(null);
            }
            long declaredLength = entity.getContentLength();
            if (declaredLength > MAX_BYTES) {
                throw responseTooLarge();
            }
            try (InputStream body = entity.getContent()) {
                byte[] bytes = body.readNBytes(MAX_BYTES + 1);
                if (bytes.length == 0) {
                    throw invalidResponse(null);
                }
                if (bytes.length > MAX_BYTES) {
                    throw responseTooLarge();
                }
                return new FetchResult(null, contentType.getValue(), bytes);
            }
        });
    }

    static URI requireHttpsUri(String value) {
        if (value == null || value.isBlank()) {
            throw invalidRemoteUrl();
        }
        try {
            String normalized = value.trim();
            if (normalized.length() > 1024) {
                throw invalidRemoteUrl();
            }
            URI uri = new URI(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || uri.getPort() == 0
                    || uri.getPort() < -1) {
                throw invalidRemoteUrl();
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw new AiModelIconException(
                    AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_URL_INVALID,
                    "Remote AI model icon URL is invalid.",
                    exception);
        }
    }

    static URI resolveRedirect(URI current, String location) {
        if (current == null || location == null || location.isBlank()) {
            throw invalidRedirect(null);
        }
        try {
            return requireHttpsUri(current.resolve(location).toString());
        } catch (AiModelIconException | IllegalArgumentException exception) {
            throw invalidRedirect(exception);
        }
    }

    static AiModelIconException mapImageValidationFailure(
            AiModelIconException exception) {
        // 远端返回的字节无法解码属于响应无效；格式、安全和解码器错误仍保留各自专用错误码。
        if (exception.code() == AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_INVALID) {
            return new AiModelIconException(
                    AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_RESPONSE_INVALID,
                    "Remote AI model icon returned an invalid image response.",
                    exception);
        }
        return exception;
    }

    /**
     * 沿异常链识别网络阶段，避免 Apache HTTP 客户端的包装异常掩盖真正的 DNS、TLS 或超时原因。
     *
     * <p>分类后的业务异常保留原始异常作为 cause，管理员接口可展示诊断信息；稳定业务消息不包含完整 URL。
     */
    static AiModelIconException mapTransportFailure(Throwable failure) {
        UnknownHostException unknownHost = findCause(failure, UnknownHostException.class);
        if (unknownHost != null) {
            AiModelIconErrorCode code = unknownHost.getCause() instanceof IllegalArgumentException
                    ? AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_HOST_NOT_PUBLIC
                    : AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_DNS_RESOLUTION_FAILED;
            return new AiModelIconException(
                    code,
                    code == AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_HOST_NOT_PUBLIC
                            ? "Remote AI model icon host did not resolve to a public address."
                            : "Remote AI model icon host DNS resolution failed.",
                    failure);
        }
        if (findCause(failure, ConnectTimeoutException.class) != null) {
            return new AiModelIconException(
                    AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_CONNECT_TIMEOUT,
                    "Remote AI model icon connection timed out.",
                    failure);
        }
        if (findCause(failure, SSLHandshakeException.class) != null
                || findCause(failure, SSLPeerUnverifiedException.class) != null
                || findCause(failure, SSLException.class) != null) {
            return new AiModelIconException(
                    AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_TLS_HANDSHAKE_FAILED,
                    "Remote AI model icon TLS handshake failed.",
                    failure);
        }
        if (findCause(failure, SocketTimeoutException.class) != null) {
            return new AiModelIconException(
                    AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_READ_TIMEOUT,
                    "Remote AI model icon response timed out.",
                    failure);
        }
        if (findCause(failure, ConnectException.class) != null
                || findCause(failure, IOException.class) != null) {
            return new AiModelIconException(
                    AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_CONNECT_FAILED,
                    "Remote AI model icon connection failed.",
                    failure);
        }
        return invalidResponse(failure);
    }

    private static <T extends Throwable> T findCause(
            Throwable failure,
            Class<T> expectedType) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (expectedType.isInstance(current)) {
                return expectedType.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean isRedirect(int status) {
        return status == 301
                || status == 302
                || status == 303
                || status == 307
                || status == 308;
    }

    private static AiModelIconException invalidRemoteUrl() {
        return new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_URL_INVALID,
                "Remote AI model icon URL is invalid.");
    }

    private static AiModelIconException invalidRedirect(Throwable cause) {
        return new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_REDIRECT_INVALID,
                "Remote AI model icon redirect is invalid.",
                cause);
    }

    private static AiModelIconException invalidResponse(Throwable cause) {
        return new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_RESPONSE_INVALID,
                "Remote AI model icon response is invalid.",
                cause);
    }

    private static AiModelIconException responseTooLarge() {
        return new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_RESPONSE_TOO_LARGE,
                "Remote AI model icon response exceeds 2 MiB.");
    }

    @PreDestroy
    void close() {
        try {
            httpClient.close();
        } catch (IOException ignored) {
            // 应用关闭阶段无需把 HTTP 客户端关闭失败改写为业务错误。
        }
    }

    private record FetchResult(
            String redirectLocation,
            String contentType,
            byte[] bytes) {
    }
}
