package com.example.temperate.service.admin.mailinspection.imap;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * 使用单例虚拟线程 Scheduler 隔离 Jakarta Mail 阻塞，并以全局信号量限制 IMAP 连接数。
 */
@Component
public final class MicrosoftMailboxImapClientImpl
        implements MicrosoftMailboxImapClient {

    private final AdminMailInspectionProperties properties;
    private final BlockingMailboxScanner scanner;
    private final Scheduler scheduler;
    private final Function<Integer, Duration> retryDelay;
    private final Semaphore concurrencyGate;

    @Autowired
    public MicrosoftMailboxImapClientImpl(
            AdminMailInspectionProperties properties,
            @Qualifier("adminMailInspectionImapScheduler") Scheduler scheduler) {
        this(
                properties,
                new JakartaMailBlockingMailboxScanner(properties),
                scheduler,
                attempt -> Duration.ofSeconds(1L << Math.max(0, attempt - 1)));
    }

    MicrosoftMailboxImapClientImpl(
            AdminMailInspectionProperties properties,
            BlockingMailboxScanner scanner,
            Scheduler scheduler,
            Function<Integer, Duration> retryDelay) {
        this.properties = Objects.requireNonNull(properties);
        this.scanner = Objects.requireNonNull(scanner);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.retryDelay = Objects.requireNonNull(retryDelay);
        this.concurrencyGate = new Semaphore(properties.imap().concurrency(), true);
    }

    @Override
    public Mono<MailboxScanOutcome> scan(MailboxScanCommand command) {
        AtomicInteger attempts = new AtomicInteger();
        return attempt(command, attempts)
                .timeout(properties.imap().scanTimeout())
                .onErrorResume(TimeoutException.class, ignored -> Mono.just(
                        MailboxScanOutcome.failure(
                                MailInspectionResultStatus.IMAP_SCAN_TIMEOUT,
                                "imap_scan_timeout",
                                Math.max(1, attempts.get()),
                                true,
                                true,
                                routeLabel())));
    }

    private Mono<MailboxScanOutcome> attempt(
            MailboxScanCommand command,
            AtomicInteger attempts) {
        int attemptNumber = attempts.incrementAndGet();
        return Mono.fromCallable(() -> {
                    // 信号量等待发生在虚拟线程而非 Netty 事件线程，最多四个调用可持有真实 IMAP 连接。
                    concurrencyGate.acquire();
                    try {
                        return scanner.scan(command).withAttempts(attemptNumber);
                    } finally {
                        concurrencyGate.release();
                    }
                })
                .subscribeOn(scheduler)
                .onErrorResume(failure -> handleFailure(
                        command, attempts, attemptNumber, failure));
    }

    private Mono<MailboxScanOutcome> handleFailure(
            MailboxScanCommand command,
            AtomicInteger attempts,
            int attemptNumber,
            Throwable failure) {
        Throwable unwrapped = Exceptions.unwrap(failure);
        if (!(unwrapped instanceof MailboxImapFailureException classified)) {
            return Mono.just(MailboxScanOutcome.failure(
                    MailInspectionResultStatus.IMAP_TRANSIENT_EXHAUSTED,
                    "imap_unclassified_failure",
                    attemptNumber,
                    false,
                    false,
                    routeLabel()));
        }
        if (!classified.retryable()) {
            return Mono.just(MailboxScanOutcome.failure(
                    classified.status(),
                    classified.safeReason(),
                    attemptNumber,
                    false,
                    false,
                    routeLabel()));
        }
        if (attemptNumber >= properties.imap().maxAttempts()) {
            return Mono.just(MailboxScanOutcome.failure(
                    classified.status(),
                    classified.safeReason(),
                    attemptNumber,
                    true,
                    true,
                    routeLabel()));
        }
        return Mono.delay(retryDelay.apply(attemptNumber))
                .then(attempt(command, attempts));
    }

    private String routeLabel() {
        return "SOCKS "
                + properties.proxy().host()
                + ":"
                + properties.proxy().port();
    }
}
