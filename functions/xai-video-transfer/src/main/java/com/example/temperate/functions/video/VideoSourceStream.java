package com.example.temperate.functions.video;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * 使用禁用重定向的 JDK HTTP 客户端执行一次视频 GET，并在返回流前校验状态、类型和声明长度。
 */
public final class VideoSourceStream {

    private final HttpClient client;
    private final VideoSourceUrlPolicy urlPolicy;

    public VideoSourceStream(VideoSourceUrlPolicy urlPolicy) {
        this.urlPolicy = Objects.requireNonNull(urlPolicy);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public OpenedVideo open(
            String sourceUrl,
            String expectedContentType,
            long maximumBytes) throws IOException, InterruptedException {
        URI uri = urlPolicy.requireAllowed(sourceUrl);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(14))
                .header("Accept", "video/mp4")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Video source returned a non-success status.");
        }
        String contentType = response.headers()
                .firstValue("Content-Type")
                .orElse("")
                .toLowerCase(Locale.ROOT);
        if (!contentType.startsWith(
                expectedContentType.toLowerCase(Locale.ROOT))) {
            response.body().close();
            throw new IOException("Video source content type is invalid.");
        }
        long declaredLength = response.headers()
                .firstValueAsLong("Content-Length")
                .orElse(-1L);
        if (declaredLength > maximumBytes) {
            response.body().close();
            throw new IOException("Video source exceeds the configured limit.");
        }
        return new OpenedVideo(response.body(), contentType, declaredLength);
    }

    /**
     * 保存已校验的单次响应流和非敏感响应元数据，关闭该对象会立即关闭网络连接。
     */
    public static final class OpenedVideo implements AutoCloseable {

        private final InputStream stream;
        private final String contentType;
        private final long declaredLength;

        public OpenedVideo(
                InputStream stream,
                String contentType,
                long declaredLength) {
            this.stream = Objects.requireNonNull(stream);
            this.contentType = contentType;
            this.declaredLength = declaredLength;
        }

        public InputStream stream() {
            return stream;
        }

        public String contentType() {
            return contentType;
        }

        public long declaredLength() {
            return declaredLength;
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }
}
