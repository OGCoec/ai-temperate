package com.example.temperate.service.user.membership.payment.store;

import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRefundRequiredFinalizationCommand;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRejectedCallbackReleaseCommand;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 该存储契约是来批量提交未应用回调的单订单原子操作，使退款直接关单、拒绝回调先释放 Marker 再恢复 MQ。
 */
public interface MembershipPaymentUnappliedCallbackStore {

    Map<String, MembershipOrderTransitionResult> finalizeRefundRequired(
            Collection<MembershipPaymentRefundRequiredFinalizationCommand> commands);

    Map<String, MembershipPaymentMissingSnapshotReleaseOutcome>
            releaseMissingRefundRequired(
                    Collection<MembershipPaymentRefundRequiredFinalizationCommand> commands);

    Set<String> releaseRejected(
            Collection<MembershipPaymentRejectedCallbackReleaseCommand> commands);
}
