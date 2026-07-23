package com.example.temperate.service.registration.component.executor.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.temperate.service.registration.component.observer.impl.MicrometerRegistrationCleanupObserver;
import com.example.temperate.service.registration.component.observer.RegistrationCleanupObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 验证注册事务提交后执行器在提交和回滚场景下分别执行清理与释放回调的测试。
 */
class SpringRegistrationAfterCommitExecutorTest {

    private final RegistrationCleanupObserver cleanupObserver =
            mock(RegistrationCleanupObserver.class);
    private final SpringRegistrationAfterCommitExecutor executor =
            new SpringRegistrationAfterCommitExecutor(cleanupObserver);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void refusesToRunCleanupBeforeATransactionCanCommit() {
        assertThatThrownBy(() -> executor.execute(() -> {}, () -> {}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void committedTransactionRetriesCleanupAtMostThreeTimes() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        executor.execute(
                () -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new IllegalStateException("temporary redis failure");
                    }
                },
                releases::incrementAndGet);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        TransactionSynchronizationManager.getSynchronizations()
                .getFirst()
                .afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED);

        assertThat(attempts).hasValue(3);
        assertThat(releases).hasValue(0);
        verify(cleanupObserver, never()).cleanupExhausted(3);
    }

    @Test
    void rollbackReleasesCompletionClaimWithoutDeletingFlow() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger cleanups = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        executor.execute(cleanups::incrementAndGet, releases::incrementAndGet);

        TransactionSynchronizationManager.getSynchronizations()
                .getFirst()
                .afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(cleanups).hasValue(0);
        assertThat(releases).hasValue(1);
    }

    @Test
    void commitFailureStatusReleasesCompletionClaim() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger cleanups = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        executor.execute(cleanups::incrementAndGet, releases::incrementAndGet);

        TransactionSynchronizationManager.getSynchronizations()
                .getFirst()
                .afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_UNKNOWN);

        assertThat(cleanups).hasValue(0);
        assertThat(releases).hasValue(1);
    }

    @Test
    void exhaustedCleanupIsObservedButDoesNotEscapeAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger attempts = new AtomicInteger();
        executor.execute(
                () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("redis unavailable");
                },
                () -> {});

        assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                        .getFirst()
                        .afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED))
                .doesNotThrowAnyException();

        assertThat(attempts).hasValue(3);
        verify(cleanupObserver).cleanupExhausted(3);
    }

    @Test
    void exhaustedCleanupIncrementsOneLowCardinalityCounterAndDoesNotEscape() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SpringRegistrationAfterCommitExecutor meteredExecutor =
                new SpringRegistrationAfterCommitExecutor(
                        new MicrometerRegistrationCleanupObserver(meterRegistry));
        TransactionSynchronizationManager.initSynchronization();
        meteredExecutor.execute(
                () -> {
                    throw new IllegalStateException("redis unavailable");
                },
                () -> {});

        assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                        .getFirst()
                        .afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED))
                .doesNotThrowAnyException();

        Counter counter = meterRegistry
                .find("ait.registration.redis.cleanup.failures")
                .tag("operation", "flow_delete")
                .tag("outcome", "exhausted")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0d);
        assertThat(counter.getId().getTags()).hasSize(2);
    }
}
