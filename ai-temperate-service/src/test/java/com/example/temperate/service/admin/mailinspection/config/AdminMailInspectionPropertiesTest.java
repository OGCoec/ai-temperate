package com.example.temperate.service.admin.mailinspection.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 验证邮箱检查默认配置锁定本机 7897、有限重试、并发和 Redis 任务租约边界。
 */
final class AdminMailInspectionPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestConfiguration.class)
                    .withPropertyValues(
                            "app.admin.mail-inspection.proxy.host=127.0.0.1",
                            "app.admin.mail-inspection.proxy.port=7897",
                            "app.admin.mail-inspection.oauth.token-uri=https://example.test/token",
                            "app.admin.mail-inspection.oauth.scope=imap offline_access",
                            "app.admin.mail-inspection.oauth.max-connections=16",
                            "app.admin.mail-inspection.oauth.concurrency=8",
                            "app.admin.mail-inspection.oauth.connect-timeout=5s",
                            "app.admin.mail-inspection.oauth.response-timeout=12s",
                            "app.admin.mail-inspection.oauth.max-attempts=3",
                            "app.admin.mail-inspection.oauth.initial-backoff=1s",
                            "app.admin.mail-inspection.oauth.jitter=0.2",
                            "app.admin.mail-inspection.oauth.max-retry-after=30s",
                            "app.admin.mail-inspection.oauth.credential-timeout=120s",
                            "app.admin.mail-inspection.imap.host=imap.example.test",
                            "app.admin.mail-inspection.imap.port=993",
                            "app.admin.mail-inspection.imap.concurrency=4",
                            "app.admin.mail-inspection.imap.connect-timeout=10s",
                            "app.admin.mail-inspection.imap.read-timeout=20s",
                            "app.admin.mail-inspection.imap.scan-timeout=45s",
                            "app.admin.mail-inspection.imap.max-attempts=3",
                            "app.admin.mail-inspection.job.max-active-jobs=2",
                            "app.admin.mail-inspection.job.max-active-jobs-per-type=1",
                            "app.admin.mail-inspection.job.default-business-concurrency=4",
                            "app.admin.mail-inspection.job.max-business-concurrency=64",
                            "app.admin.mail-inspection.job.max-line-chars=12288",
                            "app.admin.mail-inspection.job.max-request-bytes=1048576",
                            "app.admin.mail-inspection.job.active-lease=15m",
                            "app.admin.mail-inspection.job.terminal-retention=15m",
                            "app.admin.mail-inspection.job.lease-heartbeat-interval=30s",
                            "app.admin.mail-inspection.job.max-credential-lines=10000",
                            "app.admin.mail-inspection.job.result-bucket-size=32",
                            "app.admin.mail-inspection.job.snapshot-batch-size=100",
                            "app.admin.mail-inspection.job.key-hmac-secret-base64="
                                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                            "app.admin.mail-inspection.rabbit.payload-key-base64="
                                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                            "app.admin.mail-inspection.rabbit.enabled=true",
                            "app.admin.mail-inspection.rabbit.confirm-timeout=5s",
                            "app.admin.mail-inspection.rabbit.publish-max-attempts=3",
                            "app.admin.mail-inspection.rabbit.publish-backoffs=200ms,500ms,1s",
                            "app.admin.mail-inspection.rabbit.marker-cleanup-interval=1m",
                            "app.admin.mail-inspection.rabbit.marker-cleanup-batch-size=500",
                            "app.admin.mail-inspection.submission.incomplete-retention=6h",
                            "app.admin.mail-inspection.submission.cleanup-interval=1m",
                            "app.admin.mail-inspection.submission.max-plaintext-chunk-bytes=163840",
                            "app.admin.mail-inspection.submission.max-message-bytes=262144",
                            "app.admin.mail-inspection.submission.publish-concurrency=8",
                            "app.admin.mail-inspection.submission.work-dispatch-concurrency=16",
                            "app.admin.mail-inspection.scan.status-fetch-count=80",
                            "app.admin.mail-inspection.scan.status-max-candidates=30",
                            "app.admin.mail-inspection.scan.ip2-fetch-count=20",
                            "app.admin.mail-inspection.scan.ip2-max-candidates=20",
                            "app.admin.mail-inspection.scan.max-body-chars=200000",
                            "app.admin.mail-inspection.scan.folder-order=Junk Email,INBOX",
                            "app.admin.mail-inspection.matchers.openai.sender-keywords=openai,chatgpt",
                            "app.admin.mail-inspection.matchers.openai.subject-keywords=openai,chatgpt",
                            "app.admin.mail-inspection.matchers.openai.restricted-phrases=deactivated",
                            "app.admin.mail-inspection.matchers.kiro.sender-keywords=amazonaws.com",
                            "app.admin.mail-inspection.matchers.kiro.subject-keywords=Kiro",
                            "app.admin.mail-inspection.matchers.kiro.restricted-phrases=restricted",
                            "app.admin.mail-inspection.matchers.ip2location.sender-domain=ip2location.io",
                            "app.admin.mail-inspection.matchers.ip2location.subject-keyword=ip2location");

    @Test
    void defaultsMatchApprovedOperationalLimits() {
        AdminMailInspectionProperties properties =
                AdminMailInspectionProperties.defaults();

        assertThat(properties.proxy().host()).isEqualTo("127.0.0.1");
        assertThat(properties.proxy().port()).isEqualTo(7897);
        assertThat(properties.oauth().maxAttempts()).isEqualTo(3);
        assertThat(properties.oauth().concurrency()).isEqualTo(8);
        assertThat(properties.imap().concurrency()).isEqualTo(4);
        assertThat(properties.job().activeLease())
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.job().terminalRetention())
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.job().maxCredentialLines()).isEqualTo(10_000);
        assertThat(properties.job().resultBucketSize()).isEqualTo(32);
        assertThat(properties.job().snapshotBatchSize()).isEqualTo(100);
        assertThat(properties.job().maxRequestBytes()).isEqualTo(1_048_576);
        assertThat(properties.job().defaultBusinessConcurrency()).isEqualTo(4);
        assertThat(properties.job().maxBusinessConcurrency()).isEqualTo(64);
        assertThat(properties.rabbit().publishMaxAttempts()).isEqualTo(3);
        assertThat(properties.submission().incompleteRetention())
                .isEqualTo(Duration.ofHours(6));
        assertThat(properties.submission().publishConcurrency()).isEqualTo(8);
    }

    @Test
    void bindsCompleteConfigurationTree() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AdminMailInspectionProperties.class);
            AdminMailInspectionProperties properties =
                    context.getBean(AdminMailInspectionProperties.class);
            assertThat(properties.oauth().tokenUri().toString())
                    .isEqualTo("https://example.test/token");
            assertThat(properties.scan().folderOrder())
                    .containsExactly("Junk Email", "INBOX");
        });
    }

    /**
     * 仅启用配置属性绑定，不创建真实 WebClient、虚拟线程或外部连接。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AdminMailInspectionProperties.class)
    static class TestConfiguration {
    }
}
