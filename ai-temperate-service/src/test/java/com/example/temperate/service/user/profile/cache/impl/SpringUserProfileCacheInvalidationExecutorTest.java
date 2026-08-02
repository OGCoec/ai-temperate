package com.example.temperate.service.user.profile.cache.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.temperate.service.user.profile.cache.UserProfileCacheStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 验证用户资料缓存只在数据库提交后失效，并在三次失败后由 TTL 继续兜底。
 */
final class SpringUserProfileCacheInvalidationExecutorTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void retriesEvictionThreeTimesAfterCommitAndContainsFailure() {
        UserProfileCacheStore store = mock(UserProfileCacheStore.class);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(store).evict(java.util.List.of(10001L));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SpringUserProfileCacheInvalidationExecutor executor =
                new SpringUserProfileCacheInvalidationExecutor(store, registry);
        TransactionSynchronizationManager.initSynchronization();

        executor.evictAfterCommit(10001L);
        TransactionSynchronization synchronization =
                TransactionSynchronizationManager.getSynchronizations().getFirst();

        assertThatCode(synchronization::afterCommit).doesNotThrowAnyException();
        verify(store, times(3)).evict(java.util.List.of(10001L));
        assertThat(registry.get("user.profile.cache.evict.exhausted").counter().count())
                .isEqualTo(1D);
    }

    @Test
    void doesNotEvictWhenTransactionNeverCommits() {
        UserProfileCacheStore store = mock(UserProfileCacheStore.class);
        SpringUserProfileCacheInvalidationExecutor executor =
                new SpringUserProfileCacheInvalidationExecutor(
                        store,
                        new SimpleMeterRegistry());
        TransactionSynchronizationManager.initSynchronization();

        executor.evictAfterCommit(10001L);
        TransactionSynchronizationManager.clearSynchronization();

        verifyNoInteractions(store);
    }
}
