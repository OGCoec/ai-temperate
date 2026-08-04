package com.example.temperate.common.aliyun;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.PresignOptions;
import com.aliyun.sdk.service.oss2.credentials.EnvironmentVariableCredentialsProvider;
import com.aliyun.sdk.service.oss2.models.CopyObjectRequest;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectResult;
import com.aliyun.sdk.service.oss2.models.HeadObjectRequest;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 提供参数完整且与业务路径解耦的阿里云 OSS 同步对象操作。
 *
 * <p>该工具只负责 SDK 请求、客户端关闭和有界下载；Bucket、路径、图片格式与公开 URL 规则由上层业务决定。
 * 凭据固定读取 OSS SDK 的环境变量，禁止调用方传入或记录 AccessKey。</p>
 */
@Component
public final class AliyunOssUtils {

    private static final String PRIVATE_ACL = "private";
    private static final String PUBLIC_READ_ACL = "public-read";
    private static final String REPLACE_METADATA = "REPLACE";
    private static final String IMMUTABLE_CACHE_CONTROL =
            "public, max-age=31536000, immutable";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_WRITE_TIMEOUT = Duration.ofSeconds(20);

    private final OssClientFactory clientFactory;

    public AliyunOssUtils() {
        this(AliyunOssUtils::createClient);
    }

    AliyunOssUtils(OssClientFactory clientFactory) {
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory must not be null");
    }

    /**
     * 生成只允许写入一个私有且不可覆盖对象的 PUT 预签名地址。
     */
    public PresignedPut generatePresignedPutUrl(
            String bucket,
            String region,
            String endpoint,
            String objectKey,
            String contentType,
            Duration validity) {
        requirePositive(validity, "validity");
        PutObjectRequest request = PutObjectRequest.newBuilder()
                .bucket(requireText(bucket, "bucket"))
                .key(requireObjectKey(objectKey))
                .contentType(requireText(contentType, "contentType"))
                .objectAcl(PRIVATE_ACL)
                .forbidOverwrite(true)
                .build();
        return presignPut(region, endpoint, request, validity);
    }

