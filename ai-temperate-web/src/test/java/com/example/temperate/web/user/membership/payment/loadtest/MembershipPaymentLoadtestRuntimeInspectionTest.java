package com.example.temperate.web.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentWorker;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCoordinator;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteRuntimeSnapshot;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * 该测试是来锁定本机正式复测运行指标只通过回环端点暴露，并准确聚合 Hikari 与两个自然 Worker 的只读证据。
 */
final class MembershipPaymentLoadtestRuntimeInspectionTest {

    @Test
    void exposesHikariAndWorkerEvidenceOnlyOnRealtimeLoopback() throws Exception {
        HikariDataSource dataSource = mock(HikariDataSource.class);
        HikariPoolMXBean pool = mock(HikariPoolMXBean.class);
        when(dataSource.getHikariPoolMXBean()).thenReturn(pool);
        when(dataSource.getMaximumPoolSize()).thenReturn(96);
        when(dataSource.getMinimumIdle()).thenReturn(8);
        when(dataSource.getPoolName()).thenReturn("membershipPool");
        when(pool.getTotalConnections()).thenReturn(40);
        when(pool.getActiveConnections()).thenReturn(32);
        when(pool.getIdleConnections()).thenReturn(8);
        when(pool.getThreadsAwaitingConnection()).thenReturn(3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.counter(
                        "hikaricp.connections.timeout",
                        "pool", "membershipPool")
                .increment(2.0D);
        Timer.builder("hikaricp.connections.acquire")
                .tag("pool", "membershipPool")
                .publishPercentiles(0.95D, 0.99D)
                .register(registry)
                .record(20L, TimeUnit.MILLISECONDS);
        Timer.builder("hikaricp.connections.usage")
                .tag("pool", "membershipPool")
                .publishPercentiles(0.95D, 0.99D)
                .register(registry)
                .record(80L, TimeUnit.MILLISECONDS);
        MembershipPaymentMetrics metrics = new MembershipPaymentMetrics(registry);
        MembershipOrderSnapshotWriteCoordinator writeCoordinator =
                mock(MembershipOrderSnapshotWriteCoordinator.class);
        when(writeCoordinator.runtimeSnapshot()).thenReturn(
                new MembershipOrderSnapshotWriteRuntimeSnapshot(
                        true,
                        64,
                        6,
                        384,
                        17,
                        367,
                        java.util.List.of(7, 6, 0, 0, 0, 0),
                        java.util.List.of(2, 2, 0, 0, 0, 0),
                        java.util.List.of(9, 8, 0, 0, 0, 0)));
        metrics.workerRunCompleted(
                MembershipPaymentWorker.CALLBACK,
                50,
                5_000,
                "capacity",
                100L,
                "membership-payment-callback-1");
        metrics.workerRunCompleted(
                MembershipPaymentWorker.ORDER_PERSIST,
                25,
                2_500,
                "drained",
                200L,
                "membership-payment-order-persist-1");

        Class<?> serviceType = Class.forName(
                "com.example.temperate.web.user.membership.payment.loadtest."
                        + "MembershipPaymentLoadtestRuntimeInspectionService");
        Class<?> implementationType = Class.forName(
                "com.example.temperate.web.user.membership.payment.loadtest.impl."
                        + "MembershipPaymentLoadtestRuntimeInspectionServiceImpl");
        Object service = implementationType
                .getConstructor(
                        HikariDataSource.class,
                        io.micrometer.core.instrument.MeterRegistry.class,
                        MembershipPaymentMetrics.class,
                        MembershipOrderSnapshotWriteCoordinator.class)
                .newInstance(dataSource, registry, metrics, writeCoordinator);
        Object probe = serviceType.getMethod("inspect").invoke(service);
        Object hikari = value(probe, "hikari");
        assertThat(value(hikari, "poolAvailable")).isEqualTo(true);
        assertThat(value(hikari, "configuredMaximumPoolSize")).isEqualTo(96);
        assertThat(value(hikari, "configuredMinimumIdle")).isEqualTo(8);
        assertThat(value(hikari, "totalConnections")).isEqualTo(40);
        assertThat(value(hikari, "activeConnections")).isEqualTo(32);
        assertThat(value(hikari, "idleConnections")).isEqualTo(8);
        assertThat(value(hikari, "pendingThreads")).isEqualTo(3);
        assertThat((double) value(hikari, "timeoutCount")).isEqualTo(2.0D);
        assertThat(value(value(probe, "callbackWorker"), "maximumClaimedItems"))
                .isEqualTo(5_000);
        assertThat(value(value(probe, "orderPersistWorker"), "maximumClaimedItems"))
                .isEqualTo(2_500);
        Object redisWrite = value(probe, "redisWrite");
        assertThat(value(redisWrite, "accepting")).isEqualTo(true);
        assertThat(value(redisWrite, "configuredBatchSize")).isEqualTo(64);
        assertThat(value(redisWrite, "configuredLaneCount")).isEqualTo(6);
        assertThat(value(redisWrite, "maximumInflight")).isEqualTo(384);
        assertThat(value(redisWrite, "inflight")).isEqualTo(17);
        assertThat(value(redisWrite, "availablePermits")).isEqualTo(367);
        assertThat(value(redisWrite, "fullRestoreQueueDepths"))
                .isEqualTo(java.util.List.of(7, 6, 0, 0, 0, 0));
        assertThat(value(redisWrite, "paymentAttemptPatchQueueDepths"))
                .isEqualTo(java.util.List.of(2, 2, 0, 0, 0, 0));
        assertThat(value(redisWrite, "queueDepths"))
                .isEqualTo(java.util.List.of(9, 8, 0, 0, 0, 0));

        Class<?> controllerType = Class.forName(
                "com.example.temperate.web.user.membership.payment.loadtest."
                        + "MembershipPaymentLoadtestRuntimeInspectionController");
        Object proxy = Proxy.newProxyInstance(
                serviceType.getClassLoader(),
                new Class<?>[] {serviceType},
                (ignored, method, arguments) -> probe);
        Object controller = controllerType.getConstructor(serviceType).newInstance(proxy);
        MockHttpServletRequest loopback = new MockHttpServletRequest();
        loopback.setRemoteAddr("127.0.0.1");
        ResponseEntity<?> response = (ResponseEntity<?>) controllerType
                .getMethod("runtime", jakarta.servlet.http.HttpServletRequest.class)
                .invoke(controller, loopback);
        assertThat(response.getBody()).isSameAs(probe);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(controllerType.getAnnotation(Profile.class).value())
                .containsExactly("loadtest-realtime");

        MockHttpServletRequest remote = new MockHttpServletRequest();
        remote.setRemoteAddr("198.51.100.20");
        assertThatThrownBy(() -> controllerType
                        .getMethod("runtime", jakarta.servlet.http.HttpServletRequest.class)
                        .invoke(controller, remote))
                .isInstanceOf(InvocationTargetException.class)
                .cause()
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    private static Object value(Object record, String accessor) throws Exception {
        return record.getClass().getMethod(accessor).invoke(record);
    }
}
