package com.example.temperate.service.registration.component.observer.impl;

import com.example.temperate.service.registration.component.observer.RegistrationCleanupObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 将注册流程清理重试耗尽事件写入 Micrometer 指标和受控日志的观测实现。
 *
 * <p>指标标签为固定低基数值，不能加入流程 Token、邮箱、手机号或其他敏感上下文。</p>
 */
@Component
public final class MicrometerRegistrationCleanupObserver
        implements RegistrationCleanupObserver {

    static final String METRIC_NAME = "ait.registration.redis.cleanup.failures";
    static final String OPERATION_TAG = "flow_delete";
    static final String OUTCOME_TAG = "exhausted";

    private static final System.Logger LOGGER =
            System.getLogger(MicrometerRegistrationCleanupObserver.class.getName());

    private final Counter exhaustedCleanupCounter;

    @Autowired
    public MicrometerRegistrationCleanupObserver(
            ObjectProvider<MeterRegistry> meterRegistries) {
        this(meterRegistries.orderedStream()
                .findFirst()
                .orElse(Metrics.globalRegistry));
    }

    public MicrometerRegistrationCleanupObserver(MeterRegistry meterRegistry) {
        MeterRegistry registry =
                Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.exhaustedCleanupCounter = Counter.builder(METRIC_NAME)
                .description("Registration Redis flow cleanup retries exhausted")
                .tag("operation", OPERATION_TAG)
                .tag("outcome", OUTCOME_TAG)
                .register(registry);
    }

    @Override
    public void cleanupExhausted(int attempts) {
        exhaustedCleanupCounter.increment();
        LOGGER.log(
                System.Logger.Level.WARNING,
                "event=registration_redis_cleanup_exhausted operation=flow_delete "
                        + "outcome=exhausted attempts="
                        + attempts);
    }
}
