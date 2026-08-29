package com.example.temperate.service.auth.identity.bloom.impl;

import com.example.temperate.service.auth.identity.bloom.IdentityPresenceBloomObserver;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceBloomSettings;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceDecision;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceKind;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceMutationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 该实现是来把身份 Bloom 的有限枚举结果、Redis 批量耗时和构建进度记录为低基数 Micrometer 指标。
 *
 * <p>指标标签只包含联系方式类型、状态和受控失败原因，不记录邮箱、手机号、用户 ID 或完整 Redis Key。</p>
 */
@Component
public final class MicrometerIdentityPresenceBloomObserver
        implements IdentityPresenceBloomObserver {

    private final MeterRegistry registry;
    private final Map<IdentityPresenceKind, Map<IdentityPresenceDecision, Counter>>
            queryCounters;
    private final Map<IdentityPresenceMutationResult, Counter> mutationCounters;
    private final Map<IdentityPresenceKind, Counter> falsePositiveCounters;
    private final Map<String, Counter> degradedCounters;
    private final Counter buildStarted;
    private final Counter buildCompleted;
    private final Counter buildFailed;
    private final Timer buildDuration;
    private final AtomicLong buildProgress = new AtomicLong();
    private final AtomicLong lifecycleState = new AtomicLong();

    public MicrometerIdentityPresenceBloomObserver(
            MeterRegistry meterRegistry,
            IdentityPresenceBloomSettings settings) {
        MeterRegistry registry = Objects.requireNonNull(meterRegistry);
        this.registry = registry;
        IdentityPresenceBloomSettings validSettings = Objects.requireNonNull(settings);
        this.queryCounters = queryCounters(registry);
        this.mutationCounters = mutationCounters(registry);
        this.falsePositiveCounters = falsePositiveCounters(registry);
        this.degradedCounters = degradedCounters(registry);
        this.buildStarted = Counter.builder("identity_presence_bloom_build_total")
                .tag("outcome", "started")
                .register(registry);
        this.buildCompleted = Counter.builder("identity_presence_bloom_build_total")
                .tag("outcome", "completed")
                .register(registry);
        this.buildFailed = Counter.builder("identity_presence_bloom_build_total")
                .tag("outcome", "failed")
                .register(registry);
        this.buildDuration = Timer.builder("identity_presence_bloom_build_duration")
                .register(registry);
        Gauge.builder(
                        "identity_presence_bloom_build_processed_elements",
                        buildProgress,
                        AtomicLong::doubleValue)
                .register(registry);
        Gauge.builder(
                        "identity_presence_bloom_lifecycle_state",
                        lifecycleState,
                        AtomicLong::doubleValue)
                .description("-1=DEGRADED, 0=UNINITIALIZED, 1=BUILDING, 2=READY, 3=ACTIVE")
                .register(registry);
        Gauge.builder(
                        "identity_presence_bloom_capacity_counters",
                        validSettings,
                        IdentityPresenceBloomSettings::capacity)
                .register(registry);
        Gauge.builder(
                        "identity_presence_bloom_estimated_false_positive_rate",
                        validSettings,
                        IdentityPresenceBloomSettings::estimatedFalsePositiveRateAtMaximumElements)
                .register(registry);
        Gauge.builder(
                        "identity_presence_bloom_estimated_counter_occupancy_ratio",
                        validSettings,
                        IdentityPresenceBloomSettings::estimatedCounterOccupancyAtMaximumElements)
                .description("达到配置元素上限时非零计数器的理论占比")
                .register(registry);
    }

    @Override
    public void query(IdentityPresenceKind kind, IdentityPresenceDecision decision) {
        queryCounters.get(kind).get(decision).increment();
    }

    @Override
    public void mutation(IdentityPresenceMutationResult result) {
        mutationCounters.get(result).increment();
    }

    @Override
    public void falsePositive(IdentityPresenceKind kind) {
        falsePositiveCounters.get(kind).increment();
    }

    @Override
    public void buildStarted() {
        buildProgress.set(0L);
        lifecycleState.set(1L);
        buildStarted.increment();
    }

    @Override
    public void buildProgress(long processedElements) {
        buildProgress.set(processedElements);
    }

    @Override
    public void buildReady() {
        lifecycleState.set(2L);
    }

    @Override
    public void buildCompleted(long processedElements, long durationNanos) {
        buildProgress.set(processedElements);
        lifecycleState.set(3L);
        buildCompleted.increment();
        buildDuration.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void buildFailed(String reason) {
        lifecycleState.set(-1L);
        buildFailed.increment();
    }

    @Override
    public void degraded(String reason) {
        lifecycleState.set(-1L);
        degradedCounters.getOrDefault(reason, degradedCounters.get("OTHER")).increment();
    }

    @Override
    public void redisOperation(
            String operation,
            String outcome,
            long durationNanos,
            int itemCount) {
        String safeOperation = switch (operation) {
            case "query", "add", "add_batch", "remove", "remove_batch" -> operation;
            default -> "query";
        };
        String safeOutcome = switch (outcome) {
            case "success", "unavailable", "failed" -> outcome;
            default -> "failed";
        };
        Timer.builder("identity_presence_bloom_redis_duration")
                .tag("operation", safeOperation)
                .tag("outcome", safeOutcome)
                .register(registry)
                .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
        DistributionSummary.builder("identity_presence_bloom_redis_batches")
                .tag("operation", safeOperation)
                .tag("outcome", safeOutcome)
                .register(registry)
                .record(1);
        DistributionSummary.builder("identity_presence_bloom_redis_batch_items")
                .tag("operation", safeOperation)
                .tag("outcome", safeOutcome)
                .register(registry)
                .record(Math.max(0, itemCount));
    }

    private static Map<IdentityPresenceKind, Map<IdentityPresenceDecision, Counter>>
            queryCounters(MeterRegistry registry) {
        EnumMap<IdentityPresenceKind, Map<IdentityPresenceDecision, Counter>> counters =
                new EnumMap<>(IdentityPresenceKind.class);
        for (IdentityPresenceKind kind : IdentityPresenceKind.values()) {
            EnumMap<IdentityPresenceDecision, Counter> outcomes =
                    new EnumMap<>(IdentityPresenceDecision.class);
            for (IdentityPresenceDecision decision : IdentityPresenceDecision.values()) {
                outcomes.put(
                        decision,
                        Counter.builder("identity_presence_bloom_query_total")
                                .tag("kind", kind.name().toLowerCase(Locale.ROOT))
                                .tag("outcome", decision.name().toLowerCase(Locale.ROOT))
                                .register(registry));
            }
            counters.put(kind, Map.copyOf(outcomes));
        }
        return Map.copyOf(counters);
    }

    private static Map<IdentityPresenceMutationResult, Counter> mutationCounters(
            MeterRegistry registry) {
        EnumMap<IdentityPresenceMutationResult, Counter> counters =
                new EnumMap<>(IdentityPresenceMutationResult.class);
        for (IdentityPresenceMutationResult result : IdentityPresenceMutationResult.values()) {
            counters.put(
                    result,
                    Counter.builder("identity_presence_bloom_mutation_total")
                            .tag("outcome", result.name().toLowerCase(Locale.ROOT))
                            .register(registry));
        }
        return Map.copyOf(counters);
    }

    private static Map<IdentityPresenceKind, Counter> falsePositiveCounters(
            MeterRegistry registry) {
        EnumMap<IdentityPresenceKind, Counter> counters =
                new EnumMap<>(IdentityPresenceKind.class);
        for (IdentityPresenceKind kind : IdentityPresenceKind.values()) {
            counters.put(
                    kind,
                    Counter.builder("identity_presence_bloom_false_positive_total")
                            .tag("kind", kind.name().toLowerCase(Locale.ROOT))
                            .register(registry));
        }
        return Map.copyOf(counters);
    }

    private static Map<String, Counter> degradedCounters(MeterRegistry registry) {
        Map<String, Counter> counters = new LinkedHashMap<>();
        for (String reason : new String[] {
                "CAPACITY_EXCEEDED",
                "COUNTER_OVERFLOW",
                "COUNTER_UNDERFLOW",
                "UPDATE_UNAVAILABLE",
                "UPDATE_FAILED",
                "QUERY_FAILED",
                "BUILD_UPDATE_UNAVAILABLE",
                "BUILD_FAILED",
                "OTHER"
        }) {
            counters.put(
                    reason,
                    Counter.builder("identity_presence_bloom_degraded_total")
                            .tag("reason", reason.toLowerCase(Locale.ROOT))
                            .register(registry));
        }
        return Map.copyOf(counters);
    }
}
