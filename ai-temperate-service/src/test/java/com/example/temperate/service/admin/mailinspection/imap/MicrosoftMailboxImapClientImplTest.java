package com.example.temperate.service.admin.mailinspection.imap;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * 验证阻塞 IMAP 扫描只在线程调度器中运行，并对临时 I/O 执行最多三次有限重试。
 */
final class MicrosoftMailboxImapClientImplTest {

    @Test
    void retriesTransientScannerFailureThenReturnsEvidence() {
        AtomicInteger calls = new AtomicInteger();
        BlockingMailboxScanner scanner = command -> {
            if (calls.incrementAndGet() < 3) {
                throw MailboxImapFailureException.retryable(
                        MailInspectionResultStatus.IMAP_NETWORK_EXHAUSTED,
                        "imap_network_failed",
                        null);
            }
            return MailboxScanOutcome.success(
                    0,
                    true,
                    "INBOX",
                    "sender",
                    "subject",
                    null,
                    "restricted",
                    "SOCKS 127.0.0.1:7897",
                    null,
                    null,
                    false);
        };
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var scheduler = Schedulers.fromExecutorService(executor);
        MicrosoftMailboxImapClient client = new MicrosoftMailboxImapClientImpl(
                AdminMailInspectionProperties.defaults(),
                scanner,
                scheduler,
                ignored -> Duration.ZERO);

        StepVerifier.create(client.scan(command()))
                .assertNext(outcome -> {
                    assertThat(outcome.successful()).isTrue();
                    assertThat(outcome.attempts()).isEqualTo(3);
                    assertThat(outcome.evidencePhrase()).isEqualTo("restricted");
                })
                .verifyComplete();

        scheduler.dispose();
        executor.close();
    }

    private static MailboxScanCommand command() {
        return new MailboxScanCommand(
                "owner@example.test",
                "access-secret",
                20,
                20,
                new KeywordEvidenceMessageMatcher(
                        List.of("sender"),
                        List.of("subject"),
                        List.of("restricted")));
    }
}
