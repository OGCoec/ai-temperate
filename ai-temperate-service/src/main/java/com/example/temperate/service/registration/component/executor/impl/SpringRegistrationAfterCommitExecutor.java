package com.example.temperate.service.registration.component.executor.impl;

import com.example.temperate.service.registration.component.executor.RegistrationAfterCommitExecutor;
import com.example.temperate.service.registration.component.observer.RegistrationCleanupObserver;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 使用 Spring 事务同步在注册提交后执行 Redis 流程清理与幂等派生状态更新、在未提交时释放领取状态的实现。
 *
 * <p>外部派生更新与清理不能早于数据库提交，否则回滚会污染 Bloom 或删除仍有效的注册流程；提交后动作
 * 采用有限重试，耗尽时记录可观测失败而不进行无限重试。</p>
 */
@Component
public final class SpringRegistrationAfterCommitExecutor
        implements RegistrationAfterCommitExecutor {

    private static final int MAX_CLEANUP_ATTEMPTS = 3;
    private static final System.Logger LOGGER =
            System.getLogger(SpringRegistrationAfterCommitExecutor.class.getName());
    private final RegistrationCleanupObserver cleanupObserver;

    public SpringRegistrationAfterCommitExecutor(RegistrationCleanupObserver cleanupObserver) {
        this.cleanupObserver =
                Objects.requireNonNull(cleanupObserver, "cleanupObserver must not be null");
    }

    @Override
    public void execute(Runnable committedAction, Runnable notCommittedAction) {
        Objects.requireNonNull(committedAction, "committedAction must not be null");
        Objects.requireNonNull(notCommittedAction, "notCommittedAction must not be null");
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Registration cleanup requires an active transaction.");
        }
        // 事务完成状态决定两个动作的互斥分支：提交后更新派生状态并清理流程，回滚或未知状态释放完成领取权。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    cleanupAfterCommit(committedAction);
                    return;
                }
                releaseAfterNonCommit(notCommittedAction, status);
            }
        });
    }

    private void cleanupAfterCommit(Runnable action) {
        for (int attempt = 1; attempt <= MAX_CLEANUP_ATTEMPTS; attempt++) {
            try {
                action.run();
                return;
            } catch (RuntimeException exception) {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "event=registration_post_commit_action_retry_failed attempt="
                                + attempt
                                + " maxAttempts="
                                + MAX_CLEANUP_ATTEMPTS,
                        exception);
            }
        }
        cleanupObserver.cleanupExhausted(MAX_CLEANUP_ATTEMPTS);
    }

    private static void releaseAfterNonCommit(Runnable action, int status) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "event=registration_completion_claim_release_failed transactionStatus="
                            + status,
                    exception);
        }
    }
}
