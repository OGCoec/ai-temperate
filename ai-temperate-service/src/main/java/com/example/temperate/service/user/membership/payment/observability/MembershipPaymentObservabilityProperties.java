package com.example.temperate.service.user.membership.payment.observability;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 该配置是来约束会员支付耗时日志的启用、全量明细、确定性采样和慢请求阈值，不改变业务状态机或消息可靠性。
 */
@ConfigurationProperties(prefix = "app.membership-payment.observability")
public record MembershipPaymentObservabilityProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("false") boolean detailLogEnabled,
        @DefaultValue("0.01") double sampleRate,
        @DefaultValue("PT1S") Duration slowThreshold,
        Set<MembershipPaymentOperation> forceLogOperations,
        @DefaultValue("unavailable") String runId,
        @DefaultValue("false") boolean includePublicOrderId) {

    @ConstructorBinding
    public MembershipPaymentObservabilityProperties {
        if (!Double.isFinite(sampleRate) || sampleRate < 0D || sampleRate > 1D) {
            throw new IllegalArgumentException(
                    "Membership payment observability sample rate must be between zero and one.");
        }
        if (slowThreshold == null
                || slowThreshold.isZero()
                || slowThreshold.isNegative()) {
            throw new IllegalArgumentException(
                    "Membership payment observability slow threshold must be positive.");
        }
        forceLogOperations = forceLogOperations == null
                ? Set.of()
                : Set.copyOf(forceLogOperations);
        if (runId == null || !runId.matches("^[A-Za-z0-9_-]{1,128}$")) {
            throw new IllegalArgumentException(
                    "Membership payment observability run ID is unsafe for structured logs.");
        }
    }

    /**
     * 保留旧配置和既有测试的构造入口；未声明强制操作时继续使用慢阈值、失败和采样规则。
     */
    public MembershipPaymentObservabilityProperties(
            boolean enabled,
            boolean detailLogEnabled,
            double sampleRate,
            Duration slowThreshold,
            String runId,
            boolean includePublicOrderId) {
        this(
                enabled,
                detailLogEnabled,
                sampleRate,
                slowThreshold,
                Set.of(),
                runId,
                includePublicOrderId);
    }
}
