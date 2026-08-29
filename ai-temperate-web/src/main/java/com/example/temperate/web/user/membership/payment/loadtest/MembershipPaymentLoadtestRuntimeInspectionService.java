package com.example.temperate.web.user.membership.payment.loadtest;

import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteRuntimeSnapshot;

/**
 * 该服务是来汇总本机正式会员支付复测的 Hikari 和自然 Worker 运行证据，不提供重置指标或修改业务状态的能力。
 */
public interface MembershipPaymentLoadtestRuntimeInspectionService {

    RuntimeProbe inspect();

    /** 该响应把同一采样时刻的连接池与两个 Worker 快照绑定，便于外部采集器落盘。 */
    record RuntimeProbe(
            long capturedAtEpochMillis,
            HikariProbe hikari,
            MembershipPaymentMetrics.WorkerRunSnapshot callbackWorker,
            MembershipPaymentMetrics.WorkerRunSnapshot orderPersistWorker,
            MembershipOrderSnapshotWriteRuntimeSnapshot redisWrite) {}

    /** 该响应只暴露连接池容量和聚合时序，不包含 JDBC 地址、账号、SQL 或连接标识。 */
    record HikariProbe(
            boolean poolAvailable,
            String poolName,
            int configuredMaximumPoolSize,
            int configuredMinimumIdle,
            int totalConnections,
            int activeConnections,
            int idleConnections,
            int pendingThreads,
            double timeoutCount,
            TimerProbe acquire,
            TimerProbe usage) {}

    /** 该响应承载 Micrometer Timer 的累计值和受控 P95/P99，不暴露单次请求标签。 */
    record TimerProbe(
            long count,
            double totalSeconds,
            double maximumSeconds,
            double p95Seconds,
            double p99Seconds) {

        public static TimerProbe empty() {
            return new TimerProbe(0L, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }
}
