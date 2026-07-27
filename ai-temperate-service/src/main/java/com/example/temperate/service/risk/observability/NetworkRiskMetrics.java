package com.example.temperate.service.risk.observability;

import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.ipintel.domain.ExternalIpProviderType;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSource;
import com.example.temperate.service.risk.ipintel.domain.ProviderFailureType;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 记录网络风险的低基数决策与失败指标，不接收 IP、Token、设备标识或任何完整摘要。
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
}
