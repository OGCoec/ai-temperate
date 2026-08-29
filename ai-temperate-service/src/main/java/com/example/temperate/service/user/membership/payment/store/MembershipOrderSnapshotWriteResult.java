package com.example.temperate.service.user.membership.payment.store;

import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import java.util.Objects;

/**
 * 该结果是来保留 Lua 的版本裁决与可选实时快照，使协调器只对缺失或版本跨度场景执行完整恢复。
 */
public record MembershipOrderSnapshotWriteResult(
        MembershipOrderSnapshotWriteOutcome outcome,
        MembershipOrderSnapshot snapshot) {

    public MembershipOrderSnapshotWriteResult {
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
