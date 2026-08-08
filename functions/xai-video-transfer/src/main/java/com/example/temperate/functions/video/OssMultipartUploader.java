package com.example.temperate.functions.video;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.OperationOptions;
import com.aliyun.sdk.service.oss2.credentials.CredentialsProvider;
import com.aliyun.sdk.service.oss2.models.AbortMultipartUploadRequest;
import com.aliyun.sdk.service.oss2.models.CompleteMultipartUpload;
import com.aliyun.sdk.service.oss2.models.CompleteMultipartUploadRequest;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.HeadObjectRequest;
import com.aliyun.sdk.service.oss2.models.InitiateMultipartUploadRequest;
import com.aliyun.sdk.service.oss2.models.Part;
import com.aliyun.sdk.service.oss2.models.UploadPartRequest;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 使用 FC Context 提供的 RAM 短期 STS 执行单次尝试的 OSS Multipart Upload，并在失败时显式 Abort 未完成分片。
 */
public final class OssMultipartUploader
        implements VideoPartUploader, AutoCloseable {

    private static final OperationOptions ONE_ATTEMPT = OperationOptions.newBuilder()
            .retryMaxAttempts(1)
            .build();

    private final VideoTransferConfiguration configuration;
    private final String objectKey;
    private final OSSClient client;
    private final List<Part> parts = new ArrayList<>();
    private String uploadId;
    private boolean completed;

    public OssMultipartUploader(
            VideoTransferConfiguration configuration,
            String objectKey,
            CredentialsProvider credentialsProvider) {
        this.configuration = Objects.requireNonNull(configuration);
        this.objectKey = requireObjectKey(objectKey, configuration.objectPrefix());
        this.client = OSSClient.newBuilder()
                .region(configuration.region())
                .endpoint(configuration.endpoint())
                .connectTimeout(Duration.ofSeconds(10))
                .readWriteTimeout(Duration.ofMinutes(15))
                .credentialsProvider(Objects.requireNonNull(credentialsProvider))
                .build();
        this.uploadId = client.initiateMultipartUpload(
                        InitiateMultipartUploadRequest.newBuilder()
                                .bucket(configuration.bucket())
                                .key(this.objectKey)
                                .contentType("video/mp4")
                                .build(),
                        ONE_ATTEMPT)
                .initiateMultipartUpload()
                .uploadId();
    }

    @Override
    public void uploadPart(long partNumber, byte[] bytes, int length) {
        if (completed
                || partNumber < 1L
                || length <= 0
                || length > bytes.length) {
            throw new IllegalArgumentException("OSS video part is invalid.");
        }
        String eTag = client.uploadPart(
                        UploadPartRequest.newBuilder()
                                .bucket(configuration.bucket())
                                .key(objectKey)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .body(BinaryData.fromStream(
                                        new ByteArrayInputStream(bytes, 0, length),
                                        (long) length))
                                .build(),
                        ONE_ATTEMPT)
                .eTag();
        parts.add(Part.newBuilder()
                .partNumber(partNumber)
                .eTag(eTag)
                .build());
    }

    @Override
    public VideoPartUploader.StoredObject complete(long expectedBytes) {
        if (completed || parts.isEmpty()) {
            throw new IllegalStateException("OSS video upload has no parts.");
        }
        client.completeMultipartUpload(
                CompleteMultipartUploadRequest.newBuilder()
                        .bucket(configuration.bucket())
                        .key(objectKey)
                        .uploadId(uploadId)
                        .forbidOverwrite(true)
                        .objectAcl("public-read")
                        .completeMultipartUpload(CompleteMultipartUpload.newBuilder()
                                .parts(List.copyOf(parts))
                                .build())
                        .build(),
                ONE_ATTEMPT);
        completed = true;
        var head = client.headObject(
                HeadObjectRequest.newBuilder()
                        .bucket(configuration.bucket())
                        .key(objectKey)
                        .build(),
                ONE_ATTEMPT);
        if (head.contentLength() == null
                || head.contentLength() != expectedBytes
                || head.contentType() == null
                || !head.contentType().toLowerCase(java.util.Locale.ROOT)
                        .startsWith("video/mp4")) {
            throw new IllegalStateException("OSS video HEAD verification failed.");
        }
        return new VideoPartUploader.StoredObject(
                objectKey,
                head.contentLength(),
                head.contentType(),
                head.eTag());
    }

    public void abort() {
        if (completed || uploadId == null) {
            return;
        }
        client.abortMultipartUpload(
                AbortMultipartUploadRequest.newBuilder()
                        .bucket(configuration.bucket())
                        .key(objectKey)
                        .uploadId(uploadId)
                        .build(),
                ONE_ATTEMPT);
        uploadId = null;
    }

    @Override
    public void compensate() {
        if (!completed) {
            abort();
            return;
        }
        client.deleteObject(
                DeleteObjectRequest.newBuilder()
                        .bucket(configuration.bucket())
                        .key(objectKey)
                        .build(),
                ONE_ATTEMPT);
    }

    @Override
    public void close() throws IOException {
        try {
            client.close();
        } catch (Exception exception) {
            // OSS SDK 将关闭操作声明为通用 checked Exception，而 FC 传输边界只暴露 IOException；统一转换便于上层回滚处理。
            throw new IOException("OSS client close failed.", exception);
        }
    }

    static String requireObjectKey(String objectKey, String prefix) {
        if (objectKey == null
                || !objectKey.startsWith(prefix)
                || objectKey.startsWith("/")
                || objectKey.contains("..")
                || objectKey.contains("\\")
                || !objectKey.endsWith(".mp4")) {
            throw new IllegalArgumentException("OSS video object key is invalid.");
        }
        return objectKey;
    }

}
