package com.example.temperate.service.registration.verification.delivery.observer.impl;

import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.verification.delivery.observer.VerificationDeliveryObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 基于 Micrometer 的验证码投递失败观察器。
 *
 * <p>用途：按通道累计投递失败次数并输出结构化告警日志。</p>
 *
 * <p>隐私原则：指标和日志只使用有限枚举通道及固定结果标签，不记录手机号、邮箱、验证码、令牌或 Redis Key。</p>
 */
@Component
public final class MicrometerVerificationDeliveryObserver
        implements VerificationDeliveryObserver {

    private static final String METRIC_NAME =
            "ait.registration.verification.delivery.failures";
    private static final System.Logger LOGGER =
            System.getLogger(MicrometerVerificationDeliveryObserver.class.getName());

    private final Map<VerificationChannel, Counter> failureCounters;

    @Autowired
    public MicrometerVerificationDeliveryObserver(
            ObjectProvider<MeterRegistry> meterRegistries) {
        this(meterRegistries.orderedStream()
                .findFirst()
                .orElse(Metrics.globalRegistry));
    }

    public MicrometerVerificationDeliveryObserver(MeterRegistry meterRegistry) {
        MeterRegistry registry =
                Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        // 启动时构造并冻结所有有限通道的 Counter，运行期仅做线程安全的 Counter 增量。
        EnumMap<VerificationChannel, Counter> counters =
                new EnumMap<>(VerificationChannel.class);
        for (VerificationChannel channel : VerificationChannel.values()) {
            counters.put(
                    channel,
                    Counter.builder(METRIC_NAME)
                            .description("Registration verification delivery failures")
                            .tag("channel", channel.name().toLowerCase(java.util.Locale.ROOT))
                            .tag("outcome", "failed")
                            .register(registry));
        }
        this.failureCounters = Map.copyOf(counters);
    }

    @Override
    public void deliveryFailed(VerificationChannel channel) {
        VerificationChannel validChannel =
                Objects.requireNonNull(channel, "channel must not be null");
        failureCounters.get(validChannel).increment();
        LOGGER.log(
                System.Logger.Level.WARNING,
                "event=registration_verification_delivery_failed channel="
                        + validChannel.name().toLowerCase(java.util.Locale.ROOT)
                        + " outcome=failed");
    }
}
