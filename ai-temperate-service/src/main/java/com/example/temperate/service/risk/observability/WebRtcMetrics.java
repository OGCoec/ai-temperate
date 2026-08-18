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
            "matched", "mismatch", "empty", "pending", "timeout", "stale",
            "invalid", "network_changed", "family_incomplete");
    private static final Set<String> INTERCEPTOR_DECISIONS = Set.of(
            "allowed", "pending_allowed", "required_allowed", "required",
            "failed", "blocked", "invalid");
    private static final Set<String> TRANSITIONS = Set.of(
            "required_created", "required_started", "required_timeout",
            "pending_verified", "pending_failed", "stale_report",
            "generation_changed");
    private static final Set<String> TRANSITION_REASONS = Set.of(
            "none", "start_timeout", "report_timeout", "no_public_candidate",
            "ip_family_incomplete", "ip_mismatch", "stale", "network_changed");
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

    /**
     * 记录 WebRTC v7 状态迁移；事件与原因均使用固定白名单，避免 generation 或敏感标识形成高基数标签。
     */
    public void transition(
            RiskScope scope,
            String transition,
            String platform,
            String reason,
            NetworkRiskMode mode) {
        meterRegistry.counter(
                        "webrtc_state_transition_total",
                        "scope", scope(scope),
                        "transition", bounded(transition, TRANSITIONS),
                        "platform", bounded(platform, PLATFORMS),
                        "reason", bounded(reason, TRANSITION_REASONS),
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
