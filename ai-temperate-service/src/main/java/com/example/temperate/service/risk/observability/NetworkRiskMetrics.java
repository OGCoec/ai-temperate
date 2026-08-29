package com.example.temperate.service.risk.observability;

import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.ipintel.domain.ExternalIpProviderType;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSource;
import com.example.temperate.service.risk.ipintel.domain.ProviderFailureType;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 该类是来记录网络风险的低基数决策、Redis 批量与失败指标，不接收 IP、Token、设备标识或完整摘要。
 */
@Component
public final class NetworkRiskMetrics {

    private final MeterRegistry meterRegistry;

    public NetworkRiskMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    public void decision(
            RiskScope scope,
            RiskDecision decision,
            String phase,
            IpIntelligenceSource source) {
        meterRegistry.counter(
                        "network.risk.decision",
                        "scope", scope.name().toLowerCase(Locale.ROOT),
                        "decision", decision.name().toLowerCase(Locale.ROOT),
                        "phase", phase,
                        "source", source == null
                                ? "none"
                                : source.name().toLowerCase(Locale.ROOT))
                .increment();
    }

    public void rejection(RiskScope scope, String reason) {
        meterRegistry.counter(
                        "network.risk.rejection",
                        "scope", scope.name().toLowerCase(Locale.ROOT),
                        "reason", reason)
                .increment();
    }

    public void ipIntelligenceCache(String outcome) {
        meterRegistry.counter(
                        "network.risk.ipintel.cache",
                        "outcome", outcome)
                .increment();
    }

    public void ipIntelligenceLookup(String outcome) {
        meterRegistry.counter(
                        "network.risk.ipintel.lookup",
                        "outcome", outcome)
                .increment();
    }

    public void provider(
            ExternalIpProviderType provider,
            ProviderFailureType failure,
            boolean success) {
        meterRegistry.counter(
                        "network.risk.ipintel.provider",
                        "provider", provider.name().toLowerCase(Locale.ROOT),
                        "outcome", success ? "success" : "failure",
                        "failure", failure.name().toLowerCase(Locale.ROOT))
                .increment();
    }

    public void challenge(RiskScope scope, String event) {
        meterRegistry.counter(
                        "network.risk.challenge",
                        "scope", scope.name().toLowerCase(Locale.ROOT),
                        "event", event)
                .increment();
    }

    /**
     * 记录 IP2Location Redis Pipeline 的耗时、项目数和固定结果类型，不接收凭据标识。
     */
    public void ip2LocationRedis(
            Duration elapsed,
            String operation,
            String outcome,
            int itemCount) {
        String safeOperation = "write_pipeline".equals(operation)
                ? operation : "other";
        String safeOutcome = switch (outcome) {
            case "success", "failed", "created", "updated", "duplicate",
                    "capacity_rejected" -> outcome;
            default -> "failed";
        };
        Timer.builder("network.risk.ip2location.redis.duration")
                .tag("operation", safeOperation)
                .tag("outcome", safeOutcome)
                .register(meterRegistry)
                .record(elapsed);
        DistributionSummary.builder("network.risk.ip2location.redis.batches")
                .tag("operation", safeOperation)
                .tag("outcome", safeOutcome)
                .register(meterRegistry)
                .record(1);
        DistributionSummary.builder("network.risk.ip2location.redis.items")
                .tag("operation", safeOperation)
                .tag("outcome", safeOutcome)
                .register(meterRegistry)
                .record(Math.max(0, itemCount));
    }

    public void ip2LocationResult(String outcome) {
        String safeOutcome = switch (outcome) {
            case "created", "updated", "duplicate", "capacity_rejected" -> outcome;
            default -> "failed";
        };
        meterRegistry.counter(
                        "network.risk.ip2location.redis.results",
                        "operation", "write_pipeline",
                        "outcome", safeOutcome)
                .increment();
    }
}
