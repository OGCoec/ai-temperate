package com.example.temperate.service.user.membership.payment.loadtest;

import java.util.List;

/**
 * 该服务是来让本机真实时间 JMeter 以受控方式触发队列租约恢复、一次性完成故障、执行有界刷盘并读取精确 Redis 工件状态。
 *
 * <p>它只在 loadtest 开关开启时注册，不创建生产业务事实、不暴露 Redis Key，也不提供任意命令执行能力。</p>
 */
public interface MembershipPaymentLoadtestControlService {

    RecoveryProbe recoverOneCallbackProcessing();

    RecoveryProbe recoverOneOrderProcessing();

    void flushOneRun();

    String publishRabbitRetryProbe(String orderId);

    String publishRabbitPoisonProbe(String orderId);

    RedisProbe inspectOrder(String orderId);

    RedisQueueProbe inspectQueues();

    List<OrderArtifactProbe> inspectOrderArtifacts(List<String> orderIds);

    FaultProbe armCallbackCompleteFailure(String orderId);

    FaultProbe inspectFaults();

    /** 该结果是来证明一条任务确实先进入过期 processing、再被恢复并重新交给正常批处理。 */
    record RecoveryProbe(int claimed, int recovered, long processingAfterRecovery) {
    }

    /** 该结果只暴露低基数存在性与队列大小，不返回 Redis Key、回调密钥或业务载荷。 */
    record RedisProbe(
            boolean snapshotPresent,
            boolean callbackMarkerPresent,
            boolean providerResultPresent,
            boolean orderDedupePresent,
            long callbackReadySize,
            long callbackProcessingSize,
            long dirtySize,
            long dirtyProcessingSize) {
    }

    /** 该结果是来独立记录四个异步集合的低基数大小，Runner 用它比较运行前后基线。 */
    record RedisQueueProbe(
            long callbackReadySize,
            long callbackProcessingSize,
            long dirtySize,
            long dirtyProcessingSize) {
    }

    /** 该结果是来批量证明终态订单快照和 callback marker 已清理，不返回实际 Redis Key。 */
    record OrderArtifactProbe(
            String orderId,
            boolean snapshotPresent,
            boolean callbackMarkerPresent) {
    }

    /** 该结果只公开单调触发次数，使 JMeter 证明一次性故障确实发生，不返回已武装订单或内部异常。 */
    record FaultProbe(long callbackCompleteFailureCount) {
    }
}
