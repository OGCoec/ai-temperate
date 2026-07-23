package com.example.temperate.service.auth.login.audit.observer.impl;

import com.example.temperate.service.auth.login.audit.enums.LoginAuditOutcome;
import com.example.temperate.service.auth.login.audit.enums.LoginAuditReason;
import com.example.temperate.service.auth.login.audit.observer.LoginAuditObserver;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 将登录结果转换为 Micrometer 计数器指标的观测器实现。
 *
 * <p>指标仅使用受控枚举作为标签，避免将用户标识、IP、Token 等高基数或敏感值写入监控系统。</p>
 */
@Component
public final class MicrometerLoginAuditObserver implements LoginAuditObserver {

    private final MeterRegistry meterRegistry;

    public MicrometerLoginAuditObserver(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    @Override
    public void observe(LoginAuditOutcome outcome, LoginAuditReason reason) {
        meterRegistry.counter(
                        "auth.login.attempts",
                        "outcome", Objects.requireNonNull(outcome, "outcome must not be null").name(),
                        "reason", Objects.requireNonNull(reason, "reason must not be null").name())
                .increment();
    }
}
