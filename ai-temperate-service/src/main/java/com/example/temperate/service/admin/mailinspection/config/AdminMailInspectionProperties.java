package com.example.temperate.service.admin.mailinspection.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定管理员邮箱检查的固定代理、有限重试、并发、扫描与进程内任务生命周期参数。
 *
 * <p>该配置不接收邮箱凭证；Rabbit 加密密钥只从部署环境绑定，代理只有一个显式端点且不提供直连或备用端口。</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.admin.mail-inspection")
public record AdminMailInspectionProperties(
        @Valid @NotNull Proxy proxy,
        @Valid @NotNull OAuth oauth,
        @Valid @NotNull Imap imap,
        @Valid @NotNull Job job,
        @Valid @NotNull Rabbit rabbit,
        @Valid @NotNull Submission submission,
        @Valid @NotNull Scan scan,
        @Valid @NotNull Matchers matchers) {

    public AdminMailInspectionProperties {
        requireLoopback(proxy);
    }

    /**
     * 提供与批准计划完全一致的无 Secret 默认值，供纯单元测试构造组件。
     */
    public static AdminMailInspectionProperties defaults() {
        return new AdminMailInspectionProperties(
                new Proxy("127.0.0.1", 7897),
                new OAuth(
                        URI.create("https://login.microsoftonline.com/common/oauth2/v2.0/token"),
                        "https://outlook.office.com/IMAP.AccessAsUser.All offline_access",
                        16,
                        8,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(12),
                        3,
                        Duration.ofSeconds(1),
                        0.2D,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(120)),
                new Imap(
                        "imap-mail.outlook.com",
                        993,
                        4,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(45),
                        3),
                new Job(
                        2,
                        1,
                        4,
                        64,
                        12_288,
                        1_048_576,
                        Duration.ofMinutes(30),
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(2)),
                new Rabbit(
                        false,
                        Base64.getEncoder().encodeToString(new byte[32]),
                        Duration.ofSeconds(5),
                        3,
                        List.of(
                                Duration.ofMillis(200),
                                Duration.ofMillis(500),
                                Duration.ofSeconds(1)),
                        Duration.ofMinutes(1),
                        500),
                new Submission(
                        Duration.ofHours(6),
                        Duration.ofMinutes(1),
                        163_840,
                        262_144,
                        8,
                        16),
                new Scan(
                        80,
                        30,
                        20,
                        20,
                        200_000,
                        List.of("Junk Email", "INBOX")),
                new Matchers(
                        new EvidenceMatcher(
                                List.of("openai", "chatgpt"),
                                List.of("openai", "chatgpt"),
                                List.of(
                                        "your account has been detected",
                                        "deactivated",
                                        "deactivating")),
                        new EvidenceMatcher(
                                List.of("amazonaws.com", "no-reply@amazonaws.com"),
                                List.of("kiro", "your kiro account", "response required"),
                                List.of(
                                        "detected suspicious activity",
                                        "restricted your ability to use kiro",
                                        "requires immediate attention",
                                        "validate your account details",
                                        "abuse",
                                        "suspicious activity",
                                        "restricted",
                                        "suspended",
                                        "disabled",
                                        "terminated",
                                        "policy violation",
                                        "account review")),
                        new Ip2LocationMatcher("ip2location.io", "ip2location")));
    }

    private static void requireLoopback(Proxy proxy) {
        if (proxy != null
                && !proxy.host().equals("127.0.0.1")) {
            throw new IllegalArgumentException(
                    "mail inspection proxy must remain on loopback");
        }
    }

    /**
     * 定义 OAuth HTTP CONNECT 与 IMAP SOCKS 共用的单一本机 mixed 代理端点。
     */
    public record Proxy(@NotBlank String host, @Min(1) @Max(65535) int port) {
    }

    /**
     * 定义 Microsoft 公共客户端 OAuth 交换的连接池、超时、重试和总截止时间。
     */
    public record OAuth(
            @NotNull URI tokenUri,
            @NotBlank String scope,
            @Min(1) @Max(64) int maxConnections,
            @Min(1) @Max(32) int concurrency,
            @NotNull Duration connectTimeout,
            @NotNull Duration responseTimeout,
            @Min(1) @Max(3) int maxAttempts,
            @NotNull Duration initialBackoff,
            @DecimalMin("0.0") @DecimalMax("0.5") double jitter,
            @NotNull Duration maxRetryAfter,
            @NotNull Duration credentialTimeout) {

        public OAuth {
            requirePositive(connectTimeout, "oauth.connectTimeout");
            requirePositive(responseTimeout, "oauth.responseTimeout");
            requirePositive(initialBackoff, "oauth.initialBackoff");
            requirePositive(maxRetryAfter, "oauth.maxRetryAfter");
            requirePositive(credentialTimeout, "oauth.credentialTimeout");
        }
    }

    /**
     * 定义 Outlook IMAPS/XOAUTH2 的目标、并发、单次 I/O 与扫描截止时间。
     */
    public record Imap(
            @NotBlank String host,
            @Min(1) @Max(65535) int port,
            @Min(1) @Max(16) int concurrency,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout,
            @NotNull Duration scanTimeout,
            @Min(1) @Max(3) int maxAttempts) {

        public Imap {
            requirePositive(connectTimeout, "imap.connectTimeout");
            requirePositive(readTimeout, "imap.readTimeout");
            requirePositive(scanTimeout, "imap.scanTimeout");
        }
    }

    /**
     * 定义任务容量、批量输入、处理并发、保留和清理周期。
     */
    public record Job(
            @Min(1) @Max(8) int maxActiveJobs,
            @Min(1) @Max(2) int maxActiveJobsPerType,
            @Min(1) @Max(64) int defaultBusinessConcurrency,
            @Min(1) @Max(64) int maxBusinessConcurrency,
            @Min(1024) int maxLineChars,
            @Min(1024) int maxRequestBytes,
            @NotNull Duration retention,
            @NotNull Duration cleanupInterval,
            @NotNull Duration pollAfter) {

        public Job {
            requirePositive(retention, "job.retention");
            requirePositive(cleanupInterval, "job.cleanupInterval");
            requirePositive(pollAfter, "job.pollAfter");
            if (defaultBusinessConcurrency > maxBusinessConcurrency) {
                throw new IllegalArgumentException(
                        "default business concurrency exceeds maximum");
            }
        }
    }

    /**
     * 定义邮箱检查工作消息的独立加密密钥、发布确认时限和有限发布重试参数。
     *
     * <p>密钥只允许来自部署环境；测试默认值仅由 {@link #defaults()} 显式构造，不会写入生产 YAML。</p>
     */
    public record Rabbit(
            boolean enabled,
            @NotBlank String payloadKeyBase64,
            @NotNull Duration confirmTimeout,
            @Min(1) @Max(3) int publishMaxAttempts,
            @NotEmpty List<@NotNull Duration> publishBackoffs,
            @NotNull Duration markerCleanupInterval,
            @Min(1) @Max(5000) int markerCleanupBatchSize) {

        public Rabbit {
            requirePositive(confirmTimeout, "rabbit.confirmTimeout");
            requirePositive(
                    markerCleanupInterval,
                    "rabbit.markerCleanupInterval");
            publishBackoffs = publishBackoffs == null
                    ? List.of()
                    : List.copyOf(publishBackoffs);
            publishBackoffs.forEach(value ->
                    requirePositive(value, "rabbit.publishBackoffs"));
            requireAes256Key(payloadKeyBase64);
        }
    }

    /**
     * 定义持久化提交分块、异步派发并发和残缺任务自动释放边界。
     */
    public record Submission(
            @NotNull Duration incompleteRetention,
            @NotNull Duration cleanupInterval,
            @Min(16_384) @Max(196_608) int maxPlaintextChunkBytes,
            @Min(65_536) @Max(262_144) int maxMessageBytes,
            @Min(1) @Max(16) int publishConcurrency,
            @Min(1) @Max(32) int workDispatchConcurrency) {

        public Submission {
            requirePositive(
                    incompleteRetention,
                    "submission.incompleteRetention");
            requirePositive(cleanupInterval, "submission.cleanupInterval");
            if (maxPlaintextChunkBytes >= maxMessageBytes) {
                throw new IllegalArgumentException(
                        "submission plaintext chunk must be smaller than message boundary");
            }
        }
    }

    /**
     * 定义各业务最多读取的最近邮件数、候选数、正文上限与文件夹优先级。
     */
    public record Scan(
            @Min(1) @Max(500) int statusFetchCount,
            @Min(1) @Max(100) int statusMaxCandidates,
            @Min(1) @Max(100) int ip2FetchCount,
            @Min(1) @Max(100) int ip2MaxCandidates,
            @Min(1024) @Max(500_000) int maxBodyChars,
            @NotEmpty List<String> folderOrder) {

        public Scan {
            folderOrder = folderOrder == null ? List.of() : List.copyOf(folderOrder);
        }
    }

    /**
     * 定义 OpenAI、Kiro 与 IP2Location 邮件候选和限制证据匹配参数。
     */
    public record Matchers(
            @Valid @NotNull EvidenceMatcher openai,
            @Valid @NotNull EvidenceMatcher kiro,
            @Valid @NotNull Ip2LocationMatcher ip2location) {
    }

    /**
     * 定义一类状态邮件的发送方、主题和限制证据短语白名单。
     */
    public record EvidenceMatcher(
            @NotEmpty List<String> senderKeywords,
            @NotEmpty List<String> subjectKeywords,
            @NotEmpty List<String> restrictedPhrases) {

        public EvidenceMatcher {
            senderKeywords = senderKeywords == null
                    ? List.of()
                    : List.copyOf(senderKeywords);
            subjectKeywords = subjectKeywords == null
                    ? List.of()
                    : List.copyOf(subjectKeywords);
            restrictedPhrases = restrictedPhrases == null
                    ? List.of()
                    : List.copyOf(restrictedPhrases);
        }
    }

    /**
     * 定义 IP2Location 邮件的可信发送域和主题候选关键字。
     */
    public record Ip2LocationMatcher(
            @NotBlank String senderDomain,
            @NotBlank String subjectKeyword) {
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireAes256Key(String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalArgumentException(
                    "mail inspection Rabbit payload key is required");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey);
            if (decoded.length != 32) {
                throw new IllegalArgumentException(
                        "mail inspection Rabbit payload key must contain 32 bytes");
            }
            if (!Base64.getEncoder().encodeToString(decoded)
                    .equals(encodedKey)) {
                throw new IllegalArgumentException(
                        "mail inspection Rabbit payload key is non-canonical");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "mail inspection Rabbit payload key must be canonical Base64",
                    exception);
        }
    }
}
