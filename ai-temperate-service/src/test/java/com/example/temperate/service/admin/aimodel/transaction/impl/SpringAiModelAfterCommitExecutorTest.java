package com.example.temperate.service.admin.aimodel.transaction.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 验证 AI 模型缓存动作只在提交后执行，并在三次失败后记录指标而不反向改变数据库提交结果。
 */
final class SpringAiModelAfterCommitExecutorTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void retriesThreeTimesAfterCommitAndContainsTheFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SpringAiModelAfterCommitExecutor executor =
                new SpringAiModelAfterCommitExecutor(registry);
        Runnable action = mock(Runnable.class);
        doThrow(new IllegalStateException("redis unavailable")).when(action).run();
        TransactionSynchronizationManager.initSynchronization();

        executor.execute(action);
        TransactionSynchronization synchronization =
                TransactionSynchronizationManager.getSynchronizations().getFirst();

        assertThatCode(synchronization::afterCommit).doesNotThrowAnyException();
        verify(action, times(3)).run();
        assertThat(registry.get("ai.model.cache.refresh.exhausted").counter().count())
                .isEqualTo(1D);
    }
}
