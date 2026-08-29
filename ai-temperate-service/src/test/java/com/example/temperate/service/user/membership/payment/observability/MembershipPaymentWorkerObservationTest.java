package com.example.temperate.service.user.membership.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定支付 Worker 单轮观测只保存低基数运行事实，并持续保留启动后的最大批次与最大领取数量。
 */
final class MembershipPaymentWorkerObservationTest {

    @Test
    void keepsLatestAndMaximumRunEvidenceForEachWorker() throws Exception {
        MembershipPaymentMetrics metrics =
                new MembershipPaymentMetrics(new SimpleMeterRegistry());
        Class<?> workerType = Class.forName(
                "com.example.temperate.service.user.membership.payment.observability."
                        + "MembershipPaymentWorker");
        Object callback = enumValue(workerType, "CALLBACK");
        Method completed = MembershipPaymentMetrics.class.getMethod(
                "workerRunCompleted",
                workerType,
                int.class,
                int.class,
                String.class,
                long.class,
                String.class);

        completed.invoke(
                metrics,
                callback,
                21,
                2_100,
                "drained",
                25_000_000L,
                "membership-payment-callback-1");
        completed.invoke(
                metrics,
                callback,
                1,
                25,
                "drained",
                2_000_000L,
                "membership-payment-callback-1");

        Object snapshot = MembershipPaymentMetrics.class
                .getMethod("workerSnapshot", workerType)
                .invoke(metrics, callback);
        assertThat(value(snapshot, "runCount")).isEqualTo(2L);
        assertThat(value(snapshot, "lastBatches")).isEqualTo(1);
        assertThat(value(snapshot, "lastClaimedItems")).isEqualTo(25);
        assertThat(value(snapshot, "maximumBatches")).isEqualTo(21);
        assertThat(value(snapshot, "maximumClaimedItems")).isEqualTo(2_100);
        assertThat(value(snapshot, "lastOutcome")).isEqualTo("drained");
        assertThat(value(snapshot, "lastThreadName"))
                .isEqualTo("membership-payment-callback-1");
        assertThat((long) value(snapshot, "lastCompletedAtEpochMillis"))
                .isPositive();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class<? extends Enum>) type, name);
    }

    private static Object value(Object record, String accessor) throws Exception {
        return record.getClass().getMethod(accessor).invoke(record);
    }
}
