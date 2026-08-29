package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;

/**
 * 该服务是来在 REJECTED 回调原子释放 Marker 后恢复订单 MQ 时间链。
 *
 * <p>它只恢复 PENDING_PAYMENT 或 CLOSING 的最终检查阶段，不改变订单、回调或 Provider 事实。</p>
 */
public interface MembershipPaymentRejectedCallbackResumeService {

    /**
     * 为 REJECTED 且仍处于活动状态的订单发布一个只等待业务边界的最终检查消息；终态订单保持 no-op。
     *
     * @param order 裁决回调时使用的订单实时快照
     */
    void resume(MembershipOrderSnapshot order);
}
