package com.example.temperate.service.user.apikey.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 该配置是来绑定外部 API Key 的启用开关、HMAC、缓存、请求边界和固定 v1 Bloom 参数，并在启动时拒绝不安全的 Secret。
 */
@Validated
@ConfigurationProperties(prefix = "app.api-key")
public class ApiKeyProperties {

    private boolean enabled;
    private String hmacSecretBase64;
    @Min(1)
    @Max(64)
    private int maxConcurrentPerKey = 3;
    @Min(0)
    @Max(100)
    private int minimumIpTrustScore = 60;
    @Min(1)
    @Max(500)
    private int maxModelGrants = 500;
    @Valid
    @NotNull
    private AuthCache authCache = new AuthCache();
    @Valid
    @NotNull
    private Request request = new Request();
    @Valid
    @NotNull
    private StreamDiagnostics streamDiagnostics = new StreamDiagnostics();
    @Valid
    @NotNull
    private Bloom bloom = new Bloom();

    /**
     * 固定 HMAC Secret 必须是规范 Base64 且至少解码为 32 字节；即使功能暂时关闭，也禁止用随机材料掩盖缺失配置。
     */
    @AssertTrue(message = "API Key HMAC Secret must be canonical Base64 containing at least 32 bytes")
    public boolean isHmacSecretValid() {
        if (hmacSecretBase64 == null || hmacSecretBase64.isBlank()) {
            return false;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(hmacSecretBase64);
            return decoded.length >= 32
                    && Base64.getEncoder().encodeToString(decoded).equals(hmacSecretBase64);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHmacSecretBase64() {
        return hmacSecretBase64;
    }

    public void setHmacSecretBase64(String hmacSecretBase64) {
        this.hmacSecretBase64 = hmacSecretBase64;
    }

    public int getMaxConcurrentPerKey() {
        return maxConcurrentPerKey;
    }

    public void setMaxConcurrentPerKey(int maxConcurrentPerKey) {
        this.maxConcurrentPerKey = maxConcurrentPerKey;
    }

    public int getMinimumIpTrustScore() {
        return minimumIpTrustScore;
    }

    public void setMinimumIpTrustScore(int minimumIpTrustScore) {
        this.minimumIpTrustScore = minimumIpTrustScore;
    }

    public int getMaxModelGrants() {
        return maxModelGrants;
    }

    public void setMaxModelGrants(int maxModelGrants) {
        this.maxModelGrants = maxModelGrants;
    }

    public AuthCache getAuthCache() {
        return authCache;
    }

    public void setAuthCache(AuthCache authCache) {
        this.authCache = authCache;
    }

    public Request getRequest() {
        return request;
    }

    public void setRequest(Request request) {
        this.request = request;
    }

    public StreamDiagnostics getStreamDiagnostics() {
        return streamDiagnostics;
    }

    public void setStreamDiagnostics(StreamDiagnostics streamDiagnostics) {
        this.streamDiagnostics = streamDiagnostics;
    }

    public Bloom getBloom() {
        return bloom;
    }

    public void setBloom(Bloom bloom) {
        this.bloom = bloom;
    }

    /**
     * 该配置组是来限制正向和负向认证缓存的随机 TTL 区间，避免同时失效形成尖峰。
     */
    public static class AuthCache {
        @NotNull
        private Duration minimumTtl = Duration.ofMinutes(5);
        @NotNull
        private Duration maximumTtl = Duration.ofMinutes(15);
        @NotNull
        private Duration negativeMinimumTtl = Duration.ofSeconds(30);
        @NotNull
        private Duration negativeMaximumTtl = Duration.ofSeconds(60);

        @AssertTrue(message = "API Key cache TTL ranges are invalid")
        public boolean isValidRange() {
            return positive(minimumTtl)
                    && positive(maximumTtl)
                    && positive(negativeMinimumTtl)
                    && positive(negativeMaximumTtl)
                    && minimumTtl.compareTo(maximumTtl) <= 0
                    && negativeMinimumTtl.compareTo(negativeMaximumTtl) <= 0;
        }

        private static boolean positive(Duration value) {
            return value != null && !value.isNegative() && !value.isZero();
        }

        public Duration getMinimumTtl() {
            return minimumTtl;
        }

        public void setMinimumTtl(Duration minimumTtl) {
            this.minimumTtl = minimumTtl;
        }

        public Duration getMaximumTtl() {
            return maximumTtl;
        }

        public void setMaximumTtl(Duration maximumTtl) {
            this.maximumTtl = maximumTtl;
        }

        public Duration getNegativeMinimumTtl() {
            return negativeMinimumTtl;
        }

        public void setNegativeMinimumTtl(Duration negativeMinimumTtl) {
            this.negativeMinimumTtl = negativeMinimumTtl;
        }

        public Duration getNegativeMaximumTtl() {
            return negativeMaximumTtl;
        }

        public void setNegativeMaximumTtl(Duration negativeMaximumTtl) {
            this.negativeMaximumTtl = negativeMaximumTtl;
        }
    }

    /**
     * 该配置组是来约束公开 Chat Completions 请求的内存和集合上限，校验发生在上游连接之前。
     */
    public static class Request {
        @Min(1024)
        @Max(10485760)
        private int maxBodyBytes = 1048576;
        @Min(1)
        @Max(1024)
        private int maxMessages = 256;
        @Min(0)
        @Max(512)
        private int maxTools = 128;
        @Min(1024)
        @Max(262144)
        private int maxToolDescriptionBytes = 32768;
        @Min(32768)
        @Max(1048576)
        private int maxToolDefinitionsBytes = 524288;

        /**
         * 单项工具描述、完整工具集合和请求体必须形成单调预算，避免较小的外层限制让内部兼容配置失效。
         */
        @AssertTrue(message = "API Key tool definition byte budgets are invalid")
        public boolean isToolBudgetsValid() {
            return maxToolDescriptionBytes <= maxToolDefinitionsBytes
                    && maxToolDefinitionsBytes <= maxBodyBytes;
        }

        public int getMaxBodyBytes() {
            return maxBodyBytes;
        }

        public void setMaxBodyBytes(int maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
        }

        public int getMaxMessages() {
            return maxMessages;
        }

        public void setMaxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
        }

        public int getMaxTools() {
            return maxTools;
        }

        public void setMaxTools(int maxTools) {
            this.maxTools = maxTools;
        }

        public int getMaxToolDescriptionBytes() {
            return maxToolDescriptionBytes;
        }

        public void setMaxToolDescriptionBytes(int maxToolDescriptionBytes) {
            this.maxToolDescriptionBytes = maxToolDescriptionBytes;
        }

        public int getMaxToolDefinitionsBytes() {
            return maxToolDefinitionsBytes;
        }

        public void setMaxToolDefinitionsBytes(int maxToolDefinitionsBytes) {
            this.maxToolDefinitionsBytes = maxToolDefinitionsBytes;
        }
    }

    /**
     * 该配置组是来控制公开 API Key 流的安全诊断采样、节奏窗口和有界终止历史；采样率只约束成功与高频遥测，
     * 诊断开启时失败分类始终全量记录，且禁止记录请求正文、模型输出、工具参数或完整凭据。
     */
    public static class StreamDiagnostics {
        private boolean enabled = true;
        private double sampleRate = 1.0d;
        @NotNull
        private Duration window = Duration.ofSeconds(1);
        private int logEveryFrames = 100;
        @NotNull
        private Duration silenceThreshold = Duration.ofSeconds(2);
        @NotNull
        private Duration burstWindow = Duration.ofMillis(250);
        private int burstFrames = 50;
        private int terminalHistorySize = 32;
        private int stackFrameLimit = 12;

        /**
         * 诊断窗口必须保持有界且时间关系单调，防止误配置产生无界内存、日志洪泛或无意义的静默判定。
         */
        @AssertTrue(message = "API Key stream diagnostics bounds are invalid")
        public boolean isValid() {
            return sampleRate >= 0.0d
                    && sampleRate <= 1.0d
                    && boundedDuration(window)
                    && boundedDuration(silenceThreshold)
                    && boundedDuration(burstWindow)
                    && window.compareTo(silenceThreshold) <= 0
                    && burstWindow.compareTo(window) <= 0
                    && logEveryFrames >= 1
                    && logEveryFrames <= 10_000
                    && burstFrames >= 2
                    && burstFrames <= 10_000
                    && terminalHistorySize >= 1
                    && terminalHistorySize <= 128
                    && stackFrameLimit >= 1
                    && stackFrameLimit <= 64;
        }

        private static boolean positive(Duration value) {
            return value != null && !value.isNegative() && !value.isZero();
        }

        private static boolean boundedDuration(Duration value) {
            return positive(value) && value.compareTo(Duration.ofHours(1)) <= 0;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getSampleRate() {
            return sampleRate;
        }

        public void setSampleRate(double sampleRate) {
            this.sampleRate = sampleRate;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public int getLogEveryFrames() {
            return logEveryFrames;
        }

        public void setLogEveryFrames(int logEveryFrames) {
            this.logEveryFrames = logEveryFrames;
        }

        public Duration getSilenceThreshold() {
            return silenceThreshold;
        }

        public void setSilenceThreshold(Duration silenceThreshold) {
            this.silenceThreshold = silenceThreshold;
        }

        public Duration getBurstWindow() {
            return burstWindow;
        }

        public void setBurstWindow(Duration burstWindow) {
            this.burstWindow = burstWindow;
        }

        public int getBurstFrames() {
            return burstFrames;
        }

        public void setBurstFrames(int burstFrames) {
            this.burstFrames = burstFrames;
        }

        public int getTerminalHistorySize() {
            return terminalHistorySize;
        }

        public void setTerminalHistorySize(int terminalHistorySize) {
            this.terminalHistorySize = terminalHistorySize;
        }

        public int getStackFrameLimit() {
            return stackFrameLimit;
        }

        public void setStackFrameLimit(int stackFrameLimit) {
            this.stackFrameLimit = stackFrameLimit;
        }
    }

    /**
     * 该配置组是来固定 API Key Bloom 的计数器布局、Receipt 分片和有界数据库构建批次。
     */
    public static class Bloom {
        @Min(1)
        private int capacity = 14377588;
        @Min(1)
        @Max(32)
        private int hashCount = 10;
        @Min(1)
        @Max(4)
        private int counterBytes = 1;
        @Min(1)
        private int countersPerBucket = 3594397;
        @Min(1)
        @Max(4096)
        private int receiptShards = 1024;
        @Min(100)
        @Max(500)
        private int buildPageSize = 500;

        /** 阶段 S 的 v1 布局写入 ADR 后固定，避免不同实例用环境变量形成不兼容的 Redis 解释。 */
        @AssertTrue(message = "API Key Bloom v1 layout must use the approved fixed parameters")
        public boolean isApprovedV1Layout() {
            return capacity == 14_377_588
                    && hashCount == 10
                    && counterBytes == 1
                    && countersPerBucket == 3_594_397
                    && receiptShards == 1_024
                    && buildPageSize == 500;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getHashCount() {
            return hashCount;
        }

        public void setHashCount(int hashCount) {
            this.hashCount = hashCount;
        }

        public int getCounterBytes() {
            return counterBytes;
        }

        public void setCounterBytes(int counterBytes) {
            this.counterBytes = counterBytes;
        }

        public int getCountersPerBucket() {
            return countersPerBucket;
        }

        public void setCountersPerBucket(int countersPerBucket) {
            this.countersPerBucket = countersPerBucket;
        }

        public int getReceiptShards() {
            return receiptShards;
        }

        public void setReceiptShards(int receiptShards) {
            this.receiptShards = receiptShards;
        }

        public int getBuildPageSize() {
            return buildPageSize;
        }

        public void setBuildPageSize(int buildPageSize) {
            this.buildPageSize = buildPageSize;
        }
    }
}
