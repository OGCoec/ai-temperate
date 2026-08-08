package com.example.temperate.service.user.aiconversation.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定 xAI 视频轮询、官方价格和 FC 搬运边界；密钥只允许从环境变量注入且不会出现在字符串输出中。
 */
@Validated
@ConfigurationProperties(prefix = "app.ai-conversation.video-generation")
public record AiConversationVideoGenerationProperties(
        boolean enabled,
        @NotNull Duration pollInterval,
        @NotNull Duration maximumPollingDuration,
        int maximumResponseJsonBytes,
        @Valid @NotNull EndpointPaths endpoints,
        @Valid @NotNull Version15Pricing version15,
        @Valid @NotNull LegacyPricing legacy,
        @Valid @NotNull FunctionCompute functionCompute) {

    public static final long TICKS_PER_US_DOLLAR = 10_000_000_000L;

    @AssertTrue(message = "xAI video polling and JSON limits must be positive")
    public boolean areLimitsValid() {
        return pollInterval != null
                && !pollInterval.isZero()
                && !pollInterval.isNegative()
                && maximumPollingDuration != null
                && !maximumPollingDuration.isZero()
                && !maximumPollingDuration.isNegative()
                && maximumResponseJsonBytes >= 16 * 1024;
    }

    @AssertTrue(message = "Enabled xAI video generation requires FC configuration")
    public boolean isFunctionComputeConfiguredWhenEnabled() {
        return !enabled || functionCompute.configured();
    }

    /**
     * 提供与官方文档一致的安全关闭默认值，供纯单元测试和未启用环境使用。
     */
    public static AiConversationVideoGenerationProperties officialDefaults() {
        return new AiConversationVideoGenerationProperties(
                false,
                Duration.ofSeconds(5),
                Duration.ofMinutes(15),
                1024 * 1024,
                new EndpointPaths(
                        "/v1/videos/generations",
                        "/v1/videos/edits",
                        "/v1/videos/extensions",
                        "/v1/videos/{requestId}"),
                new Version15Pricing(
                        "grok-imagine-video-1.5",
                        800_000_000L,
                        1_400_000_000L,
                        2_500_000_000L,
                        100_000_000L),
                new LegacyPricing(
                        "grok-imagine-video",
                        500_000_000L,
                        700_000_000L,
                        20_000_000L,
                        100_000_000L),
                new FunctionCompute(
                        null,
                        null,
                        Duration.ofMinutes(15),
                        "ai/video/",
                        2_147_483_648L,
                        List.of("vidgen.x.ai")));
    }

    /**
     * 保存 xAI 视频创建、编辑、延长和轮询路径，禁止通过配置切换到完整外部 URL。
     */
    public record EndpointPaths(
            String generations,
            String edits,
            String extensions,
            String poll) {

        public EndpointPaths {
            requirePath(generations, "Video generations path");
            requirePath(edits, "Video edits path");
            requirePath(extensions, "Video extensions path");
            requirePath(poll, "Video poll path");
            if (!poll.contains("{requestId}")) {
                throw new IllegalArgumentException(
                        "Video poll path must contain {requestId}.");
            }
        }
    }

    /**
     * 保存 grok-imagine-video-1.5 的官方模型名、三档输出秒价和图片输入单价。
     */
    public record Version15Pricing(
            String modelName,
            long p480OutputTicksPerSecond,
            long p720OutputTicksPerSecond,
            long p1080OutputTicksPerSecond,
            long imageInputTicksEach) {

        public Version15Pricing {
            requireText(modelName, "Version 1.5 model name");
            requirePositive(
                    p480OutputTicksPerSecond,
                    p720OutputTicksPerSecond,
                    p1080OutputTicksPerSecond,
                    imageInputTicksEach);
        }
    }

    /**
     * 保存 grok-imagine-video 的官方模型名、两档输出秒价以及图片和视频输入单价。
     */
    public record LegacyPricing(
            String modelName,
            long p480OutputTicksPerSecond,
            long p720OutputTicksPerSecond,
            long imageInputTicksEach,
            long videoInputTicksPerSecond) {

        public LegacyPricing {
            requireText(modelName, "Legacy video model name");
            requirePositive(
                    p480OutputTicksPerSecond,
                    p720OutputTicksPerSecond,
                    imageInputTicksEach,
                    videoInputTicksPerSecond);
        }
    }

    /**
     * 保存主业务服务调用 FC 所需的小型 JSON 端点和对象边界，不包含 OSS 长期凭据。
     */
    public record FunctionCompute(
            String invocationUrl,
            String hmacSecret,
            @NotNull Duration timeout,
            String objectPrefix,
            long maximumVideoBytes,
            List<String> allowedSourceHosts) {

        public FunctionCompute {
            allowedSourceHosts = allowedSourceHosts == null
                    ? List.of()
                    : List.copyOf(allowedSourceHosts);
        }

        public boolean configured() {
            return hasText(invocationUrl)
                    && invocationUrl.startsWith("https://")
                    && hasText(hmacSecret)
                    && hmacSecret.getBytes(StandardCharsets.UTF_8).length >= 32
                    && timeout != null
                    && !timeout.isZero()
                    && !timeout.isNegative()
                    && hasText(objectPrefix)
                    && maximumVideoBytes > 0L
                    && allowedSourceHosts.size() >= 2
                    && allowedSourceHosts.stream().allMatch(
                            AiConversationVideoGenerationProperties::hasText);
        }

        @Override
        public String toString() {
            return "FunctionCompute[invocationUrl=" + invocationUrl
                    + ", hmacSecret=<redacted>, timeout=" + timeout
                    + ", objectPrefix=" + objectPrefix
                    + ", maximumVideoBytes=" + maximumVideoBytes
                    + ", allowedSourceHosts=" + allowedSourceHosts + "]";
        }
    }

    @Override
    public String toString() {
        return "AiConversationVideoGenerationProperties[enabled=" + enabled
                + ", pollInterval=" + pollInterval
                + ", maximumPollingDuration=" + maximumPollingDuration
                + ", maximumResponseJsonBytes=" + maximumResponseJsonBytes
                + ", endpoints=" + endpoints
                + ", version15=" + version15
                + ", legacy=" + legacy
                + ", functionCompute=" + functionCompute + "]";
    }

    private static void requireText(String value, String name) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(name + " is required.");
        }
    }

    private static void requirePath(String value, String name) {
        requireText(value, name);
        if (!value.startsWith("/")
                || value.startsWith("//")
                || value.contains("?")
                || value.contains("#")) {
            throw new IllegalArgumentException(name + " must be an absolute API path.");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void requirePositive(long... values) {
        for (long value : values) {
            if (value <= 0L) {
                throw new IllegalArgumentException(
                        "Official xAI video prices must be positive.");
            }
        }
    }
}
