package com.example.temperate.service.admin.mailinspection.recovery.impl;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.MailInspectionAcceptanceState;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 把每种邮箱检查类型的恢复闸门、Marker 队列观测值和恢复结果写入 Micrometer。
 *
 * <p>所有标签均来自固定枚举，禁止使用 jobId、messageId、邮箱或幂等键，避免敏感信息和高基数标签。</p>
 */
@Component
public final class MicrometerMailInspectionRecoveryObserver
        implements MailInspectionRecoveryObserver {

    private final Map<MailInspectionType, Counter> recoverySucceeded;
    private final Map<MailInspectionType, Counter> recoveryFailed;
    private final Map<MailInspectionType, Timer> recoveryDuration;
    private final Map<MailInspectionType, Counter> cleanedMarkers;
    private final Map<MailInspectionType, Counter> nackFailures;
    private final Map<MailInspectionType, AtomicInteger> markerReady;
    private final Map<MailInspectionType, AtomicInteger> markerUnacked;

    public MicrometerMailInspectionRecoveryObserver(
            MeterRegistry meterRegistry,
            AdminMailInspectionJobStore jobStore) {
        MeterRegistry registry = Objects.requireNonNull(meterRegistry);
        AdminMailInspectionJobStore store =
                Objects.requireNonNull(jobStore);
        recoverySucceeded = counters(
                registry,
                "admin_mail_inspection_recovery_total",
                "success");
        recoveryFailed = counters(
                registry,
                "admin_mail_inspection_recovery_total",
                "failure");
        recoveryDuration = timers(registry);
        cleanedMarkers = counters(
                registry,
                "admin_mail_inspection_terminal_markers_cleaned_total",
                "cleaned");
        nackFailures = counters(
                registry,
                "admin_mail_inspection_nack_requeue_failure_total",
                "failure");
        markerReady = atomicValues();
        markerUnacked = atomicValues();
        for (MailInspectionType type : MailInspectionType.values()) {
            String typeTag = type.name();
            Gauge.builder(
                            "admin_mail_inspection_acceptance_state",
                            store,
                            value -> acceptanceValue(
                                    value.acceptanceState(type)))
                    .tag("inspectionType", typeTag)
                    .register(registry);
            Gauge.builder(
                            "admin_mail_inspection_marker_ready",
                            markerReady.get(type),
                            AtomicInteger::get)
                    .tag("inspectionType", typeTag)
                    .register(registry);
            Gauge.builder(
                            "admin_mail_inspection_marker_unacked",
                            markerUnacked.get(type),
                            AtomicInteger::get)
                    .tag("inspectionType", typeTag)
                    .register(registry);
        }
    }

    @Override
    public void recoveryCompleted(
            MailInspectionType type,
            boolean successful,
            Duration elapsed) {
        required(successful ? recoverySucceeded : recoveryFailed, type)
                .increment();
        required(recoveryDuration, type).record(elapsed);
    }

    @Override
    public void markersCleaned(
            MailInspectionType type,
            int count) {
        if (count > 0) {
            required(cleanedMarkers, type).increment(count);
        }
    }

    @Override
    public void markerQueueObserved(
            MailInspectionType type,
            int ready,
            int unacked) {
        required(markerReady, type).set(Math.max(0, ready));
        required(markerUnacked, type).set(Math.max(0, unacked));
    }

    @Override
    public void nackRequeueFailed(MailInspectionType type) {
        required(nackFailures, type).increment();
    }

    private static Map<MailInspectionType, Counter> counters(
            MeterRegistry registry,
            String name,
            String outcome) {
        EnumMap<MailInspectionType, Counter> values =
                new EnumMap<>(MailInspectionType.class);
        for (MailInspectionType type : MailInspectionType.values()) {
            values.put(
                    type,
                    Counter.builder(name)
                            .tag("inspectionType", type.name())
                            .tag("outcome", outcome)
                            .register(registry));
        }
        return Map.copyOf(values);
    }

    private static Map<MailInspectionType, Timer> timers(
            MeterRegistry registry) {
        EnumMap<MailInspectionType, Timer> values =
                new EnumMap<>(MailInspectionType.class);
        for (MailInspectionType type : MailInspectionType.values()) {
            values.put(
                    type,
                    Timer.builder(
                                    "admin_mail_inspection_recovery_duration")
                            .tag("inspectionType", type.name())
                            .register(registry));
        }
        return Map.copyOf(values);
    }

    private static Map<MailInspectionType, AtomicInteger> atomicValues() {
        EnumMap<MailInspectionType, AtomicInteger> values =
                new EnumMap<>(MailInspectionType.class);
        for (MailInspectionType type : MailInspectionType.values()) {
            values.put(type, new AtomicInteger());
        }
        return Map.copyOf(values);
    }

    private static double acceptanceValue(
            MailInspectionAcceptanceState state) {
        return switch (state) {
            case ACCEPTING -> 1D;
            case RECOVERING -> 0D;
            case UNAVAILABLE -> -1D;
            case STOPPED -> -2D;
        };
    }

    private static <T> T required(
            Map<MailInspectionType, T> values,
            MailInspectionType type) {
        T value = values.get(Objects.requireNonNull(type));
        if (value == null) {
            throw new IllegalArgumentException(
                    "unsupported mail inspection type");
        }
        return value;
    }
}
