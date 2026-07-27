package com.example.temperate.service.admin.aimodel.transaction.impl;

import com.example.temperate.service.admin.aimodel.transaction.AiModelAfterCommitExecutor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 使用 Spring 事务同步在 AI 模型写入提交后执行有界缓存刷新重试。
 *
 * <p>三次失败后只记录结构化日志和指标，不回滚已经提交的 PostgreSQL 状态；缓存缺失或短暂旧值由
 * 数据库回源和 TTL 兜底。</p>
 */
@Component
public final class SpringAiModelAfterCommitExecutor implements AiModelAfterCommitExecutor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(SpringAiModelAfterCommitExecutor.class);
    private static final int MAX_ATTEMPTS = 3;

    private final Counter exhaustedCounter;

    public SpringAiModelAfterCommitExecutor(MeterRegistry meterRegistry) {
        this.exhaustedCounter = Objects.requireNonNull(meterRegistry)
                .counter("ai.model.cache.refresh.exhausted");
    }

    @Override
    public void execute(Runnable committedAction) {
        Objects.requireNonNull(committedAction, "committedAction must not be null");
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("AI model cache refresh requires an active transaction.");
        }
        // 缓存动作只挂载到提交分支，避免数据库回滚后发布不存在或错误状态的模型快照。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                refreshWithRetry(committedAction);
            }
        });
    }

    private void refreshWithRetry(Runnable action) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                action.run();
                return;
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "event=ai_model_cache_refresh_retry_failed attempt={} maxAttempts={}",
                        attempt,
                        MAX_ATTEMPTS,
                        exception);
            }
        }
        exhaustedCounter.increment();
    }
}
