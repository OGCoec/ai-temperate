package com.example.temperate.service.risk.observability;

import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.domain.RiskScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 记录 WebRTC 校验与拦截的固定低基数结果，禁止接收 IP、Token、设备标识或 STUN 地址。
 */
@Component
public final class WebRtcMetrics {

    private static final Set<String> VERIFICATION_OUTCOMES = Set.of(
            "matched", "mismatch", "empty", "invalid", "network_changed");
    private static final Set<String> INTERCEPTOR_DECISIONS = Set.of(
            "allowed", "required", "failed", "blocked");
    private static final Set<String> PLATFORMS = Set.of("h5", "android");

    private final MeterRegistry meterRegistry;

    public WebRtcMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    public void verification(
            RiskScope scope,
            String outcome,
            String platform,
            NetworkRiskMode mode) {
        meterRegistry.counter(
                        "webrtc_verification_total",
                        "scope", scope(scope),
                        "outcome", bounded(outcome, VERIFICATION_OUTCOMES),
                        "platform", bounded(platform, PLATFORMS),
                        "mode", mode(mode))
                .increment();
    }

    public void interceptor(
            RiskScope scope,
            String decision,
            String platform,
            NetworkRiskMode mode) {
        meterRegistry.counter(
                        "webrtc_interceptor_total",
                        "scope", scope(scope),
                        "decision", bounded(decision, INTERCEPTOR_DECISIONS),
                        "platform", bounded(platform, PLATFORMS),
                        "mode", mode(mode))
                .increment();
    }

    private static String bounded(String value, Set<String> allowed) {
        String normalized = value == null
                ? ""
                : value.toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException("Metric label is outside the bounded set.");
        }
        return normalized;
    }

    private static String scope(RiskScope scope) {
        return Objects.requireNonNull(scope).name().toLowerCase(Locale.ROOT);
    }

    private static String mode(NetworkRiskMode mode) {
        return Objects.requireNonNull(mode).name().toLowerCase(Locale.ROOT);
    }
}
