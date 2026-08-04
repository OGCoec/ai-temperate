package com.example.temperate.service.auth.session.access.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 使用无高基数标签的 Micrometer Counter 记录普通用户会话认证结果。
 */
@Component
public final class MicrometerAccessSessionMetrics implements AccessSessionMetrics {

    private final Counter accessValid;
    private final Counter accessRenewed;
    private final Counter refreshInvalid;
    private final Counter accessInvalid;
    private final Counter sessionMismatch;
    private final Counter ttlInvariantViolation;
    private final Counter infrastructureFailure;

    public MicrometerAccessSessionMetrics(MeterRegistry meterRegistry) {
        MeterRegistry registry = Objects.requireNonNull(meterRegistry);
        accessValid = registry.counter("session_access_valid");
        accessRenewed = registry.counter("session_access_renewed");
        refreshInvalid = registry.counter("session_refresh_invalid");
        accessInvalid = registry.counter("session_access_invalid");
        sessionMismatch = registry.counter("session_mismatch");
        ttlInvariantViolation = registry.counter("session_ttl_invariant_violation");
        infrastructureFailure = registry.counter("session_auth_infrastructure_failure");
    }

    @Override
    public void accessValid() {
        accessValid.increment();
    }

    @Override
    public void accessRenewed() {
        accessRenewed.increment();
    }

    @Override
    public void refreshInvalid() {
        refreshInvalid.increment();
    }

    @Override
    public void accessInvalid() {
        accessInvalid.increment();
    }

    @Override
    public void sessionMismatch() {
        sessionMismatch.increment();
    }

    @Override
    public void ttlInvariantViolation() {
        ttlInvariantViolation.increment();
    }

    @Override
    public void infrastructureFailure() {
        infrastructureFailure.increment();
    }
}
