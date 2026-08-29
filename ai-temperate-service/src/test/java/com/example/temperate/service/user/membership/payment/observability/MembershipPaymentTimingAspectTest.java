package com.example.temperate.service.user.membership.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.membership.payment.order.MembershipOrderCreateCommand;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderResult;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderService;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCoordinator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/**
 * 该测试是来约束总入口切面能命中Service接口代理，并且步骤切面只在已有总计时上下文时记录Redis分段。
 */
final class MembershipPaymentTimingAspectTest {

    @Test
    void operationAspectObservesServiceInterfaceProxy() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MembershipPaymentTimingRecorder recorder = recorder(registry);
        AspectJProxyFactory factory = new AspectJProxyFactory(new StubOrderService());
        factory.setProxyTargetClass(false);
        factory.setInterfaces(MembershipOrderService.class);
        factory.addAspect(new MembershipPaymentOperationTimingAspect(recorder));
        MembershipOrderService proxy = factory.getProxy();

        proxy.getOwned(1L, new byte[16]);

        assertThat(registry.get("membership_payment_operation_total")
                        .tag("operation", "order_get")
                        .tag("outcome", "success")
                        .counter()
                        .count())
                .isEqualTo(1D);
    }

    @Test
    void stepAspectRecordsRedisReadInsideActiveOperationOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MembershipPaymentTimingRecorder recorder = recorder(registry);
        MembershipOrderSnapshotStore target = mock(MembershipOrderSnapshotStore.class);
        when(target.find("order-id")).thenReturn(Optional.empty());
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(false);
        factory.setInterfaces(MembershipOrderSnapshotStore.class);
        factory.addAspect(new MembershipPaymentStepTimingAspect(recorder));
        MembershipOrderSnapshotStore proxy = factory.getProxy();
        MembershipPaymentTimingRecorder.Session session = recorder.start(
                MembershipPaymentOperation.ORDER_GET,
                new Object[0]);

        proxy.find("order-id");
        recorder.finish(session, null, null);

        assertThat(registry.get("membership_payment_step_duration")
                        .tag("step", "redis_order_read")
                        .tag("outcome", "success")
                        .timer()
                        .count())
                .isEqualTo(1L);
    }

    @Test
    void stepAspectRecordsCoordinatorWaitAsRedisOrderWrite() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MembershipPaymentTimingRecorder recorder = recorder(registry);
        MembershipOrderSnapshot snapshot = mock(MembershipOrderSnapshot.class);
        MembershipOrderSnapshotWriteCoordinator target =
                mock(MembershipOrderSnapshotWriteCoordinator.class);
        when(target.putAndGet(snapshot)).thenReturn(snapshot);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(false);
        factory.setInterfaces(MembershipOrderSnapshotWriteCoordinator.class);
        factory.addAspect(new MembershipPaymentStepTimingAspect(recorder));
        MembershipOrderSnapshotWriteCoordinator proxy = factory.getProxy();
        MembershipPaymentTimingRecorder.Session session = recorder.start(
                MembershipPaymentOperation.ORDER_CREATE,
                new Object[0]);

        proxy.putAndGet(snapshot);
        recorder.finish(session, null, null);

        assertThat(registry.get("membership_payment_step_duration")
                        .tag("step", "redis_order_write")
                        .tag("outcome", "success")
                        .timer()
                        .count())
                .isEqualTo(1L);
    }

    private static MembershipPaymentTimingRecorder recorder(
            SimpleMeterRegistry registry) {
        return new MembershipPaymentTimingRecorder(
                new MembershipPaymentMetrics(registry),
                new MembershipPaymentObservabilityProperties(
                        true,
                        false,
                        0D,
                        Duration.ofDays(1),
                        "unavailable",
                        false),
                Clock.systemUTC());
    }

    /**
     * 该桩实现是来提供无外部副作用的Service代理目标，只验证切点是否命中接口方法。
     */
    private static final class StubOrderService implements MembershipOrderService {

        @Override
        public MembershipOrderResult create(
                long loginIdentityId,
                MembershipOrderCreateCommand command) {
            return null;
        }

        @Override
        public MembershipOrderResult getOwned(long loginIdentityId, byte[] orderId) {
            return null;
        }

        @Override
        public MembershipOrderResult cancel(long loginIdentityId, byte[] orderId) {
            return null;
        }
    }
}