    /**
     * 为已知大小的上传生成私有 PUT 地址，并把大小纳入签名约束。
     *
     * <p>会话附件使用此重载；旧头像调用暂时保留不带长度的兼容重载，避免改变既有接口契约。</p>
     */
    public PresignedPut generatePresignedPutUrl(
            String bucket,
            String region,
            String endpoint,
            String objectKey,
            String contentType,
            long contentLength,
            Duration validity) {
        requirePositive(validity, "validity");
        if (contentLength <= 0L || contentLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "contentLength must be between 1 and Integer.MAX_VALUE");
        }
        // OSS v2 SDK 仅接受 Integer；先校验再精确转换，避免 long 强转时静默截断并签入错误长度。
        int sdkContentLength = Math.toIntExact(contentLength);
        PutObjectRequest request = PutObjectRequest.newBuilder()
                .bucket(requireText(bucket, "bucket"))
                .key(requireObjectKey(objectKey))
                .contentType(requireText(contentType, "contentType"))
                .contentLength(sdkContentLength)
                .objectAcl(PRIVATE_ACL)
                .forbidOverwrite(true)
                .build();
        return presignPut(region, endpoint, request, validity);
    }

    private PresignedPut presignPut(
            String region,
            String endpoint,
            PutObjectRequest request,
            Duration validity) {
        OSSClient client = client(region, endpoint);
        try {
            var result = client.presign(
                    request,
                    PresignOptions.newBuilder().expiration(validity).build());
            return new PresignedPut(
                    requireText(result.url(), "presignedUrl"),
                    requireText(result.method(), "method"),
                    result.expiration().orElseGet(() -> Instant.now().plus(validity)),
                    Map.copyOf(result.signedHeaders().orElse(Map.of())));
        } finally {
            closeQuietly(client);
        }
    }

    /**
     * 为私有对象生成短期 GET 地址；上层只能把结果交给当前模型请求，禁止写入数据库、Redis 或日志。
     */
    public PresignedGet generatePresignedGetUrl(
            String bucket,
            String region,
            String endpoint,
            String objectKey,
            Duration validity) {
        requirePositive(validity, "validity");
        GetObjectRequest request = GetObjectRequest.newBuilder()
                .bucket(requireText(bucket, "bucket"))
                .key(requireObjectKey(objectKey))
                .build();
        OSSClient client = client(region, endpoint);
        try {
            var result = client.presign(
                    request,
                    PresignOptions.newBuilder().expiration(validity).build());
            return new PresignedGet(
                    requireText(result.url(), "presignedUrl"),
                    result.expiration().orElseGet(() -> Instant.now().plus(validity)));
        } finally {
            closeQuietly(client);
        }
    }

    /**
     * 将已经由业务层完成大小和内容校验的小型字节数组同步上传为公共读对象。
     *
     * <p>调用方必须显式提供缓存策略与是否禁止覆盖，避免头像临时对象、正式头像和模型图标
     * 在通用工具层共享错误的 ACL 或缓存语义。</p>
     */
    public void putObjectBytes(
            String bucket,
            String region,
            String endpoint,
            String objectKey,
            byte[] bytes,
            String contentType,
            String cacheControl,
            boolean forbidOverwrite) {
        putObjectBytes(
                bucket,
                region,
                endpoint,
                objectKey,
                bytes,
                contentType,
                cacheControl,
                forbidOverwrite,
                DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_READ_WRITE_TIMEOUT);
    }

    /**
     * 使用调用方限定的连接和读写超时上传对象，避免最终图片持久化无限占用 Worker。
     */
    public void putObjectBytes(
            String bucket,
            String region,
            String endpoint,
            String objectKey,
            byte[] bytes,
            String contentType,
            String cacheControl,
            boolean forbidOverwrite,
            Duration connectTimeout,
            Duration readWriteTimeout) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("bytes must not be empty");
        }
        PutObjectRequest request = PutObjectRequest.newBuilder()
                .bucket(requireText(bucket, "bucket"))
                .key(requireObjectKey(objectKey))
                .body(BinaryData.fromBytes(bytes))
                .contentLength(bytes.length)
                .contentType(requireText(contentType, "contentType"))
                .cacheControl(requireText(cacheControl, "cacheControl"))
                .objectAcl(PUBLIC_READ_ACL)
                .forbidOverwrite(forbidOverwrite)
                .build();
        OSSClient client = client(
                region, endpoint, connectTimeout, readWriteTimeout);
        try {
            client.putObject(request);
        } finally {
            closeQuietly(client);
        }
    }

    /**
     * 读取对象元数据，供上层在下载前先执行大小和媒体类型校验。
     */
    public ObjectMetadata headObject(
            String bucket,
            String region,
            String endpoint,
            String objectKey) {
        OSSClient client = client(region, endpoint);
        try {
            var result = client.headObject(HeadObjectRequest.newBuilder()
                    .bucket(requireText(bucket, "bucket"))
                    .key(requireObjectKey(objectKey))
                    .build());
            return new ObjectMetadata(result.contentLength(), result.contentType(), result.eTag());
        } finally {
            closeQuietly(client);
        }
    }

    /**
     * 有界下载一个对象；即使服务端元数据异常，也只读取上限加一个字节来识别超限。
     */
    public byte[] downloadObjectBytesBounded(
            String bucket,
            String region,
            String endpoint,
            String objectKey,
            long maximumBytes) {
        if (maximumBytes <= 0L || maximumBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maximumBytes must be between 1 and Integer.MAX_VALUE - 1");
        }
        OSSClient client = client(region, endpoint);
        try (GetObjectResult result = client.getObject(GetObjectRequest.newBuilder()
                     .bucket(requireText(bucket, "bucket"))
                     .key(requireObjectKey(objectKey))
                     .range("bytes=0-" + maximumBytes)
                     .build())) {
            Long declaredLength = result.contentLength();
            if (declaredLength != null && declaredLength > maximumBytes) {
                throw new ObjectTooLargeException(maximumBytes);
            }
            byte[] bytes = result.body().readNBytes(Math.toIntExact(maximumBytes + 1L));
            if (bytes.length > maximumBytes) {
                throw new ObjectTooLargeException(maximumBytes);
            }
            return bytes;
        } catch (IOException exception) {
            throw new IllegalStateException("OSS object body could not be read", exception);
        } catch (ObjectTooLargeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("OSS object download could not be completed", exception);
        } finally {
            closeQuietly(client);
        }
    }

    /**
     * 在 OSS 服务端复制对象，并用显式元数据把正式头像设为公共读和不可变缓存。
     */
    public void copyObjectToBucket(
            String sourceBucket,
            String sourceKey,
            String destinationBucket,
            String destinationKey,
            String region,
            String endpoint,
            String contentType) {
        OSSClient client = client(region, endpoint);
        try {
            client.copyObject(CopyObjectRequest.newBuilder()
                    .sourceBucket(requireText(sourceBucket, "sourceBucket"))
                    .sourceKey(requireObjectKey(sourceKey))
                    .bucket(requireText(destinationBucket, "destinationBucket"))
                    .key(requireObjectKey(destinationKey))
                    .metadataDirective(REPLACE_METADATA)
                    .objectAcl(PUBLIC_READ_ACL)
                    .contentType(requireText(contentType, "contentType"))
                    .cacheControl(IMMUTABLE_CACHE_CONTROL)
                    .build());
        } finally {
            closeQuietly(client);
        }
    }

    /**
     * 幂等删除单个已知 Object Key；OSS 对不存在对象执行删除仍视为成功。
     */
    public void deleteObject(
            String bucket,
            String region,
            String endpoint,
            String objectKey) {
        OSSClient client = client(region, endpoint);
        try {
            client.deleteObject(DeleteObjectRequest.newBuilder()
                    .bucket(requireText(bucket, "bucket"))
                    .key(requireObjectKey(objectKey))
                    .build());
        } finally {
            closeQuietly(client);
        }
    }

    private OSSClient client(String region, String endpoint) {
        return clientFactory.create(
                requireText(region, "region"),
                requireText(endpoint, "endpoint"),
                DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_READ_WRITE_TIMEOUT);
    }

    private OSSClient client(
            String region,
            String endpoint,
            Duration connectTimeout,
            Duration readWriteTimeout) {
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readWriteTimeout, "readWriteTimeout");
        return clientFactory.create(
                requireText(region, "region"),
                requireText(endpoint, "endpoint"),
                connectTimeout,
                readWriteTimeout);
    }

    private static OSSClient createClient(
            String region,
            String endpoint,
            Duration connectTimeout,
            Duration readWriteTimeout) {
        return OSSClient.newBuilder()
                .region(region)
                .endpoint(endpoint)
                .connectTimeout(connectTimeout)
                .readWriteTimeout(readWriteTimeout)
                .credentialsProvider(new EnvironmentVariableCredentialsProvider())
                .build();
    }

    private static void closeQuietly(OSSClient client) {
        try {
            client.close();
        } catch (Exception ignored) {
            // 主请求结果优先；客户端关闭失败不能把已经成功的幂等对象操作改写为业务失败。
        }
    }

    private static String requireObjectKey(String objectKey) {
        String value = requireText(objectKey, "objectKey");
        if (value.startsWith("/") || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException("objectKey must be a normalized relative key");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /**
     * 表示预签名 PUT 所有必须交给客户端的请求条件。
     */
    public record PresignedPut(
            String url,
            String method,
            Instant expiresAt,
            Map<String, String> signedHeaders) {
    }

    /**
     * 表示只用于当前上游模型请求的私有对象短期读取地址。
     */
    public record PresignedGet(String url, Instant expiresAt) {
    }

    /**
     * 表示确认头像所需的最小 OSS 对象元数据。
     */
    public record ObjectMetadata(
            Long contentLength,
            String contentType,
            String eTag) {
    }

    /**
     * 表示对象体超过业务允许的有界下载上限。
     */
    public static final class ObjectTooLargeException extends RuntimeException {

        public ObjectTooLargeException(long maximumBytes) {
            super("OSS object exceeds the bounded download limit of " + maximumBytes + " bytes");
        }
    }

    @FunctionalInterface
    interface OssClientFactory {

        OSSClient create(
                String region,
                String endpoint,
                Duration connectTimeout,
                Duration readWriteTimeout);
    }
}
