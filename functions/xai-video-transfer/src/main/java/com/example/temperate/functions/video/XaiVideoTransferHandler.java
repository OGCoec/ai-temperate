package com.example.temperate.functions.video;

import com.aliyun.fc.runtime.Context;
import com.aliyun.fc.runtime.Credentials;
import com.aliyun.fc.runtime.StreamRequestHandler;
import com.aliyun.sdk.service.oss2.credentials.CredentialsProvider;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;
import com.example.temperate.functions.video.dto.FcSignedVideoRequest;
import com.example.temperate.functions.video.dto.FcVideoRequest;
import com.example.temperate.functions.video.dto.VideoProbeRequest;
import com.example.temperate.functions.video.dto.VideoProbeResponse;
import com.example.temperate.functions.video.dto.VideoTransferRequest;
import com.example.temperate.functions.video.dto.VideoTransferResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 验证主业务 HMAC 后分发 transfer 或 probe 操作，并确保响应始终只包含小型元数据。
 */
public final class XaiVideoTransferHandler implements StreamRequestHandler {

    private static final int MAXIMUM_REQUEST_BYTES = 128 * 1024;
    private static final long MAXIMUM_CLOCK_SKEW_SECONDS = 60L;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FcInvocationCodec invocationCodec =
            new FcInvocationCodec(objectMapper);
    private final VideoTransferConfiguration configuration =
            VideoTransferConfiguration.fromEnvironment();
    private final VideoSourceStream videoSource = new VideoSourceStream(
            new VideoSourceUrlPolicy(configuration.allowedSourceHosts()));
    private final VideoStreamRelay relay = new VideoStreamRelay();

    @Override
    public void handleRequest(
            InputStream input,
            OutputStream output,
            Context context) throws IOException {
        byte[] body = input.readNBytes(MAXIMUM_REQUEST_BYTES + 1);
        if (body.length == 0 || body.length > MAXIMUM_REQUEST_BYTES) {
            throw new IOException("FC video request size is invalid.");
        }
        FcInvocationCodec.DecodedInvocation invocation =
                invocationCodec.decode(body);
        FcSignedVideoRequest signed = invocation.request();
        requireAuthentic(signed);
        FcVideoRequest request = objectMapper.treeToValue(
                signed.request(), FcVideoRequest.class);
        Object response;
        try {
            if (request.operation() == null) {
                throw new IllegalArgumentException(
                        "FC video operation is unsupported.");
            }
            switch (request.operation()) {
                case "transfer":
                    response = transfer(objectMapper.treeToValue(
                            request.payload(), VideoTransferRequest.class), context);
                    break;
                case "probe":
                    response = probe(objectMapper.treeToValue(
                            request.payload(), VideoProbeRequest.class));
                    break;
                default:
                    throw new IllegalArgumentException(
                            "FC video operation is unsupported.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("FC video operation was interrupted.", exception);
        } catch (RuntimeException exception) {
            throw new IOException("FC video operation failed.", exception);
        }
        invocationCodec.writeResponse(
                output, response, invocation.httpTrigger());
    }

    private VideoTransferResponse transfer(
            VideoTransferRequest request,
            Context context)
            throws IOException, InterruptedException {
        requireCommonRequest(
                request.sourceUrl(),
                request.expectedContentType(),
                request.maximumBytes());
        if (request.transferId() == null
                || !request.transferId().matches("^[A-Za-z0-9_-]{38}$")
                || request.targetObjectKey() == null
                || !request.targetObjectKey().startsWith(
                        configuration.objectPrefix())) {
            throw new IllegalArgumentException("FC video transfer request is invalid.");
        }
        try (VideoSourceStream.OpenedVideo source = videoSource.open(
                        request.sourceUrl(),
                        request.expectedContentType(),
                        request.maximumBytes());
                OssMultipartUploader uploader = new OssMultipartUploader(
                        configuration,
                        request.targetObjectKey(),
                        requireOssCredentials(context))) {
            return relay.relay(source, uploader, request.maximumBytes());
        }
    }

    private CredentialsProvider requireOssCredentials(Context context)
            throws IOException {
        if (context == null) {
            throw new IOException("FC execution context is unavailable.");
        }
        Credentials executionCredentials = context.getExecutionCredentials();
        if (executionCredentials == null
                || isBlank(executionCredentials.getAccessKeyId())
                || isBlank(executionCredentials.getAccessKeySecret())
                || isBlank(executionCredentials.getSecurityToken())) {
            throw new IOException("FC RAM execution credentials are unavailable.");
        }
        // 仅把本次 FC 执行角色产生的短期 STS 三元组交给 OSS SDK，禁止读取或保存长期 AccessKey。
        return new StaticCredentialsProvider(
                executionCredentials.getAccessKeyId(),
                executionCredentials.getAccessKeySecret(),
                executionCredentials.getSecurityToken());
    }

    private VideoProbeResponse probe(VideoProbeRequest request)
            throws IOException, InterruptedException {
        requireCommonRequest(
                request.sourceUrl(),
                request.expectedContentType(),
                request.maximumBytes());
        try (VideoSourceStream.OpenedVideo source = videoSource.open(
                request.sourceUrl(),
                request.expectedContentType(),
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
        videoSourcePolicyCheck(sourceUrl);
    }

    private void videoSourcePolicyCheck(String sourceUrl) {
        // 在建立 HTTP 连接前重复执行 URL 与 DNS 安全校验，禁止通过请求直接选择私网来源。
        new VideoSourceUrlPolicy(configuration.allowedSourceHosts())
                .requireAllowed(sourceUrl);
    }

    private void requireAuthentic(FcSignedVideoRequest signed) throws IOException {
        if (signed == null
                || signed.timestamp() == null
                || signed.nonce() == null
                || !signed.nonce().matches("^[A-Za-z0-9_-]{22}$")
                || signed.signature() == null
                || signed.request() == null) {
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
        byte[] expected = hmac(
                signed.timestamp(), signed.nonce(), signed.request().toString());
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
