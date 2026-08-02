package com.example.temperate.service.user.profile.cache.impl;

import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import com.example.temperate.service.user.profile.cache.UserProfileCacheStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在用户资料数据库事务提交后执行最多三次 Redis 缓存失效。
 *
 * <p>删除失败不会反向回滚已提交的 PostgreSQL 状态；耗尽重试后只记录固定指标和脱敏日志，旧值最终由
 * 五至十五分钟 TTL 收敛。</p>
 */
@Component
public final class SpringUserProfileCacheInvalidationExecutor
        implements UserProfileCacheInvalidationExecutor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(SpringUserProfileCacheInvalidationExecutor.class);
    private static final int MAX_ATTEMPTS = 3;

    private final UserProfileCacheStore cacheStore;
    private final Counter exhaustedCounter;

    public SpringUserProfileCacheInvalidationExecutor(
            UserProfileCacheStore cacheStore,
            MeterRegistry meterRegistry) {
        this.cacheStore = Objects.requireNonNull(cacheStore);
        this.exhaustedCounter = Objects.requireNonNull(meterRegistry)
                .counter("user.profile.cache.evict.exhausted");
    }

    @Override
    public void evictAfterCommit(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("Cache eviction requires a positive user ID.");
        }
        evictAfterCommit(List.of(userId));
    }

    @Override
    public void evictAfterCommit(Collection<Long> userIds) {
        List<Long> distinctUserIds = userIds.stream()
                .distinct()
                .toList();
        if (distinctUserIds.isEmpty() || distinctUserIds.size() > 500
                || distinctUserIds.stream().anyMatch(userId -> userId <= 0L)) {
            throw new IllegalArgumentException(
                    "Cache eviction batch requires one to five hundred positive user IDs.");
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "User profile cache eviction requires an active transaction.");
        }
        // 删除动作只挂载到提交分支，避免数据库回滚后错误清除仍然有效的资料快照。
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        logAiLifecycle("PROFILE_CACHE_EVICTION_STARTED", null, 0L);
                        evictWithRetry(distinctUserIds);
                    }
                });
    }

    private void evictWithRetry(List<Long> userIds) {
        long startedNanos = System.nanoTime();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                cacheStore.evict(userIds);
                logAiLifecycle(
                        "PROFILE_CACHE_EVICTION_COMPLETED",
                        attempt,
                        elapsedMillis(startedNanos));
                return;
            } catch (RuntimeException exception) {
                logAiLifecycle(
                        "PROFILE_CACHE_EVICTION_RETRY",
                        attempt,
                        elapsedMillis(startedNanos));
                LOGGER.warn(
                        "event=user_profile_cache_evict_retry_failed attempt={} maxAttempts={} cause={}",
                        attempt,
                        MAX_ATTEMPTS,
                        exception.getClass().getSimpleName());
            }
        }
        exhaustedCounter.increment();
        logAiLifecycle(
                "PROFILE_CACHE_EVICTION_FAILED",
                MAX_ATTEMPTS,
                elapsedMillis(startedNanos));
    }

    private static void logAiLifecycle(
            String phase,
            Integer attempt,
            long durationMs) {
        if (!"true".equals(MDC.get("aiLifecycleDiagnosticSampled"))) {
            return;
        }
        LOGGER.info(
                "event=ai_conversation_lifecycle traceId={} clientRequestId={} "
                        + "usagePublicId={} conversationPublicId={} modelPublicId={} "
                        + "phase={} outcome=unavailable reactorSignal=unavailable "
                        + "finishReason=unavailable failureCode=unavailable "
                        + "billingAction=unavailable billingStatus=unavailable "
                        + "hasVisibleOutput=unavailable hasReportedUsage=unavailable "
                        + "emittedTextCharacters=unavailable attempt={} elapsedMs={} "
                        + "phaseDurationMs={} queueDelayMs=unavailable "
                        + "lifecycleStateBefore=unavailable "
                        + "lifecycleStateAfter=unavailable thread={}",
                safeMdc("traceId"),
                safeMdc("aiClientRequestId"),
                safeMdc("aiUsagePublicId"),
                safeMdc("aiConversationPublicId"),
                safeMdc("aiModelPublicId"),
                phase,
                attempt == null ? "unavailable" : attempt,
                requestElapsedMillis(),
                durationMs,
                Thread.currentThread().getName());
    }

    private static String safeMdc(String key) {
        String value = MDC.get(key);
        return value == null || value.isBlank() ? "unavailable" : value;
    }

    private static String requestElapsedMillis() {
        try {
            long startedNanos = Long.parseLong(
                    safeMdc("aiRequestStartedNanos"));
            return startedNanos <= 0L
                    ? "unavailable"
                    : Long.toString(elapsedMillis(startedNanos));
        } catch (NumberFormatException exception) {
            return "unavailable";
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
