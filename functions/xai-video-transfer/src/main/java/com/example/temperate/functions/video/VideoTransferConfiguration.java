package com.example.temperate.functions.video;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从 FC 环境读取固定 Bucket、RAM 访问边界和来源白名单，禁止请求覆盖部署级安全配置。
 */
public final class VideoTransferConfiguration {

    private final String hmacSecret;
    private final String bucket;
    private final String region;
    private final String endpoint;
    private final String objectPrefix;
    private final long maximumBytes;
    private final Set<String> allowedSourceHosts;

    public VideoTransferConfiguration(
            String hmacSecret,
            String bucket,
            String region,
            String endpoint,
            String objectPrefix,
            long maximumBytes,
            Set<String> allowedSourceHosts) {
        requireText(hmacSecret, "VIDEO_TRANSFER_HMAC_SECRET");
        requireText(bucket, "OSS_BUCKET");
        requireText(region, "OSS_REGION");
        requireText(endpoint, "OSS_ENDPOINT");
        requireText(objectPrefix, "OSS_OBJECT_PREFIX");
        // HMAC 密钥至少保留 256 bit 原始强度，防止部署时误用短口令削弱请求鉴权。
        if (hmacSecret.getBytes(StandardCharsets.UTF_8).length < 32
                || !"https".equalsIgnoreCase(URI.create(endpoint).getScheme())
                || objectPrefix.startsWith("/")
                || objectPrefix.contains("..")
                || objectPrefix.contains("\\")
                || maximumBytes <= 0L
                || allowedSourceHosts == null
                || allowedSourceHosts.isEmpty()) {
            throw new IllegalArgumentException("FC video transfer configuration is invalid.");
        }
        this.hmacSecret = hmacSecret;
        this.bucket = bucket;
        this.region = region;
        this.endpoint = endpoint;
        this.objectPrefix = objectPrefix.endsWith("/")
                ? objectPrefix
                : objectPrefix + "/";
        this.maximumBytes = maximumBytes;
        this.allowedSourceHosts = Set.copyOf(allowedSourceHosts);
    }

    public String hmacSecret() {
        return hmacSecret;
    }

    public String bucket() {
        return bucket;
    }

    public String region() {
        return region;
    }

    public String endpoint() {
        return endpoint;
    }

    public String objectPrefix() {
        return objectPrefix;
    }

    public long maximumBytes() {
        return maximumBytes;
    }

    public Set<String> allowedSourceHosts() {
        return allowedSourceHosts;
    }

    public static VideoTransferConfiguration fromEnvironment() {
        return new VideoTransferConfiguration(
                System.getenv("VIDEO_TRANSFER_HMAC_SECRET"),
                System.getenv("OSS_BUCKET"),
                System.getenv("OSS_REGION"),
                System.getenv("OSS_ENDPOINT"),
                valueOrDefault(System.getenv("OSS_OBJECT_PREFIX"), "ai/video/"),
                Long.parseLong(valueOrDefault(
                        System.getenv("VIDEO_MAXIMUM_BYTES"),
                        "2147483648")),
                Arrays.stream(requireEnvironment("VIDEO_ALLOWED_SOURCE_HOSTS")
                                .split(","))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .collect(Collectors.toUnmodifiableSet()));
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        requireText(value, name);
        return value;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required.");
        }
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
