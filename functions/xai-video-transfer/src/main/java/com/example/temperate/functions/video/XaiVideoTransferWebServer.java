package com.example.temperate.functions.video;

import com.aliyun.sdk.service.oss2.credentials.CredentialsProvider;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;
import com.example.temperate.functions.video.dto.FcSignedVideoRequest;
import com.example.temperate.functions.video.dto.FcVideoRequest;
import com.example.temperate.functions.video.dto.VideoProbeRequest;
import com.example.temperate.functions.video.dto.VideoProbeResponse;
import com.example.temperate.functions.video.dto.VideoTransferRequest;
import com.example.temperate.functions.video.dto.VideoTransferResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 为 FC Web Function 提供轻量 HTTP 入口，校验主服务 HMAC 请求后执行视频探测或搬运，并在 NDJSON 模式下持续输出真实 OSS 字节进度。
 *
 * <p>该类只负责 HTTP、鉴权与安全边界编排；源 URL 校验、分片上传、补偿删除及 OSS HEAD 校验仍分别由既有协作类完成。</p>
 */
public final class XaiVideoTransferWebServer {

    private static final int MAXIMUM_REQUEST_BYTES = 128 * 1024;
    private static final long MAXIMUM_CLOCK_SKEW_SECONDS = 60L;
    private static final String NDJSON_RESPONSE_MODE = "ndjson-v1";
    private static final String TEMPORARY_ACCESS_KEY_ID =
            "ALIBABA_CLOUD_ACCESS_KEY_ID";
    private static final String TEMPORARY_ACCESS_KEY_SECRET =
            "ALIBABA_CLOUD_ACCESS_KEY_SECRET";
    private static final String TEMPORARY_SECURITY_TOKEN =
            "ALIBABA_CLOUD_SECURITY_TOKEN";

    private final ObjectMapper objectMapper;
    private final VideoTransferConfiguration configuration;
    private final VideoSourceStream videoSource;
    private final VideoStreamRelay relay;

    /**
     * 使用部署环境创建 HTTP 服务依赖，供自定义运行时主入口调用。
     */
    public XaiVideoTransferWebServer() {
        this(new ObjectMapper(), VideoTransferConfiguration.fromEnvironment(),
                new VideoStreamRelay());
    }

    XaiVideoTransferWebServer(
            ObjectMapper objectMapper,
            VideoTransferConfiguration configuration,
            VideoStreamRelay relay) {
        this.objectMapper = objectMapper;
        this.configuration = configuration;
        this.videoSource = new VideoSourceStream(
                new VideoSourceUrlPolicy(configuration.allowedSourceHosts()));
        this.relay = relay;
    }

