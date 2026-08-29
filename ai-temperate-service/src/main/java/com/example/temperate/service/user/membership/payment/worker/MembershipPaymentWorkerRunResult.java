package com.example.temperate.service.user.membership.payment.worker;

import java.util.Objects;

/**
 * 该结果是来把单轮批次数、领取数和退出原因交给 single-flight 触发器，不携带订单或回调标识。
 */
public record MembershipPaymentWorkerRunResult(
        int batches,
        int claimedItems,
        MembershipPaymentWorkerOutcome outcome) {

    public MembershipPaymentWorkerRunResult {
        if (batches < 0 || claimedItems < 0) {
            throw new IllegalArgumentException("Membership payment worker counts are invalid.");
        }
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
    }

    public static MembershipPaymentWorkerRunResult empty(
            MembershipPaymentWorkerOutcome outcome) {
        return new MembershipPaymentWorkerRunResult(0, 0, outcome);
    }
}
