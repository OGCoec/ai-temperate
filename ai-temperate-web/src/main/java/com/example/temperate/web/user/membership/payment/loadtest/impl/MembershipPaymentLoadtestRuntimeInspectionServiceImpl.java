package com.example.temperate.web.user.membership.payment.loadtest.impl;

import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentWorker;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCoordinator;
import com.example.temperate.web.user.membership.payment.loadtest.MembershipPaymentLoadtestRuntimeInspectionService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.search.Search;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 该实现是来从唯一 Hikari 数据源、Micrometer、支付指标和 Redis 写入协调器读取本机复测快照，且不触发数据库或 Redis I/O。
 */
@Service
@Profile("loadtest-realtime")
@ConditionalOnProperty(
        prefix = "app.membership-payment.loadtest",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentLoadtestRuntimeInspectionServiceImpl
        implements MembershipPaymentLoadtestRuntimeInspectionService {

    private final HikariDataSource dataSource;
    private final MeterRegistry meterRegistry;
    private final MembershipPaymentMetrics paymentMetrics;
    private final MembershipOrderSnapshotWriteCoordinator writeCoordinator;

    public MembershipPaymentLoadtestRuntimeInspectionServiceImpl(
            HikariDataSource dataSource,
            MeterRegistry meterRegistry,
            MembershipPaymentMetrics paymentMetrics,
            MembershipOrderSnapshotWriteCoordinator writeCoordinator) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        this.paymentMetrics = Objects.requireNonNull(paymentMetrics);
        this.writeCoordinator = Objects.requireNonNull(writeCoordinator);
    }

    @Override
    public RuntimeProbe inspect() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        String poolName = Objects.requireNonNullElse(
                dataSource.getPoolName(), "unavailable");
        HikariProbe hikari = new HikariProbe(
                pool != null,
                poolName,
                dataSource.getMaximumPoolSize(),
                dataSource.getMinimumIdle(),
                pool == null ? 0 : pool.getTotalConnections(),
                pool == null ? 0 : pool.getActiveConnections(),
                pool == null ? 0 : pool.getIdleConnections(),
                pool == null ? 0 : pool.getThreadsAwaitingConnection(),
                counter("hikaricp.connections.timeout", poolName),
                timer("hikaricp.connections.acquire", poolName),
                timer("hikaricp.connections.usage", poolName));
        return new RuntimeProbe(
                System.currentTimeMillis(),
                hikari,
                paymentMetrics.workerSnapshot(MembershipPaymentWorker.CALLBACK),
                paymentMetrics.workerSnapshot(MembershipPaymentWorker.ORDER_PERSIST),
                writeCoordinator.runtimeSnapshot());
    }

    private double counter(String name, String poolName) {
        Counter counter = search(name, poolName).counter();
        return counter == null ? 0.0D : counter.count();
    }

    private TimerProbe timer(String name, String poolName) {
        Timer timer = search(name, poolName).timer();
        if (timer == null) {
            return TimerProbe.empty();
        }
        ValueAtPercentile[] percentiles = timer.takeSnapshot().percentileValues();
        return new TimerProbe(
                timer.count(),
                timer.totalTime(TimeUnit.SECONDS),
                timer.max(TimeUnit.SECONDS),
                percentile(percentiles, 0.95D),
                percentile(percentiles, 0.99D));
    }

    private Search search(String name, String poolName) {
        Search search = meterRegistry.find(name);
        return "unavailable".equals(poolName)
                ? search
                : search.tag("pool", poolName);
    }

    private static double percentile(
            ValueAtPercentile[] values,
            double requestedPercentile) {
        for (ValueAtPercentile value : values) {
            if (Math.abs(value.percentile() - requestedPercentile) < 0.0001D) {
                return value.value(TimeUnit.SECONDS);
            }
        }
        return 0.0D;
    }
}
