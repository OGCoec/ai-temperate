package com.example.temperate.service.user.membership.payment.loadtest;

import java.util.List;

/**
 * 管理八万个持久毫秒边界测试账号的创建、只读检查和本轮订单精确清理。
 *
 * <p>该接口不允许传入用户范围或套餐配置；用户拓扑由固定策略决定，订单清理只接受本轮真实清单。</p>
 */
public interface MembershipPaymentBoundaryFixtureService {

    MembershipPaymentBoundaryFixtureState prepare();

    MembershipPaymentBoundaryFixtureState state();

    MembershipPaymentBoundaryFixtureState reset(List<byte[]> runOrderIds);

    /**
     * 仅清理已停止失败运行留下的完整 PENDING 清单，并恢复固定用户的 FREE 基线。
     *
     * <p>该能力不接受终态或正在关闭的订单，调用方必须先停止生产者、消费者和调度器。</p>
     */
    MembershipPaymentBoundaryFixtureState resetFailedRun(List<byte[]> runOrderIds);

    /**
     * 精确清理当前固定区段的完整真实预热清单，并证明此前正式区段的数据摘要未变化。
     *
     * <p>该入口只恢复当前区段额度，不允许借预热复位改写前序正式用户或任意用户范围。</p>
     */
    MembershipPaymentSegmentWarmupResetState resetSegmentWarmup(
            MembershipPaymentBoundaryLoadtestPolicy.RunScale runScale,
            String groupCode,
            String warmupRunId,
            List<byte[]> warmupOrderIds);
}