    /**
     * 启动 FC Web Function 所需的 9000 端口 HTTP Server；平台给出的监听端口优先，以保证本地调试和云端行为一致。
     */
    public static void main(String[] args) throws IOException {
        XaiVideoTransferWebServer application = new XaiVideoTransferWebServer();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(resolvePort()), 0);
        server.createContext("/", application::handle);
        // 函数实例并发固定为 1，使用默认执行器可避免额外线程池持有请求级状态。
        server.setExecutor(null);
        server.start();
    }

    private static int resolvePort() {
        String configured = System.getenv("FC_CUSTOM_LISTEN_PORT");
        if (configured == null || configured.isBlank()) {
            return 9000;
        }
        try {
            int port = Integer.parseInt(configured);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("FC custom listener port is invalid.");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("FC custom listener port is invalid.", exception);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, errorResponse("AI_VIDEO_TRANSFER_METHOD_NOT_ALLOWED"));
                return;
            }
            FcVideoRequest request = decodeAndAuthenticate(exchange.getRequestBody());
            if ("probe".equals(request.operation())) {
                VideoProbeResponse response = probe(objectMapper.treeToValue(
                        request.payload(), VideoProbeRequest.class));
                writeJson(exchange, 200, response);
                return;
            }
            if (!"transfer".equals(request.operation())) {
                writeJson(exchange, 400, errorResponse("AI_VIDEO_TRANSFER_INVALID_REQUEST"));
                return;
            }
            VideoTransferRequest transferRequest = objectMapper.treeToValue(
                    request.payload(), VideoTransferRequest.class);
            if (NDJSON_RESPONSE_MODE.equals(request.responseMode())) {
                transferNdjson(exchange, transferRequest);
                return;
            }
            VideoTransferResponse response = transfer(
                    transferRequest, VideoTransferProgressListener.noOp());
            writeJson(exchange, 200, response);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writeJsonIfPossible(exchange, 500,
                    errorResponse("AI_VIDEO_OSS_TRANSFER_FAILED"));
        } catch (Exception exception) {
            // 响应绝不透传源地址、对象 Key、临时凭据或 SDK 异常，避免把内部边界暴露给调用方。
            writeJsonIfPossible(exchange, 400,
                    errorResponse("AI_VIDEO_OSS_TRANSFER_FAILED"));
        } finally {
            exchange.close();
        }
    }

    private FcVideoRequest decodeAndAuthenticate(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAXIMUM_REQUEST_BYTES + 1);
        if (bytes.length == 0 || bytes.length > MAXIMUM_REQUEST_BYTES) {
            throw new IOException("FC video request size is invalid.");
        }
        FcSignedVideoRequest signed = objectMapper.readValue(bytes,
                FcSignedVideoRequest.class);
        requireAuthentic(signed);
        FcVideoRequest request = objectMapper.treeToValue(
                signed.request(), FcVideoRequest.class);
        if (request == null || request.operation() == null || request.payload() == null) {
            throw new IOException("FC video request payload is invalid.");
        }
        return request;
    }

    private void transferNdjson(
            HttpExchange exchange,
            VideoTransferRequest request) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/x-ndjson; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, 0);
        AtomicLong sequence = new AtomicLong();
        try (OutputStream output = exchange.getResponseBody()) {
            VideoTransferNdjsonWriter writer = new VideoTransferNdjsonWriter(
                    output, objectMapper);
            try {
                VideoTransferResponse result = transfer(request,
                        new VideoTransferProgressListener() {
                            @Override
                            public void uploading(long transferredBytes, Long totalBytes) {
                                writer.progress(sequence.incrementAndGet(),
                                        transferredBytes, totalBytes);
                            }

                            @Override
                            public void verifying(long transferredBytes, Long totalBytes) {
                                writer.verifying(sequence.incrementAndGet(),
                                        transferredBytes, totalBytes);
                            }
                        });
                writer.completed(sequence.incrementAndGet(), result);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                writer.failed(sequence.incrementAndGet(),
                        VideoTransferFailureCode.safeCode(exception));
            } catch (Exception exception) {
                // NDJSON 已经开始时只能写入稳定失败帧，不能改写已提交的 HTTP 状态或泄漏底层异常。
                writer.failed(sequence.incrementAndGet(),
                        VideoTransferFailureCode.safeCode(exception));
            }
        }
    }

    private VideoTransferResponse transfer(
            VideoTransferRequest request,
            VideoTransferProgressListener progressListener)
            throws IOException, InterruptedException {
        requireCommonRequest(request.sourceUrl(), request.expectedContentType(),
                request.maximumBytes());
        if (request.transferId() == null
                || !request.transferId().matches("^[A-Za-z0-9_-]{38}$")
                || request.targetObjectKey() == null
                || !request.targetObjectKey().startsWith(configuration.objectPrefix())) {
            throw new IllegalArgumentException("FC video transfer request is invalid.");
        }
        // 先读取 FC 角色的短期凭据，缺失时立即返回阶段码，避免无意义地打开上游视频流。
        CredentialsProvider credentials = requireOssCredentials();
        VideoSourceStream.OpenedVideo source;
        try {
            source = videoSource.open(
                    request.sourceUrl(), request.expectedContentType(),
                    request.maximumBytes());
        } catch (InterruptedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new VideoTransferFailureException(
                    VideoTransferFailureCode.SOURCE_OPEN_FAILED, exception);
        }
        try (VideoSourceStream.OpenedVideo openedSource = source) {
            OssMultipartUploader uploader;
            try {
                uploader = new OssMultipartUploader(
                        configuration, request.targetObjectKey(), credentials);
            } catch (Exception exception) {
                throw new VideoTransferFailureException(
                        VideoTransferFailureCode.OSS_MULTIPART_INIT_FAILED,
                        exception);
            }
            try (OssMultipartUploader openedUploader = uploader) {
                progressListener.uploading(0L,
                        openedSource.declaredLength() > 0L
                                ? openedSource.declaredLength() : null);
                return relay.relay(
                        openedSource, openedUploader,
                        request.maximumBytes(), progressListener);
            }
        }
    }

    private CredentialsProvider requireOssCredentials() throws IOException {
        String accessKeyId = System.getenv(TEMPORARY_ACCESS_KEY_ID);
        String accessKeySecret = System.getenv(TEMPORARY_ACCESS_KEY_SECRET);
        String securityToken = System.getenv(TEMPORARY_SECURITY_TOKEN);
        if (isBlank(accessKeyId) || isBlank(accessKeySecret)
                || isBlank(securityToken)) {
            throw new VideoTransferFailureException(
                    VideoTransferFailureCode.OSS_CREDENTIALS_UNAVAILABLE,
                    null);
        }
        // 仅把 FC 执行角色注入的短期 STS 三元组交给 OSS SDK，禁止读取、记录或保存长期 AccessKey。
        return new StaticCredentialsProvider(accessKeyId, accessKeySecret,
                securityToken);
    }

    private VideoProbeResponse probe(VideoProbeRequest request)
            throws IOException, InterruptedException {
        requireCommonRequest(request.sourceUrl(), request.expectedContentType(),
                request.maximumBytes());
        try (VideoSourceStream.OpenedVideo source = videoSource.open(
                request.sourceUrl(), request.expectedContentType(),
                request.maximumBytes())) {
            return relay.probe(source.stream(), request.maximumBytes());
        }
    }

    private void requireCommonRequest(
            String sourceUrl,
            String expectedContentType,
            long maximumBytes) {
        if (sourceUrl == null
                || !"video/mp4".equalsIgnoreCase(expectedContentType)
                || maximumBytes <= 0L
                || maximumBytes > configuration.maximumBytes()) {
            throw new IllegalArgumentException("FC video request boundary is invalid.");
        }
        // 在建立 HTTP 连接前再次执行 URL 与 DNS 安全校验，防止请求直接选择私网或非白名单来源。
        new VideoSourceUrlPolicy(configuration.allowedSourceHosts())
                .requireAllowed(sourceUrl);
    }

    private void requireAuthentic(FcSignedVideoRequest signed) throws IOException {
        if (signed == null || signed.timestamp() == null || signed.nonce() == null
                || !signed.nonce().matches("^[A-Za-z0-9_-]{22}$")
                || signed.signature() == null || signed.request() == null) {
            throw new IOException("FC video request authentication is invalid.");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(signed.timestamp());
        } catch (NumberFormatException exception) {
            throw new IOException("FC video request timestamp is invalid.", exception);
        }
        if (Math.abs(Instant.now().getEpochSecond() - timestamp)
                > MAXIMUM_CLOCK_SKEW_SECONDS) {
            throw new IOException("FC video request has expired.");
        }
        byte[] expected = hmac(signed.timestamp(), signed.nonce(),
                signed.request().toString());
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(signed.signature());
        } catch (IllegalArgumentException exception) {
            throw new IOException("FC video request signature is invalid.", exception);
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IOException("FC video request signature is invalid.");
        }
    }

    private byte[] hmac(String timestamp, String nonce, String requestJson)
            throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    configuration.hmacSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return mac.doFinal((timestamp + "\n" + nonce + "\n" + requestJson)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IOException("FC video HMAC is unavailable.", exception);
        }
    }

    private void writeJsonIfPossible(
            HttpExchange exchange,
            int status,
            Object response) throws IOException {
        if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
            writeJson(exchange, status, response);
        }
    }

    private void writeJson(HttpExchange exchange, int status, Object response)
            throws IOException {
        byte[] body = objectMapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type",
                "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static java.util.Map<String, String> errorResponse(String errorCode) {
        return java.util.Map.of("errorCode", errorCode);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
